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
        // Weighted selection: decide priority order
        // 80% chance to prefer Elephant (LPT), 20% chance to prefer Mouse (SJF)
        // If one queue is empty, we naturally prioritize the other by skipping the empty one.
        boolean preferElephant = (Math.random() < elephantWeight);

        if (preferElephant) {
            // Strategy: Try Elephant -> Fallback to Mouse
            Optional<Assignment> result = attemptElephant("weighted LJF");
            if (result.isPresent()) return result;

            return attemptMouse("fallback after elephant skipped/filtered");
        } else {
            // Strategy: Try Mouse -> Fallback to Elephant
            Optional<Assignment> result = attemptMouse("weighted SJF");
            if (result.isPresent()) return result;

            return attemptElephant("fallback after mouse skipped/filtered");
        }
    }

    // How many queued tests of a kind are tried per pick. Trying only the head let one test that
    // fits no node (now or ever) block every test behind it while nodes sat idle.
    private static final int LOOKAHEAD = 32;

    private Optional<Assignment> attemptElephant(String reason) {
        if (readyQueue.isElephantsEmpty()) {
            return Optional.empty();
        }
        return attemptInOrder(readyQueue.pollElephants(LOOKAHEAD), "elephant", reason);
    }

    private Optional<Assignment> attemptMouse(String reason) {
        if (readyQueue.isMiceEmpty()) {
            return Optional.empty();
        }
        return attemptInOrder(readyQueue.pollMice(LOOKAHEAD), "mouse", reason);
    }

    /** Assigns the first candidate (in priority order) that fits a node; offers the rest back. */
    private Optional<Assignment> attemptInOrder(List<TestInstance> candidates, String kind, String reason) {
        Optional<Assignment> assignment = Optional.empty();
        for (TestInstance candidate : candidates) {
            if (!assignment.isPresent()) {
                assignment = assignTest(candidate);
                if (assignment.isPresent()) {
                    logger.info(String.format("Assigned %s (%s): %s", kind, reason, assignment.get()));
                    continue;
                }
            }
            readyQueue.offer(candidate);
        }
        return assignment;
    }

    /**
     * Removes and returns every pending test (used when the remaining tests cannot be placed).
     */
    public synchronized List<TestInstance> drainPending() {
        return readyQueue.drainAll();
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
     * Returns current queue statistics.
     */
    public synchronized String getQueueStats() {
        return String.format("Queue: mice=%d, elephants=%d, total=%d",
                readyQueue.getMiceCount(),
                readyQueue.getElephantsCount(),
                readyQueue.size());
    }
}
