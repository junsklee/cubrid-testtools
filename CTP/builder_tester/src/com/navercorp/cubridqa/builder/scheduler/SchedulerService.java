package com.navercorp.cubridqa.builder.scheduler;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Main scheduler service for intelligent test placement with resource-aware makespan optimization.
 *
 * <p>Implements a multi-resource, cache-aware scheduler that separates tests
 * into "mice" (short) and "elephants" (long), applies bin-packing scoring,
 * and accounts for queue aging and cache locality.</p>
 *
 * <p><b>Weighted Round-Robin Algorithm:</b> Uses probabilistic selection (default 80% elephant weight)
 * to balance makespan optimization with resource safety:
 * <ul>
 *   <li><b>Makespan:</b> Elephants (longest tests) get priority, starting critical path early</li>
 *   <li><b>Safety:</b> Mice fill gaps, preventing elephant pile-up and resource contention</li>
 *   <li><b>Distribution:</b> Resource checks and load penalties spread elephants across nodes</li>
 *   <li><b>Stability:</b> Prevents thrashing, OOM kills, and node crashes from elephant overload</li>
 * </ul>
 * </p>
 *
 * <p><b>Resource Protection:</b> The scheduler never schedules all elephants simultaneously.
 * Instead, weighted selection ensures a mix of elephants (for makespan) and mice (to fill gaps),
 * while existing resource headroom checks and elephant-specific load penalties prevent any single
 * node from being overwhelmed by multiple heavy tests.</p>
 *
 * <p>Usage:
 * <pre>
 * SchedulerService scheduler = new SchedulerService(nodeDirectory, scoreFunction, readyQueue, 0.80);
 * scheduler.offer(Arrays.asList(testInstances));
 * while (scheduler.hasPending()) {
 *     Optional&lt;Assignment&gt; assignment = scheduler.assignNext();
 *     if (assignment.isPresent()) {
 *         // Submit test to assigned node
 *     } else {
 *         // No eligible nodes, wait
 *         break;
 *     }
 * }
 * </pre>
 * </p>
 */
public class SchedulerService {

    private static final Logger logger = Logger.getLogger(SchedulerService.class.getName());
    private static final double DEFAULT_ELEPHANT_WEIGHT = 0.80;  // 80% prefer elephants

    private final NodeDirectory nodeDirectory;
    private final ScoreFunction scoreFunction;
    private final ReadyQueue readyQueue;
    private final double elephantWeight;
    private final com.navercorp.cubridqa.builder.BuilderConfig config;

    public SchedulerService(NodeDirectory nodeDirectory, ScoreFunction scoreFunction, ReadyQueue readyQueue) {
        this(nodeDirectory, scoreFunction, readyQueue, DEFAULT_ELEPHANT_WEIGHT, null);
    }

    public SchedulerService(NodeDirectory nodeDirectory, ScoreFunction scoreFunction, ReadyQueue readyQueue, double elephantWeight) {
        this(nodeDirectory, scoreFunction, readyQueue, elephantWeight, null);
    }

    public SchedulerService(NodeDirectory nodeDirectory, ScoreFunction scoreFunction, ReadyQueue readyQueue, double elephantWeight, com.navercorp.cubridqa.builder.BuilderConfig config) {
        this.nodeDirectory = nodeDirectory;
        this.scoreFunction = scoreFunction;
        this.readyQueue = readyQueue;
        this.elephantWeight = Math.max(0.0, Math.min(1.0, elephantWeight));  // Clamp to [0, 1]
        this.config = config;
    }

    /**
     * Adds tests to the ready queue.
     */
    public synchronized void offer(List<TestInstance> tests) {
        for (TestInstance test : tests) {
            readyQueue.offer(test);
        }
        logger.info("Offered " + tests.size() + " tests to scheduler. Queue: " + readyQueue);
    }

