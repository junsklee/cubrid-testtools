package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.tester.SafeIo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Pool of warm SQL agent containers, keyed by (requestId, commit, buildType).
 *
 * Each agent is a long-lived container with a provisioned test database
 * (created once by sql_env_setup.sh); cases execute via `docker exec
 * sql_run_case.sh` at seconds-per-case instead of paying DB provisioning per
 * case. Agents are replaced when tainted (core file, wedged exec, health
 * failure) or after sql_agent_max_cases executions (DB bloat guard), and reaped
 * when idle. The pool only manages lifecycle/bookkeeping; launching a concrete
 * container is delegated to the executor via {@link AgentLauncher}.
 */
public class SqlAgentPool {

    /** Launches a fresh, fully provisioned agent container. */
    public interface AgentLauncher {
        Agent launch(String poolKey, int agentSeq, Logger log) throws Exception;
    }

    public static class Agent {
        public final String containerName;
        public final Path workDir; // host dir mounted at /workspace
        final String poolKey;
        int casesRun = 0;
        long lastUsedMs = System.currentTimeMillis();
        boolean busy = false;
        boolean tainted = false;

        public Agent(String poolKey, String containerName, Path workDir) {
            this.poolKey = poolKey;
            this.containerName = containerName;
            this.workDir = workDir;
        }
    }

    private final BuilderConfig config;
    private final Map<String, List<Agent>> pools = new HashMap<>();
    private final Object lock = new Object();
    private int agentSeq = 0;

    public SqlAgentPool(BuilderConfig config) {
        this.config = config;
    }

    public static String poolKey(String requestId, String commitShort, String buildType) {
        String safeReq = (requestId == null ? "adhoc" : requestId).replaceAll("[^a-zA-Z0-9_.-]", "_");
        return safeReq + "|" + commitShort + "|" + buildType;
    }

    /**
     * Acquires an idle agent, launching a new one if the pool is below capacity.
     * Returns null when the pool is saturated and no agent frees up within
     * maxWaitMs (callers should fall back to a one-off per-case container).
     */
    public Agent tryAcquire(String poolKey, long maxWaitMs, AgentLauncher launcher, Logger log) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(0, maxWaitMs);
        while (true) {
            Integer launchSeq = null;
            Agent acquired = null;
            List<Agent> idleVictims;
            synchronized (lock) {
                idleVictims = collectIdleVictimsLocked();
                List<Agent> pool = pools.computeIfAbsent(poolKey, k -> new ArrayList<>());
                for (Agent agent : pool) {
                    if (!agent.busy && !agent.tainted) {
                        acquired = agent;
                        agent.busy = true;
                        agent.lastUsedMs = System.currentTimeMillis();
                        break;
                    }
                }
                if (acquired == null && pool.size() < config.getSqlAgentsPerCommit()) {
                    launchSeq = ++agentSeq;
                    // Reserve a slot with a placeholder so concurrent acquirers don't over-launch
                    Agent placeholder = new Agent(poolKey, null, null);
                    placeholder.busy = true;
                    pool.add(placeholder);
                }
            }
            // Destroy idle-reaped agents outside the lock (docker rm is slow)
            destroyAgents(idleVictims, log);
            if (acquired != null) {
                return acquired;
            }
            if (launchSeq != null) {
                Agent agent = null;
                try {
                    agent = launcher.launch(poolKey, launchSeq, log);
                    agent.busy = true;
                    agent.lastUsedMs = System.currentTimeMillis();
                } finally {
                    synchronized (lock) {
                        List<Agent> pool = pools.get(poolKey);
                        if (pool != null) {
                            // Swap the placeholder for the real agent (or drop it on failure)
                            Iterator<Agent> it = pool.iterator();
                            boolean removed = false;
                            while (it.hasNext()) {
                                Agent a = it.next();
                                if (a.containerName == null && a.busy) {
                                    it.remove();
                                    removed = true;
                                    break;
                                }
                            }
                            if (agent != null && removed) {
                                pool.add(agent);
                            }
                        }
                    }
                }
                return agent;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            synchronized (lock) {
                try {
                    lock.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /**
     * Releases an agent back to the pool. Tainted agents (or agents past the
     * max-cases guard) are destroyed instead of reused.
     */
    public void release(Agent agent, boolean taint, Logger log) {
        if (agent == null) {
            return;
        }
        boolean destroy = false;
        synchronized (lock) {
            agent.casesRun++;
            agent.lastUsedMs = System.currentTimeMillis();
            agent.busy = false;
            if (taint || agent.tainted || agent.casesRun >= config.getSqlAgentMaxCases()) {
                agent.tainted = true;
                destroy = true;
                List<Agent> pool = pools.get(agent.poolKey);
                if (pool != null) {
                    pool.remove(agent);
                }
            }
            lock.notifyAll();
        }
        if (destroy) {
            destroyAgent(agent, log);
        }
    }

    /** Tears down all agents belonging to a request (cancel/finalize). */
    public void teardownRequest(String requestId, Logger log) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return;
        }
        String safeReq = requestId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        List<Agent> victims = new ArrayList<>();
        synchronized (lock) {
            Iterator<Map.Entry<String, List<Agent>>> it = pools.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, List<Agent>> entry = it.next();
                if (entry.getKey().startsWith(safeReq + "|")) {
                    victims.addAll(entry.getValue());
                    it.remove();
                }
            }
            lock.notifyAll();
        }
        for (Agent agent : victims) {
            destroyAgent(agent, log);
        }
        if (!victims.isEmpty()) {
            log.info("Tore down " + victims.size() + " SQL agent(s) for request " + requestId);
        }
    }

    /** Removes idle-expired agents from all pools; caller destroys them outside the lock. */
    private List<Agent> collectIdleVictimsLocked() {
        long idleCutoff = System.currentTimeMillis() - config.getSqlAgentIdleTimeoutSec() * 1000L;
        List<Agent> victims = new ArrayList<>();
        for (List<Agent> pool : pools.values()) {
            Iterator<Agent> it = pool.iterator();
            while (it.hasNext()) {
                Agent agent = it.next();
                if (!agent.busy && agent.containerName != null && agent.lastUsedMs < idleCutoff) {
                    it.remove();
                    victims.add(agent);
                }
            }
        }
        return victims;
    }

    private void destroyAgents(List<Agent> agents, Logger log) {
        if (agents == null) {
            return;
        }
        for (Agent agent : agents) {
            destroyAgent(agent, log);
        }
    }

    private void destroyAgent(Agent agent, Logger log) {
        if (agent == null || agent.containerName == null) {
            return;
        }
        try {
            new ProcessBuilder("docker", "rm", "-f", agent.containerName)
                .redirectErrorStream(true)
                .start()
                .waitFor();
        } catch (Exception e) {
            log.warning("Failed to remove SQL agent container " + agent.containerName + ": " + e.getMessage());
        }
        if (agent.workDir != null) {
            try {
                SafeIo.deleteDirectoryWithPrivileges(agent.workDir.toFile(), log);
            } catch (Exception e) {
                log.warning("Failed to delete SQL agent workdir " + agent.workDir + ": " + e.getMessage());
            }
        }
    }
}
