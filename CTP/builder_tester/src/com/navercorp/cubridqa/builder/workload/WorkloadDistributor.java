package com.navercorp.cubridqa.builder.workload;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * WorkloadDistributor - Manages distribution of build and test workloads across nodes
 *
 * This class ensures:
 * - Sequential build execution (one build at a time across all nodes)
 * - Smart test distribution (prefer same node as build to minimize network transfer)
 * - Efficient node utilization (use free nodes when builder is busy)
 * - Thread-safe coordination
 * - Circuit breaker protection against failing nodes
 */
public class WorkloadDistributor {
    private static final Logger logger = Logger.getLogger(WorkloadDistributor.class.getName());

    // Maximum time to wait for an available node (prevent indefinite blocking)
    private static final long MAX_WAIT_TIME_MS = 5 * 60 * 1000; // 5 minutes

    // Maximum time a build can be assigned before considering it timed out
    private static final long BUILD_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

    private final Map<String, NodeInfo> nodes;
    private final List<String> nodeIds;
    private final Map<String, String> packageLocations; // commit -> nodeId
    private final Map<String, Instant> buildAssignmentTimes; // nodeId -> assignment time
    private final Object buildLock = new Object();
    private final Object testLock = new Object();
    private int nextNodeIndex = 0;

    /**
     * Create a WorkloadDistributor with the given nodes
     *
     * @param nodeIds List of node identifiers (e.g., "localhost:8089", "192.168.1.10:8089")
     */
    public WorkloadDistributor(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("Node list cannot be empty");
        }

        this.nodeIds = new ArrayList<>(nodeIds);
        this.nodes = new ConcurrentHashMap<>();
        this.packageLocations = new ConcurrentHashMap<>();
        this.buildAssignmentTimes = new ConcurrentHashMap<>();

        // Initialize node info
        for (String nodeId : nodeIds) {
            boolean isLocal = isLocalNode(nodeId);
            NodeInfo nodeInfo = new NodeInfo(nodeId, isLocal);
            nodes.put(nodeId, nodeInfo);
            logger.info(String.format("Registered node: %s (local=%s)", nodeId, isLocal));
        }

