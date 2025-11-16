package com.navercorp.cubridqa.builder.scheduler;

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
 * <p><b>Weighted Round-Robin Algorithm:</b> Uses probabilistic selection (default 70% elephant weight)
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
 * SchedulerService scheduler = new SchedulerService(nodeDirectory, scoreFunction, readyQueue, 0.70);
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
    private static final double DEFAULT_ELEPHANT_WEIGHT = 0.70;  // 70% prefer elephants

    private final NodeDirectory nodeDirectory;
    private final ScoreFunction scoreFunction;
    private final ReadyQueue readyQueue;
    private final double elephantWeight;

    public SchedulerService(NodeDirectory nodeDirectory, ScoreFunction scoreFunction, ReadyQueue readyQueue) {
        this(nodeDirectory, scoreFunction, readyQueue, DEFAULT_ELEPHANT_WEIGHT);
    }

    public SchedulerService(NodeDirectory nodeDirectory, ScoreFunction scoreFunction, ReadyQueue readyQueue, double elephantWeight) {
        this.nodeDirectory = nodeDirectory;
        this.scoreFunction = scoreFunction;
        this.readyQueue = readyQueue;
        this.elephantWeight = Math.max(0.0, Math.min(1.0, elephantWeight));  // Clamp to [0, 1]
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
     *   <li>Use weighted probability to decide elephant vs mice (default 70% elephant)</li>
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

    /**
     * Scores all elephants against all eligible nodes, returns best assignment.
     * @deprecated No longer used. New algorithm uses pollElephant() for longest-first scheduling.
     */
    @Deprecated
    private Optional<Assignment> assignBestElephant() {
        List<NodeSnapshot> eligibleNodes = nodeDirectory.getHealthyNodes();
        if (eligibleNodes.isEmpty()) {
            return Optional.empty();
        }

        TestInstance bestTest = null;
        NodeSnapshot bestNode = null;
        double bestScore = Double.MAX_VALUE;

        for (TestInstance elephant : readyQueue.getElephants()) {
            for (NodeSnapshot node : eligibleNodes) {
                if (node.getAvailableConcurrency() <= 0) {
                    continue;  // Skip nodes with no headroom
                }

                double score = scoreFunction.score(elephant, node);
                if (score < bestScore) {
                    bestScore = score;
                    bestTest = elephant;
                    bestNode = node;
                }
            }
        }

        if (bestTest == null || bestNode == null) {
            return Optional.empty();
        }

        // Remove assigned elephant from set
        readyQueue.removeElephant(bestTest);

        return Optional.of(new Assignment(bestTest, bestNode.getNodeId(), bestScore));
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
