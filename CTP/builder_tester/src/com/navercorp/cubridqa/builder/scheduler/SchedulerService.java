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
     * Assigns the next test to the best available node with retry-aware prioritization.
     *
     * <p><b>Retry-Aware Priority:</b>
     * <ol>
     *   <li><b>Priority 1 (100%):</b> Retry tests (oldest first, to prevent starvation)</li>
     *   <li><b>Priority 2 (80%):</b> Elephant tests (longest first, for makespan optimization)</li>
     *   <li><b>Priority 3 (20%):</b> Mice tests (shortest first with aging, for throughput)</li>
     * </ol>
     *
     * <p>Retries always attempt scheduling before new tests. This prevents retry starvation
     * during heavy traffic and ensures failed tests are retried promptly.</p>
     *
     * @return Assignment if successful, empty if no eligible nodes
     */
    public synchronized Optional<Assignment> assignNext() {
        boolean hasPendingRetries = !readyQueue.isRetriesEmpty();

        // PRIORITY 1: Always try retries first (100% priority)
        if (hasPendingRetries) {
            TestInstance retry = readyQueue.peekRetry();
            if (retry != null) {
                Optional<Assignment> assignment = assignRetry(retry);
                if (assignment.isPresent()) {
                    readyQueue.pollRetry();  // Consume retry on success
                    // Optimistically increment retryRunning to prevent TOCTOU over-subscription
                    nodeDirectory.incrementRetryRunning(assignment.get().getTargetNodeId());
                    logger.info(String.format("Assigned retry (attempt %d, age %ds): %s",
                            retry.getRetryAttempt(), retry.getWaitTimeSeconds(), assignment.get()));
                    return assignment;
                }
                // Retry couldn't be placed - leave it queued, try new tests
            }
        }

        // PRIORITY 2/3: New tests (weighted elephant/mice selection)
        return assignNewTest(hasPendingRetries);
    }

    /**
     * Attempts to assign a retry test using retry-specific eligibility.
     */
    private Optional<Assignment> assignRetry(TestInstance retry) {
        List<NodeSnapshot> eligible = nodeDirectory.getEligibleNodesForRetry(retry);
        if (eligible.isEmpty()) {
            logger.fine(String.format("No eligible nodes for retry: %s (attempt %d, retryRunning/maxRetry on nodes exhausted)",
                    retry.getTestKey(), retry.getRetryAttempt()));
            return Optional.empty();
        }

        // Score and pick best node
        NodeSnapshot best = scoreAndPickBest(retry, eligible);
        return Optional.of(new Assignment(retry, best.getNodeId(), scoreFunction.score(retry, best)));
    }

    /**
     * Assigns a new (non-retry) test using weighted elephant/mice selection.
     */
    private Optional<Assignment> assignNewTest(boolean hasPendingRetries) {
        // Weighted selection: decide whether to try elephant first
        boolean tryElephantFirst = (Math.random() < elephantWeight) && !readyQueue.isElephantsEmpty();

        if (tryElephantFirst) {
            // Try longest elephant (weighted selection favored this)
            TestInstance elephant = readyQueue.peekElephant();
            if (elephant != null) {
                Optional<Assignment> assignment = assignTest(elephant, hasPendingRetries);
                if (assignment.isPresent()) {
                    readyQueue.pollElephant();  // Consume elephant on success
                    // Optimistically increment runningTests to prevent TOCTOU over-subscription
                    nodeDirectory.incrementRunningTests(assignment.get().getTargetNodeId());
                    logger.info(String.format("Assigned elephant (%.0f%% weighted LJF): %s",
                            elephantWeight * 100, assignment.get()));
                    return assignment;
                }
                // No eligible nodes for elephant, try mice
            }
        }

        // Try mice (either weighted selection chose mice, or elephant failed eligibility)
        if (!readyQueue.isMiceEmpty()) {
            TestInstance mouse = readyQueue.peekMouse();
            if (mouse != null) {
                Optional<Assignment> assignment = assignTest(mouse, hasPendingRetries);
                if (assignment.isPresent()) {
                    readyQueue.pollMouse();  // Consume mouse on success
                    // Optimistically increment runningTests to prevent TOCTOU over-subscription
                    nodeDirectory.incrementRunningTests(assignment.get().getTargetNodeId());
                    logger.info(String.format("Assigned mouse (%.0f%% weighted SJF): %s",
                            (1.0 - elephantWeight) * 100, assignment.get()));
                    return assignment;
                }
            }
        }

        // If we tried elephants first and failed, now try mice as fallback
        if (tryElephantFirst && !readyQueue.isMiceEmpty()) {
            TestInstance mouse = readyQueue.peekMouse();
            if (mouse != null) {
                Optional<Assignment> assignment = assignTest(mouse, hasPendingRetries);
                if (assignment.isPresent()) {
                    readyQueue.pollMouse();  // Consume mouse on success
                    // Optimistically increment runningTests to prevent TOCTOU over-subscription
                    nodeDirectory.incrementRunningTests(assignment.get().getTargetNodeId());
                    logger.info("Assigned mouse (fallback after elephant filtered): " + assignment.get());
                    return assignment;
                }
            }
        }

        // If we tried mice first and failed, now try elephants as fallback
        if (!tryElephantFirst && !readyQueue.isElephantsEmpty()) {
            TestInstance elephant = readyQueue.peekElephant();
            if (elephant != null) {
                Optional<Assignment> assignment = assignTest(elephant, hasPendingRetries);
                if (assignment.isPresent()) {
                    readyQueue.pollElephant();  // Consume elephant on success
                    // Optimistically increment runningTests to prevent TOCTOU over-subscription
                    nodeDirectory.incrementRunningTests(assignment.get().getTargetNodeId());
                    logger.info("Assigned elephant (fallback after mouse filtered): " + assignment.get());
                    return assignment;
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Assigns a specific test to the best eligible node.
     */
    private Optional<Assignment> assignTest(TestInstance test, boolean hasPendingRetries) {
        List<NodeSnapshot> eligibleNodes = nodeDirectory.getEligibleNodes(test, hasPendingRetries);
        if (eligibleNodes.isEmpty()) {
            return Optional.empty();
        }

        NodeSnapshot bestNode = scoreAndPickBest(test, eligibleNodes);
        double bestScore = scoreFunction.score(test, bestNode);
        return Optional.of(new Assignment(test, bestNode.getNodeId(), bestScore));
    }

    /**
     * Scores all eligible nodes and picks the one with lowest score (best fit).
     */
    private NodeSnapshot scoreAndPickBest(TestInstance test, List<NodeSnapshot> eligibleNodes) {
        NodeSnapshot bestNode = null;
        double bestScore = Double.MAX_VALUE;

        for (NodeSnapshot node : eligibleNodes) {
            double score = scoreFunction.score(test, node);
            if (score < bestScore) {
                bestScore = score;
                bestNode = node;
            }
        }

        return bestNode;
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
     * Returns current queue statistics.
     */
    public synchronized String getQueueStats() {
        return String.format("Queue: mice=%d, elephants=%d, total=%d",
                readyQueue.getMiceCount(),
                readyQueue.getElephantsCount(),
                readyQueue.size());
    }
}