        logger.info(String.format("WorkloadDistributor initialized with %d node(s)", nodeIds.size()));
    }

    /**
     * Assign a build to the next available node (blocking if all nodes are busy)
     * Uses round-robin strategy to distribute builds across nodes
     *
     * @param commit The commit hash to build
     * @param buildType The build type (debug/release)
     * @param baselineCommit The baseline commit for comparison
     * @return The node ID assigned to this build
     * @throws RuntimeException if no nodes are available within MAX_WAIT_TIME_MS
     */
    public String assignBuild(String commit, String buildType, String baselineCommit) {
        synchronized (buildLock) {
            long startTime = System.currentTimeMillis();
            NodeInfo assignedNode = null;

            // Check for timed-out builds and recover them
            checkAndRecoverTimedOutBuilds();

            while (assignedNode == null) {
                // Check if we've exceeded maximum wait time
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime > MAX_WAIT_TIME_MS) {
                    String errorMsg = String.format(
                        "Failed to assign build after waiting %d seconds. All %d nodes are busy or failing.",
                        elapsedTime / 1000, nodeIds.size());
                    logger.severe(errorMsg);
                    throw new RuntimeException(errorMsg);
                }

                // Try to find an idle and healthy node using round-robin
                for (int i = 0; i < nodeIds.size(); i++) {
                    int index = (nextNodeIndex + i) % nodeIds.size();
                    String nodeId = nodeIds.get(index);
                    NodeInfo node = nodes.get(nodeId);

                    // Skip nodes in circuit breaker backoff
                    if (node.shouldSkip()) {
                        logger.fine(String.format("Skipping node %s (circuit breaker: %d failures, %dms backoff remaining)",
                            nodeId, node.getConsecutiveFailures(), node.getRemainingBackoffMs()));
                        continue;
                    }

                    if (node.isIdle()) {
                        assignedNode = node;
                        nextNodeIndex = (index + 1) % nodeIds.size();
                        break;
                    }
                }

                if (assignedNode == null) {
                    // All nodes are busy or in backoff, wait a bit
                    long remainingWait = MAX_WAIT_TIME_MS - elapsedTime;
                    if (remainingWait > 0) {
                        try {
                            int healthyNodes = getHealthyNodes().size();
                            int totalNodes = nodeIds.size();
                            logger.fine(String.format(
                                "All nodes busy or in backoff (%d/%d healthy), waiting... (%d/%d seconds elapsed)",
                                healthyNodes, totalNodes, elapsedTime / 1000, MAX_WAIT_TIME_MS / 1000));
                            buildLock.wait(Math.min(1000, remainingWait)); // Wait up to 1 second or remaining time
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while waiting for available node", e);
                        }
                    }
                }
            }

            // Assign build to the node
            assignedNode.setStatus(NodeStatus.BUILDING);
            assignedNode.setCurrentBuildCommit(commit);

            // Track assignment time for timeout detection
            buildAssignmentTimes.put(assignedNode.getNodeId(), Instant.now());

            logger.info(String.format("Assigned build for commit %s to node %s (failures: %d)",
                commit.substring(0, Math.min(7, commit.length())),
                assignedNode.getNodeId(),
                assignedNode.getConsecutiveFailures()));

            return assignedNode.getNodeId();
        }
    }

    /**
     * Mark a build as complete and record the package location
     *
     * @param nodeId The node that completed the build
     * @param commit The commit that was built
     * @param packagePath The path to the build package (can be null if build failed)
     */
    public void completeBuild(String nodeId, String commit, String packagePath) {
        synchronized (buildLock) {
            NodeInfo node = nodes.get(nodeId);
            if (node == null) {
                logger.warning("completeBuild called for unknown node: " + nodeId);
                return;
            }

            // Clear build assignment tracking
            buildAssignmentTimes.remove(nodeId);

            // Mark node as idle and clear build commit
            node.setCurrentBuildCommit(null);

            // Only set to IDLE if not running tests
            if (node.getActiveTestCount() == 0) {
                node.setStatus(NodeStatus.IDLE);
            }

            // Record package location if build succeeded
            if (packagePath != null && !packagePath.isEmpty()) {
                node.addLocalPackage(commit);
                packageLocations.put(commit, nodeId);
                // Record success for circuit breaker
                node.recordSuccess();
                logger.info(String.format("Build complete: commit %s on node %s (package: %s)",
                    commit.substring(0, Math.min(7, commit.length())),
                    nodeId,
                    packagePath));
            } else {
                // Build failed - don't record as circuit breaker failure (might be code issue, not node issue)
                // Only network/communication failures should trigger circuit breaker
                logger.warning(String.format("Build failed: commit %s on node %s",
                    commit.substring(0, Math.min(7, commit.length())),
                    nodeId));
            }

            // Notify waiting threads
            buildLock.notifyAll();
        }
    }

    /**
     * Mark a node as failed (e.g., network error, timeout).
     * This triggers the circuit breaker to avoid the node temporarily.
     *
     * @param nodeId The node that failed
     * @param reason The reason for failure
     */
    public void markNodeFailed(String nodeId, String reason) {
        NodeInfo node = nodes.get(nodeId);
        if (node == null) {
            logger.warning("markNodeFailed called for unknown node: " + nodeId);
            return;
        }

        node.recordFailure();
        logger.warning(String.format("Node %s marked as failed (reason: %s, failures: %d/%d, backoff: %dms)",
            nodeId, reason, node.getConsecutiveFailures(), 3, node.getRemainingBackoffMs()));

        synchronized (buildLock) {
            buildLock.notifyAll(); // Wake up any waiting threads to try other nodes
        }
    }

    /**
     * Returns list of healthy nodes (not in circuit breaker backoff).
     */
    public List<NodeInfo> getHealthyNodes() {
        List<NodeInfo> healthy = new ArrayList<>();
        for (NodeInfo node : nodes.values()) {
            if (node.isHealthy() && !node.shouldSkip()) {
                healthy.add(node);
            }
        }
        return healthy;
    }

    /**
     * Checks for builds that have been assigned for too long and recovers them.
     * This prevents indefinite blocking if a node hangs mid-build.
     */
    private void checkAndRecoverTimedOutBuilds() {
        Instant now = Instant.now();
        List<String> timedOutNodes = new ArrayList<>();

        for (Map.Entry<String, Instant> entry : buildAssignmentTimes.entrySet()) {
            String nodeId = entry.getKey();
            Instant assignmentTime = entry.getValue();

            long elapsedMs = now.toEpochMilli() - assignmentTime.toEpochMilli();
            if (elapsedMs > BUILD_TIMEOUT_MS) {
                timedOutNodes.add(nodeId);
            }
        }

        for (String nodeId : timedOutNodes) {
            NodeInfo node = nodes.get(nodeId);
            if (node != null) {
                logger.warning(String.format(
                    "Build on node %s has timed out (exceeded %d minutes), marking as failed and freeing slot",
                    nodeId, BUILD_TIMEOUT_MS / 60000));

                // Mark as failed (triggers circuit breaker)
                node.recordFailure();

                // Clear build state
                node.setCurrentBuildCommit(null);
                if (node.getActiveTestCount() == 0) {
                    node.setStatus(NodeStatus.IDLE);
                }

                buildAssignmentTimes.remove(nodeId);
            }
        }
    }

    /**
     * Assign tests for a specific commit to appropriate nodes
     * Strategy:
     * 1. Prefer the node that built the package (no network transfer)
     * 2. If that node is busy, distribute to other idle nodes
     * 3. Balance load across all available nodes
     *
     * @param commit The commit to test
     * @param testPaths List of test paths to run
     * @return Map of node ID to list of test tasks assigned to that node
     */
    public Map<String, List<TestTask>> assignTests(String commit, List<String> testPaths) {
        synchronized (testLock) {
            Map<String, List<TestTask>> assignments = new HashMap<>();

            // Find the node that built this package
            String builderNodeId = packageLocations.get(commit);
            NodeInfo builderNode = builderNodeId != null ? nodes.get(builderNodeId) : null;

            // If builder node exists and is idle, assign all tests to it (optimal case)
            if (builderNode != null && builderNode.isIdle()) {
                logger.info(String.format("Assigning all %d tests for commit %s to builder node %s (no transfer needed)",
                    testPaths.size(),
                    commit.substring(0, Math.min(7, commit.length())),
                    builderNodeId));

                List<TestTask> tasks = new ArrayList<>();
                for (String testPath : testPaths) {
                    TestTask task = new TestTask(commit, testPath);
                    task.setAssignedNode(builderNodeId);
                    task.setRequiresPackageTransfer(false);
                    tasks.add(task);
                }
                assignments.put(builderNodeId, tasks);
                return assignments;
            }

            // Builder node is busy or doesn't exist, distribute across available nodes
            logger.info(String.format("Builder node %s is busy or unavailable, distributing %d tests across all nodes",
                builderNodeId != null ? builderNodeId : "unknown",
                testPaths.size()));

            // Round-robin distribution across all nodes
            int testIndex = 0;
            for (String testPath : testPaths) {
                String assignedNodeId = nodeIds.get(testIndex % nodeIds.size());
                NodeInfo assignedNode = nodes.get(assignedNodeId);

                TestTask task = new TestTask(commit, testPath);
                task.setAssignedNode(assignedNodeId);

                // Check if package transfer is needed
                boolean needsTransfer = !assignedNode.hasLocalPackage(commit);
                task.setRequiresPackageTransfer(needsTransfer);

                // Add to assignments
                assignments.computeIfAbsent(assignedNodeId, k -> new ArrayList<>()).add(task);

                testIndex++;
            }

            // Log distribution summary
            for (Map.Entry<String, List<TestTask>> entry : assignments.entrySet()) {
                String nodeId = entry.getKey();
                List<TestTask> tasks = entry.getValue();
                long transferCount = tasks.stream().filter(TestTask::requiresPackageTransfer).count();

                logger.info(String.format("  Node %s: %d tests (%d require package transfer)",
                    nodeId, tasks.size(), transferCount));
            }

            return assignments;
        }
    }

    /**
     * Mark the start of test execution on a node
     *
     * @param nodeId The node starting test execution
     * @param commit The commit being tested
     * @param testPath The test being executed
     */
    public void startTest(String nodeId, String commit, String testPath) {
        NodeInfo node = nodes.get(nodeId);
        if (node != null) {
            node.incrementTestCount();
            logger.fine(String.format("Test started on node %s: %s for commit %s (active tests: %d)",
                nodeId, testPath,
                commit.substring(0, Math.min(7, commit.length())),
                node.getActiveTestCount()));
        }
    }

    /**
     * Mark the completion of a test on a node
     *
     * @param nodeId The node that completed the test
     * @param commit The commit that was tested
     * @param testPath The test that completed
     */
    public void completeTest(String nodeId, String commit, String testPath) {
        synchronized (testLock) {
            NodeInfo node = nodes.get(nodeId);
            if (node == null) {
                logger.warning("completeTest called for unknown node: " + nodeId);
                return;
            }

            node.decrementTestCount();

            logger.fine(String.format("Test complete on node %s: %s for commit %s (active tests: %d)",
                nodeId, testPath,
                commit.substring(0, Math.min(7, commit.length())),
                node.getActiveTestCount()));

            // Notify waiting threads
            testLock.notifyAll();
        }
    }

    /**
     * Get the current status of a node
     *
     * @param nodeId The node to query
     * @return The node's current status, or null if node not found
     */
    public NodeStatus getNodeStatus(String nodeId) {
        NodeInfo node = nodes.get(nodeId);
        return node != null ? node.getStatus() : null;
    }

    /**
     * Get the node that has the build package for a commit
     *
     * @param commit The commit to query
     * @return The node ID that has the package, or null if not found
     */
    public String getPackageLocation(String commit) {
        return packageLocations.get(commit);
    }

    /**
     * Check if a node is local (localhost or 127.0.0.1 or 192.168.x.x)
     *
     * @param nodeId The node ID to check
     * @return true if the node is local
     */
    public boolean isNodeLocal(String nodeId) {
        NodeInfo node = nodes.get(nodeId);
        return node != null && node.isLocal();
    }

    /**
     * Get all registered nodes
     *
     * @return List of all node IDs
     */
    public List<String> getAllNodes() {
        return new ArrayList<>(nodeIds);
    }

    /**
     * Get current status summary of all nodes
     *
     * @return Map of node ID to status string
     */
    public Map<String, String> getStatusSummary() {
        Map<String, String> summary = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            NodeInfo node = nodes.get(nodeId);
            if (node != null) {
                summary.put(nodeId, node.toString());
            }
        }
        return summary;
    }

    /**
     * Determine if a node identifier represents a local node
     *
     * @param nodeId The node identifier
     * @return true if the node is local
     */
    private static boolean isLocalNode(String nodeId) {
        String host = nodeId;

        // Extract host from "host:port" format
        if (nodeId.contains(":")) {
            host = nodeId.substring(0, nodeId.indexOf(":"));
        }

        // Check for local addresses
        return "localhost".equalsIgnoreCase(host) ||
               "127.0.0.1".equals(host) ||
               host.startsWith("192.168.") ||
               host.startsWith("10.") ||
               host.startsWith("172.");
    }
}