    /**
     * Pull-based assignment: choose the best test for the requesting node using its live metrics.
     * Legacy push-based assignNext() remains unchanged; this method simply consumes from the same
     * queue with a Tetris-style alignment score.
     */
    public synchronized Optional<Assignment> assignForNode(String nodeId, NodeMetrics metrics) {
        NodeSnapshot baseSnapshot = nodeDirectory.getSnapshot(nodeId);
        if (baseSnapshot == null) {
            return Optional.empty();
        }

        NodeSnapshot nodeView = mergeSnapshot(baseSnapshot, metrics);
        TestInstance bestTest = null;
        double bestPriority = Double.NEGATIVE_INFINITY;

        for (TestInstance candidate : readyQueue.snapshot()) {
            if (!nodeDirectory.hasResourceHeadroom(nodeView, candidate)) {
                continue;
            }

            double priority = scoreFunction.alignment(candidate, nodeView) + ageNudge(candidate);
            if (priority > bestPriority) {
                bestPriority = priority;
                bestTest = candidate;
            }
        }

        if (bestTest == null) {
            return Optional.empty();
        }

        readyQueue.remove(bestTest);
        return Optional.of(new Assignment(bestTest, nodeView.getNodeId(), bestPriority));
    }

    /**
     * Returns true if there are pending tests in the queue.
     */
    public synchronized boolean hasPending() {
        return !readyQueue.isEmpty();
    }

    /**
     * Returns the number of pending tests.
     */
    public synchronized int getPendingCount() {
        return readyQueue.size();
    }

    /**
     * Assigns the next test to the best available node.
     *
     * <p>Algorithm (weighted round-robin with resource awareness):
     * <ol>
     *   <li>Use weighted probability to decide elephant vs mice (default 80% elephant)</li>
     *   <li>Try selected type first, fall back to other type if needed</li>
     *   <li>Resource headroom checks and elephant load penalties prevent oversubscription</li>
     *   <li>Return Assignment with best (test, node, score) triple, or empty if no eligible nodes</li>
     * </ol>
     * </p>
     *
     * <p><b>Makespan Optimization with Resource Safety:</b> Elephants (longest tests) get priority
     * via weighted selection, starting the critical path early. However, mice fill gaps to prevent
     * elephant pile-up and resource contention. Existing resource checks and load penalties ensure
     * elephants are distributed across nodes safely.</p>
     *
     * @return Assignment if successful, empty if no eligible nodes
     */
    public synchronized Optional<Assignment> assignNext() {
        // Weighted selection: decide whether to try elephant first
        boolean tryElephantFirst = (Math.random() < elephantWeight) && !readyQueue.isElephantsEmpty();

        if (tryElephantFirst) {
            // Try longest elephant (weighted selection favored this)
            TestInstance elephant = readyQueue.pollElephant();
            if (elephant != null) {
                // Global backpressure: if NO node can host this test, stop trying
                if (!nodeDirectory.hasAnyNodeHeadroomFor(elephant)) {
                    logger.fine("No nodes have headroom for elephant " + elephant.getTestKey() + ", re-offering to queue");
                    elephant.incrementNoHeadroomRounds();

                    // Check if test is unschedulable (too many failures for too long)
                    if (checkAndFailIfUnschedulable(elephant)) {
                        // Test was failed as unschedulable, return empty
                        return Optional.empty();
                    }

                    readyQueue.offer(elephant);
                    // Don't try to assign, return empty to signal backpressure
                    return Optional.empty();
                }

                Optional<Assignment> assignment = assignTest(elephant);
                if (assignment.isPresent()) {
                    logger.info(String.format("Assigned elephant (%.0f%% weighted LJF): %s",
                                             elephantWeight * 100, assignment.get()));
                    return assignment;
                }
                // No eligible nodes for elephant, re-offer and try mice
                readyQueue.offer(elephant);
            }
        }

        // Try mice (either weighted selection chose mice, or elephant failed eligibility)
        if (!readyQueue.isMiceEmpty()) {
            TestInstance mouse = readyQueue.pollMouse();
            if (mouse != null) {
                // Global backpressure: if NO node can host this test, stop trying
                if (!nodeDirectory.hasAnyNodeHeadroomFor(mouse)) {
                    logger.fine("No nodes have headroom for mouse " + mouse.getTestKey() + ", re-offering to queue");
                    mouse.incrementNoHeadroomRounds();

                    // Check if test is unschedulable (too many failures for too long)
                    if (checkAndFailIfUnschedulable(mouse)) {
                        // Test was failed as unschedulable, return empty
                        return Optional.empty();
                    }

                    readyQueue.offer(mouse);
                    // Don't try to assign, return empty to signal backpressure
                    return Optional.empty();
                }

                Optional<Assignment> assignment = assignTest(mouse);
                if (assignment.isPresent()) {
                    logger.info(String.format("Assigned mouse (%.0f%% weighted SJF): %s",
                                             (1.0 - elephantWeight) * 100, assignment.get()));
                    return assignment;
                }
                // No eligible nodes, re-offer mouse
                readyQueue.offer(mouse);
            }
        }

        // If we tried elephants first and failed, now try mice as fallback
        // (we skipped mice earlier because elephant was selected)
        if (tryElephantFirst && !readyQueue.isMiceEmpty()) {
            TestInstance mouse = readyQueue.pollMouse();
            if (mouse != null) {
                Optional<Assignment> assignment = assignTest(mouse);
                if (assignment.isPresent()) {
                    logger.info("Assigned mouse (fallback after elephant filtered): " + assignment.get());
                    return assignment;
                }
                readyQueue.offer(mouse);
            }
        }

        // If we tried mice first and failed, now try elephants as fallback
        if (!tryElephantFirst && !readyQueue.isElephantsEmpty()) {
            TestInstance elephant = readyQueue.pollElephant();
            if (elephant != null) {
                Optional<Assignment> assignment = assignTest(elephant);
                if (assignment.isPresent()) {
                    logger.info("Assigned elephant (fallback after mouse filtered): " + assignment.get());
                    return assignment;
                }
                readyQueue.offer(elephant);
            }
        }

        return Optional.empty();
    }

