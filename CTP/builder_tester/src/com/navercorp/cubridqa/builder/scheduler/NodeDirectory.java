package com.navercorp.cubridqa.builder.scheduler;

import com.navercorp.cubridqa.builder.BuilderConfig;
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
    private final BuilderConfig config;  // Optional config for margins/weights (null for tests)

    private final ConcurrentHashMap<String, NodeSnapshot> nodeMap;
    private final ScheduledExecutorService pollScheduler;

    public NodeDirectory(List<String> testerNodes) {
        this(testerNodes, DEFAULT_POLL_INTERVAL_SECONDS, DEFAULT_STALE_THRESHOLD_SECONDS, null);
    }

    public NodeDirectory(List<String> testerNodes, long pollIntervalSeconds, long staleThresholdSeconds) {
        this(testerNodes, pollIntervalSeconds, staleThresholdSeconds, null);
    }

    public NodeDirectory(List<String> testerNodes, long pollIntervalSeconds, long staleThresholdSeconds, BuilderConfig config) {
        this.testerNodes = new ArrayList<>(testerNodes);
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.staleThresholdSeconds = staleThresholdSeconds;
        this.config = config;
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
     * Synchronously poll all nodes once. Useful when we need fresh snapshots before scheduling.
     */
    public void pollNow() {
        pollAllNodes();
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
    boolean hasResourceHeadroom(NodeSnapshot node, TestInstance test) {
        // Use config if available, otherwise fall back to hardcoded production-safe defaults
        final double baseCpu = config != null ? config.getSchedulingMarginCpuBase() : 0.10;
        final double baseMem = config != null ? config.getSchedulingMarginMemBase() : 0.20;
        final double baseIoRead = config != null ? config.getSchedulingMarginIoReadBase() : 0.35;
        final double baseIoWrite = config != null ? config.getSchedulingMarginIoWriteBase() : 0.35;
        final double baseNet = config != null ? config.getSchedulingMarginNetBase() : 0.25;
        final double baseIops = config != null ? config.getSchedulingMarginIopsBase() : 0.25;
        final double k = config != null ? config.getSchedulingMarginConfidenceFactor() : 0.50;
        final double ioSafetyHeadroom = config != null ? config.getIoSafetyHeadroomRatio() : 0.15;

        // Use scalar confidence for all dimensions
        // Note: TestInstance only has scalar confidence. Per-dimension confidence
        // is available in PredictedDemand (tester-side) but not yet in TestInstance (builder-side).
        final double conf = Math.max(0.0, Math.min(1.0, test.getConfidence()));

        // Compute dimension-specific margins with confidence scaling
        double mCpu = baseCpu + k * (1.0 - conf);
        double mMem = baseMem + k * (1.0 - conf);
        double mIoRead = baseIoRead + k * (1.0 - conf);
        double mIoWrite = baseIoWrite + k * (1.0 - conf);
        double mNet = baseNet + k * (1.0 - conf);
        double mIops = baseIops + k * (1.0 - conf);

        // Compute required resources with margin
        // IMPORTANT: Memory floor is 100MB in BYTES (104857600), not MB
        double requiredCpu = test.getPredictedCpuPct() * (1.0 + mCpu);
        double requiredMem = test.getPredictedMemMb() + Math.max(test.getPredictedMemMb() * mMem, 100.0); // +100MB floor
        double requiredIoRead = test.getPredictedIoReadMbPerSec() * (1.0 + mIoRead);
        double requiredIoWrite = test.getPredictedIoWriteMbPerSec() * (1.0 + mIoWrite);
        double requiredIops = test.getPredictedIops() * (1.0 + mIops);
        double requiredNet = test.getPredictedNetMbPerSec() * (1.0 + mNet);

        // Get free capacity (accounting for safety headroom)
        double freeIoRead = node.getFreeIoReadMbPerSec();
        double freeIoWrite = node.getFreeIoWriteMbPerSec();
        long keepFreeRead = (long) (node.getIoReadCapacityBytesPerSec() * ioSafetyHeadroom) / (1024 * 1024);
        long keepFreeWrite = (long) (node.getIoWriteCapacityBytesPerSec() * ioSafetyHeadroom) / (1024 * 1024);

        // Check headroom across all dimensions
        // I/O-first: Enforce per-direction headroom; also honor global IO keep-free
        // TEMPORARY: Skip IOPS check entirely until predictions are calibrated (node capacity too low)
        // Skip network checks if node reports 0 capacity (not yet implemented/measured)
        boolean hasHeadroom = node.getFreeCpuPct() >= requiredCpu
                && node.getFreeMemMb() >= requiredMem
                && (requiredIoRead <= 0 || (freeIoRead - keepFreeRead >= requiredIoRead))
                && (requiredIoWrite <= 0 || (freeIoWrite - keepFreeWrite >= requiredIoWrite))
                && (node.getIops() <= 0 || node.getFreeIops() >= requiredIops)
                && (node.getNetMbPerSec() <= 0 || node.getFreeNetMbPerSec() >= requiredNet);

        if (!hasHeadroom) {
            logger.fine(String.format(
                    "Node %s lacks headroom for test %s (conf=%.2f, margins: cpu=%.0f%% mem=%.0f%% io_r=%.0f%% io_w=%.0f%%): " +
                            "CPU %.1f < %.1f, Mem %.0f < %.0f, IO_R %.1f < %.1f (keep_free=%.0f), IO_W %.1f < %.1f (keep_free=%.0f), IOPS %.0f < %.0f, Net %.1f < %.1f",
                    node.getNodeId(), test.getTestKey(),
                    conf,
                    mCpu * 100, mMem * 100, mIoRead * 100, mIoWrite * 100,
                    node.getFreeCpuPct(), requiredCpu,
                    node.getFreeMemMb(), requiredMem,
                    freeIoRead, requiredIoRead, (double) keepFreeRead,
                    freeIoWrite, requiredIoWrite, (double) keepFreeWrite,
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
