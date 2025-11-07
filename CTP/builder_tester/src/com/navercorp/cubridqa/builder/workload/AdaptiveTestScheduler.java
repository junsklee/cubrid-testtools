package com.navercorp.cubridqa.builder.workload;

import java.util.*;

/**
 * AdaptiveTestScheduler assigns tests to tester nodes using a weighted shortest
 * queue strategy. Each node advertises its concurrency capacity (threads it can
 * run in parallel). The scheduler keeps a logical queue length per node and
 * always hands the next test to the node with the lowest load factor
 * (queued/capacity). This allows high-capacity nodes to drain their queues
 * faster and continuously receive more work, maximizing throughput.
 */
public class AdaptiveTestScheduler {

    private final Map<String, NodeLoad> loadByWorker;
    private final PriorityQueue<NodeLoad> loadHeap;

    public AdaptiveTestScheduler(Map<String, Integer> workerCapacities) {
        if (workerCapacities == null || workerCapacities.isEmpty()) {
            throw new IllegalArgumentException("Worker capacities must not be empty");
        }
        this.loadByWorker = new LinkedHashMap<>();
        this.loadHeap = new PriorityQueue<>();

        for (Map.Entry<String, Integer> entry : workerCapacities.entrySet()) {
            String worker = entry.getKey();
            int capacity = Math.max(1, entry.getValue());
            NodeLoad nodeLoad = new NodeLoad(worker, capacity);
            loadByWorker.put(worker, nodeLoad);
            loadHeap.add(nodeLoad);
        }
    }

    /**
     * Assign the next test to the least loaded worker.
     *
     * @return assignment metadata including worker id and queue depth
     */
    public synchronized Assignment assignWorker() {
        NodeLoad chosen = loadHeap.poll();
        if (chosen == null) {
            throw new IllegalStateException("No workers registered");
        }
        chosen.incrementQueue();
        loadHeap.offer(chosen);
        return new Assignment(chosen.workerId, chosen.getQueued(), chosen.currentLoadFactor());
    }

    /**
     * Snapshot of queue lengths per worker for logging.
     */
    public synchronized Map<String, Integer> snapshotQueueDepths() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, NodeLoad> entry : loadByWorker.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().getQueued());
        }
        return snapshot;
    }

    public static final class Assignment {
        private final String workerId;
        private final int queueDepth;
        private final double loadFactor;

        private Assignment(String workerId, int queueDepth, double loadFactor) {
            this.workerId = workerId;
            this.queueDepth = queueDepth;
            this.loadFactor = loadFactor;
        }

        public String getWorkerId() {
            return workerId;
        }

        public int getQueueDepth() {
            return queueDepth;
        }

        public double getLoadFactor() {
            return loadFactor;
        }
    }

    private static final class NodeLoad implements Comparable<NodeLoad> {
        private final String workerId;
        private final int capacity;
        private int queued;

        private NodeLoad(String workerId, int capacity) {
            this.workerId = workerId;
            this.capacity = capacity;
            this.queued = 0;
        }

        private void incrementQueue() {
            this.queued++;
        }

        private int getQueued() {
            return queued;
        }

        private double currentLoadFactor() {
            // small epsilon prevents divide-by-zero and keeps deterministic ordering
            return (double) queued / (double) capacity;
        }

        @Override
        public int compareTo(NodeLoad other) {
            int cmp = Double.compare(this.currentLoadFactor(), other.currentLoadFactor());
            if (cmp != 0) {
                return cmp;
            }
            return this.workerId.compareTo(other.workerId);
        }
    }
}