    /**
     * Assigns a specific test to the best eligible node.
     */
    private Optional<Assignment> assignTest(TestInstance test) {
        List<NodeSnapshot> eligibleNodes = nodeDirectory.getEligibleNodes(test);
        if (eligibleNodes.isEmpty()) {
            return Optional.empty();
        }

        // Score all eligible nodes, pick minimum
        NodeSnapshot bestNode = null;
        double bestScore = Double.MAX_VALUE;

        for (NodeSnapshot node : eligibleNodes) {
            double score = scoreFunction.score(test, node);
            if (score < bestScore) {
                bestScore = score;
                bestNode = node;
            }
        }

        if (bestNode == null) {
            return Optional.empty();
        }

        return Optional.of(new Assignment(test, bestNode.getNodeId(), bestScore));
    }

    private double ageNudge(TestInstance test) {
        double waitSeconds = test.getWaitTimeSeconds();
        double ageCap = 300.0; // 5 minutes
        return Math.min(1.0, waitSeconds / ageCap);
    }

    private NodeSnapshot mergeSnapshot(NodeSnapshot base, NodeMetrics metrics) {
        if (metrics == null) {
            return base;
        }

        NodeSnapshot.Builder builder = NodeSnapshot.builder()
                .nodeId(base.getNodeId())
                .timestamp(Instant.now())
                .status(base.getStatus())
                .maxConcurrentTests(base.getMaxConcurrentTests())
                .runningTests(metrics.getRunningTests())
                .queuedTests(metrics.getQueuedTests())
                .cpuPct(base.getCpuPct())
                .memMb(base.getMemMb())
                .ioMbPerSec(base.getIoMbPerSec())
                .ioReadMbPerSec(base.getIoReadMbPerSec())
                .ioWriteMbPerSec(base.getIoWriteMbPerSec())
                .iops(base.getIops())
                .netMbPerSec(base.getNetMbPerSec())
                .usedCpuPct(metrics.getCpuUsedPct())
                .usedMemMb(metrics.getMemUsedMb())
                .usedIoMbPerSec(metrics.getIoReadUsedMbPerSec() + metrics.getIoWriteUsedMbPerSec())
                .usedIoReadMbPerSec(metrics.getIoReadUsedMbPerSec())
                .usedIoWriteMbPerSec(metrics.getIoWriteUsedMbPerSec())
                .usedIops(metrics.getIopsUsed())
                .usedNetMbPerSec(metrics.getNetUsedMbPerSec())
                .degraded(base.isDegraded())
                .diskPressure(base.isDiskPressure());

        base.getCachedImages().forEach(builder::addCachedImage);
        base.getCachedPackages().forEach(builder::addCachedPackage);

        return builder.build();
    }

    /**
     * Callback when a test completes (for future speculation/feedback).
     */
    public void onCompletion(String testKey, String nodeId, long actualDurationMs) {
        // Future: Track actual vs. predicted for adaptive scheduling
        // Future: Trigger speculation if test took much longer than predicted
    }

