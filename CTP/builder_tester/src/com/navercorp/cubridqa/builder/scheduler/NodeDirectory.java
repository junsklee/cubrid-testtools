package com.navercorp.cubridqa.builder.scheduler;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Maintains a directory of tester nodes by polling /health endpoints.
 *
 * <p>Runs a background thread that periodically polls each configured tester,
 * updates the cluster state map, and expires stale entries. Provides methods
 * to query healthy nodes and filter by resource availability.</p>
 *
 * <p>Thread-safe for concurrent reads/updates.</p>
 */
public class NodeDirectory {

    private static final Logger logger = Logger.getLogger(NodeDirectory.class.getName());
    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 5;
    private static final long DEFAULT_STALE_THRESHOLD_SECONDS = 30;

    private final List<String> testerNodes;  // List of "host:port" strings
    private final long pollIntervalSeconds;
    private final long staleThresholdSeconds;

    private final ConcurrentHashMap<String, NodeSnapshot> nodeMap;
    private final ScheduledExecutorService pollScheduler;

    public NodeDirectory(List<String> testerNodes) {
        this(testerNodes, DEFAULT_POLL_INTERVAL_SECONDS, DEFAULT_STALE_THRESHOLD_SECONDS);
    }

    public NodeDirectory(List<String> testerNodes, long pollIntervalSeconds, long staleThresholdSeconds) {
        this.testerNodes = new ArrayList<>(testerNodes);
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.staleThresholdSeconds = staleThresholdSeconds;
        this.nodeMap = new ConcurrentHashMap<>();
        this.pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NodeDirectory-Poller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the background polling thread.
     */
    public void start() {
        pollScheduler.scheduleAtFixedRate(
                this::pollAllNodes,
                0,  // initial delay
                pollIntervalSeconds,
                TimeUnit.SECONDS
        );
        logger.info("NodeDirectory started, polling " + testerNodes.size() + " nodes every " + pollIntervalSeconds + "s");
    }

    /**
     * Stops the background polling thread.
     */
    public void stop() {
        pollScheduler.shutdown();
        try {
            if (!pollScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                pollScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("NodeDirectory stopped");
    }

    /**
     * Manually updates a node snapshot (for push-based heartbeats).
     */
    public void updateSnapshot(String nodeId, NodeSnapshot snapshot) {
        nodeMap.put(nodeId, snapshot);
    }

    /**
     * Returns the current snapshot for a node, or null if not present/stale.
     */
    public NodeSnapshot getSnapshot(String nodeId) {
        NodeSnapshot snapshot = nodeMap.get(nodeId);
        if (snapshot == null) {
            return null;
        }
        if (isStale(snapshot)) {
            nodeMap.remove(nodeId);
            return null;
        }
        return snapshot;
    }

    /**
     * Returns all healthy (non-stale, non-degraded) nodes.
     */
    public List<NodeSnapshot> getHealthyNodes() {
        return nodeMap.values().stream()
                .filter(s -> !isStale(s))
                .filter(s -> "healthy".equalsIgnoreCase(s.getStatus()))
                .filter(s -> !s.isDegraded())
                .filter(s -> !s.isDiskPressure())
                .collect(Collectors.toList());
    }

    /**
     * Returns nodes eligible to run a test (has concurrency AND resource headroom).
     */
    public List<NodeSnapshot> getEligibleNodes(TestInstance test) {
        return getHealthyNodes().stream()
                .filter(s -> s.getAvailableConcurrency() > 0)
                .filter(s -> hasResourceHeadroom(s, test))
                .collect(Collectors.toList());
    }

    /**
     * Checks if a node has sufficient resource headroom for a test.
     * Uses dimension-specific and confidence-aware safety margins to prevent oversubscription.
     *
     * CRITICAL FIX: Always applies resource gating, even for unknown predictions (confidence=0).
     * Unknown tests are conservatively estimated rather than bypassing checks entirely.
     *
     * @param node the node to check
     * @param test the test instance with predicted resource demands
     * @return true if node has enough free resources
     */
    private boolean hasResourceHeadroom(NodeSnapshot node, TestInstance test) {
        // TODO: Wire BuilderConfig margins here once config is passed to NodeDirectory
        // For now, use hardcoded production-safe defaults
        final double baseCpu = 0.10;  // 10% base margin for CPU
        final double baseMem = 0.20;  // 20% base margin for memory
        final double baseIo = 0.30;   // 30% base margin for I/O (highest variability)
        final double baseNet = 0.25;  // 25% base margin for network
        final double baseIops = 0.25; // 25% base margin for IOPS
        final double k = 0.50;        // Extra margin factor for low confidence

        // Use scalar confidence for all dimensions
        // Note: TestInstance only has scalar confidence. Per-dimension confidence
        // is available in PredictedDemand (tester-side) but not yet in TestInstance (builder-side).
        final double conf = Math.max(0.0, Math.min(1.0, test.getConfidence()));

        // Compute dimension-specific margins with confidence scaling
        double mCpu = baseCpu + k * (1.0 - conf);
        double mMem = baseMem + k * (1.0 - conf);
        double mIo = baseIo + k * (1.0 - conf);
        double mNet = baseNet + k * (1.0 - conf);
        double mIops = baseIops + k * (1.0 - conf);

        // Compute required resources with margin
        // IMPORTANT: Memory floor is 100MB in BYTES (104857600), not MB
        double requiredCpu = test.getPredictedCpuPct() * (1.0 + mCpu);
        double requiredMem = test.getPredictedMemMb() + Math.max(test.getPredictedMemMb() * mMem, 100.0); // +100MB floor
        double requiredIo = test.getPredictedIoMbPerSec() * (1.0 + mIo);
        double requiredIops = test.getPredictedIops() * (1.0 + mIops);
        double requiredNet = test.getPredictedNetMbPerSec() * (1.0 + mNet);

        // Check headroom across all dimensions
        boolean hasHeadroom = node.getFreeCpuPct() >= requiredCpu
                && node.getFreeMemMb() >= requiredMem
                && node.getFreeIoMbPerSec() >= requiredIo
                && node.getFreeIops() >= requiredIops
                && node.getFreeNetMbPerSec() >= requiredNet;

        if (!hasHeadroom) {
            logger.fine(String.format(
                    "Node %s lacks headroom for test %s (conf=%.2f, margins: cpu=%.0f%% mem=%.0f%% io=%.0f%%): " +
                            "CPU %.1f < %.1f, Mem %.0f < %.0f, IO %.1f < %.1f, IOPS %.0f < %.0f, Net %.1f < %.1f",
                    node.getNodeId(), test.getTestKey(),
                    conf,
                    mCpu * 100, mMem * 100, mIo * 100,
                    node.getFreeCpuPct(), requiredCpu,
                    node.getFreeMemMb(), requiredMem,
                    node.getFreeIoMbPerSec(), requiredIo,
                    node.getFreeIops(), requiredIops,
                    node.getFreeNetMbPerSec(), requiredNet));
        }

        return hasHeadroom;
    }

    /**
     * Returns the number of tracked nodes (including stale).
     */
    public int getNodeCount() {
        return nodeMap.size();
    }

    /**
     * Polls all configured tester nodes and updates snapshots.
     */
    private void pollAllNodes() {
        for (String nodeAddr : testerNodes) {
            try {
                NodeSnapshot snapshot = pollNode(nodeAddr);
                if (snapshot != null) {
                    nodeMap.put(snapshot.getNodeId(), snapshot);
                }
            } catch (Exception e) {
                logger.warning("Failed to poll node " + nodeAddr + ": " + e.getMessage());
            }
        }

        // Remove stale entries
        nodeMap.values().removeIf(this::isStale);
    }

    /**
     * Polls a single node's /health endpoint.
     */
    private NodeSnapshot pollNode(String nodeAddr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + nodeAddr + "/health");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);  // 5 seconds
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                logger.warning("Non-200 response from " + nodeAddr + ": " + responseCode);
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            return NodeSnapshot.fromJSON(json);

        } catch (Exception e) {
            logger.warning("Error polling " + nodeAddr + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Checks if a snapshot is stale (timestamp too old).
     */
    private boolean isStale(NodeSnapshot snapshot) {
        Duration age = Duration.between(snapshot.getTimestamp(), Instant.now());
        return age.getSeconds() > staleThresholdSeconds;
    }
}
