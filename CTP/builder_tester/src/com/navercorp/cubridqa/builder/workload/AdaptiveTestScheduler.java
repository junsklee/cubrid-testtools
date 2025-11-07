package com.navercorp.cubridqa.builder.workload;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AdaptiveTestScheduler hands out worker "leases" dynamically. Each tester
 * contributes a number of slots equal to its max concurrent capacity. When a
 * test starts we acquire a lease (blocking if all slots are busy). When the
 * test finishes we release the lease, immediately making that worker available
 * for the next pending test. Faster nodes naturally re-enter the available pool
 * sooner, so they automatically absorb more work without any static partitioning.
 */
public class AdaptiveTestScheduler {

    private final BlockingQueue<NodeLease> availableSlots;
    private final Map<String, AtomicInteger> inFlight;
    private final Map<String, Integer> capacities;

    public AdaptiveTestScheduler(Map<String, Integer> workerCapacities) {
        if (workerCapacities == null || workerCapacities.isEmpty()) {
            throw new IllegalArgumentException("Worker capacities must not be empty");
        }
        this.availableSlots = new LinkedBlockingQueue<>();
        this.inFlight = new ConcurrentHashMap<>();
        this.capacities = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : workerCapacities.entrySet()) {
            String worker = entry.getKey();
            int capacity = Math.max(1, entry.getValue());
            capacities.put(worker, capacity);
            inFlight.put(worker, new AtomicInteger(0));
            for (int i = 0; i < capacity; i++) {
                availableSlots.offer(new NodeLease(worker, i));
            }
        }
    }

    /**
     * Acquire a worker slot, blocking until some tester has spare capacity.
     */
    public NodeLease acquire() throws InterruptedException {
        NodeLease lease = availableSlots.take();
        inFlight.get(lease.workerId).incrementAndGet();
        return lease;
    }

    /**
     * Release a worker slot so the next pending test can use it.
     */
    public void release(NodeLease lease) {
        if (lease == null) {
            return;
        }
        AtomicInteger counter = inFlight.get(lease.workerId);
        if (counter != null) {
            counter.decrementAndGet();
        }
        availableSlots.offer(lease);
    }

    /**
     * Snapshot of concurrent tests per worker for logging/metrics.
     */
    public Map<String, Integer> snapshotInFlight() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicInteger> entry : inFlight.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return snapshot;
    }

    public Map<String, Integer> getCapacities() {
        return Collections.unmodifiableMap(capacities);
    }

    public static final class NodeLease {
        private final String workerId;
        private final int slotIndex;

        private NodeLease(String workerId, int slotIndex) {
            this.workerId = workerId;
            this.slotIndex = slotIndex;
        }

        public String getWorkerId() {
            return workerId;
        }

        public int getSlotIndex() {
            return slotIndex;
        }
    }
}
