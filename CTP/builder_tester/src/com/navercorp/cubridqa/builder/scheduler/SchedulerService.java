package com.navercorp.cubridqa.builder.scheduler;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Main scheduler service for intelligent test placement with makespan optimization.
 *
 * <p>Implements a multi-resource, cache-aware scheduler that separates tests
 * into "mice" (short) and "elephants" (long), applies bin-packing scoring,
 * and accounts for queue aging and cache locality.</p>
 *
 * <p><b>Makespan Optimization:</b> Schedules elephants (longest tests) before mice to
 * minimize total parallel execution time. The critical path (longest test) determines
 * the overall completion time, so starting long tests early prevents them from becoming
 * bottlenecks at the end of execution.</p>
 *
 * <p>Usage:
 * <pre>
 * SchedulerService scheduler = new SchedulerService(nodeDirectory, scoreFunction, readyQueue);
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

    private final NodeDirectory nodeDirectory;
    private final ScoreFunction scoreFunction;
    private final ReadyQueue readyQueue;

    public SchedulerService(NodeDirectory nodeDirectory, ScoreFunction scoreFunction, ReadyQueue readyQueue) {
        this.nodeDirectory = nodeDirectory;
        this.scoreFunction = scoreFunction;
        this.readyQueue = readyQueue;
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
     * <p>Algorithm (makespan-optimized):
     * <ol>
     *   <li>If elephants queue non-empty, pop longest elephant and score against eligible nodes</li>
     *   <li>Else if mice queue non-empty, pop next mouse and score against eligible nodes</li>
     *   <li>Return Assignment with best (test, node, score) triple, or empty if no eligible nodes</li>
     * </ol>
     * </p>
     *
     * <p><b>Makespan Optimization:</b> By scheduling elephants (longest tests) first, we ensure
     * the critical path starts immediately in parallel execution, minimizing total completion time.</p>
     *
     * @return Assignment if successful, empty if no eligible nodes
     */
    public synchronized Optional<Assignment> assignNext() {
        // Try elephants first (longest-job-first for makespan optimization)
        TestInstance elephant = readyQueue.pollElephant();
        if (elephant != null) {
            Optional<Assignment> assignment = assignTest(elephant);
            if (assignment.isPresent()) {
                logger.info("Assigned elephant (LJF): " + assignment.get());
                return assignment;
            } else {
                // No eligible nodes, re-offer elephant and return empty
                readyQueue.offer(elephant);
                return Optional.empty();
            }
        }

        // Try mice (greedy shortest-job-first with aging)
        TestInstance mouse = readyQueue.pollMouse();
        if (mouse != null) {
            Optional<Assignment> assignment = assignTest(mouse);
            if (assignment.isPresent()) {
                logger.info("Assigned mouse (SJF): " + assignment.get());
                return assignment;
            } else {
                // No eligible nodes, re-offer mouse and return empty
                readyQueue.offer(mouse);
                return Optional.empty();
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