    /**
     * Handles a 409 Conflict response from a node.
     * This means the node's local admission check disagreed with our snapshot.
     * We mark the node on cooldown and re-offer the test to the queue.
     *
     * @param test the test that was rejected
     * @param nodeId the node that returned 409
     */
    public synchronized void handle409(TestInstance test, String nodeId) {
        logger.info(String.format("Received 409 from node %s for test %s (attempt %d)",
                nodeId, test.getTestKey(), test.getScheduleAttemptCount() + 1));

        // Mark node on cooldown to prevent hammering
        nodeDirectory.onCapacity409(nodeId);

        // Test was never successfully scheduled, stays PENDING
        test.incrementScheduleAttemptCount();

        // Add small jitter to avoid spinning on same test
        long jitterMs = 1000 + (long) (Math.random() * 2000);  // 1-3 seconds
        test.setEarliestRescheduleTime(java.time.Instant.now().plusMillis(jitterMs));

        // Re-offer to queue (stays in same mice/elephants lane)
        readyQueue.offer(test);

        logger.fine(String.format("Test %s re-offered to queue with %dms jitter (total attempts: %d)",
                test.getTestKey(), jitterMs, test.getScheduleAttemptCount()));
    }

    /**
     * Checks if a test should be marked as unschedulable and fails it if so.
     * A test is unschedulable if it has been blocked too many times for too long.
     *
     * @param test the test to check
     * @return true if test was failed as unschedulable, false otherwise
     */
    private boolean checkAndFailIfUnschedulable(TestInstance test) {
        // Get thresholds from config (defaults: 10 rounds, 10 minutes)
        int maxRounds = (config != null) ? config.getUnschedMaxRounds() : 10;
        long maxWallclockMinutes = (config != null) ? config.getUnschedMaxWallclockMinutes() : 10;

        boolean tooManyRounds = test.getNoHeadroomRounds() >= maxRounds;
        boolean tooLongWaiting = java.time.Duration.between(test.getSubmittedAt(), java.time.Instant.now())
                .toMinutes() >= maxWallclockMinutes;

        if (tooManyRounds && tooLongWaiting) {
            String reason = gatherUnschedulableDiagnostics(test);
            failAsUnschedulable(test, reason);
            return true;
        }

        return false;
    }

    /**
     * Marks a test as permanently failed due to being unschedulable.
     */
    private void failAsUnschedulable(TestInstance test, String reason) {
        logger.severe(String.format(
                "Test %s is UNSCHEDULABLE after %d rounds and %d minutes: %s",
                test.getTestKey(),
                test.getNoHeadroomRounds(),
                java.time.Duration.between(test.getSubmittedAt(), java.time.Instant.now()).toMinutes(),
                reason
        ));

        // Remove from queue - it won't be retried
        readyQueue.remove(test);

        // Future: Report failure to BuilderTask via callback or exception
        // For now, just log the failure
    }

    /**
     * Gathers diagnostic information about why a test is unschedulable.
     */
    private String gatherUnschedulableDiagnostics(TestInstance test) {
        StringBuilder diag = new StringBuilder();
        diag.append(String.format("Test demands: CPU=%.1f%%, Mem=%.0fMB, IO_R=%.1fMB/s, IO_W=%.1fMB/s, IOPS=%.0f, Net=%.1fMB/s, Duration=%dms. ",
                test.getPredictedCpuPct(),
                test.getPredictedMemMb(),
                test.getPredictedIoReadMbPerSec(),
                test.getPredictedIoWriteMbPerSec(),
                test.getPredictedIops(),
                test.getPredictedNetMbPerSec(),
                test.getPredictedDurationMs()));

        // Get node capacities to compare
        List<NodeSnapshot> healthyNodes = nodeDirectory.getHealthyNodes();
        if (healthyNodes.isEmpty()) {
            diag.append("No healthy nodes available.");
        } else {
            diag.append(String.format("Available nodes: %d. ", healthyNodes.size()));
            NodeSnapshot firstNode = healthyNodes.get(0);
            diag.append(String.format("Example node capacity: CPU=%.1f%%, Mem=%.0fMB, IO_R=%.1fMB/s, IO_W=%.1fMB/s, IOPS=%.0f. ",
                    firstNode.getCpuPct(),
                    firstNode.getMemMb(),
                    firstNode.getIoReadMbPerSec(),
                    firstNode.getIoWriteMbPerSec(),
                    firstNode.getIops()));
        }

        return diag.toString();
    }

    /**
     * Returns current queue statistics.
     */
    public synchronized String getQueueStats() {
        return String.format("Queue: mice=%d, elephants=%d, total=%d",
                readyQueue.getMiceCount(),
                readyQueue.getElephantsCount(),
                readyQueue.size());
    }
}
