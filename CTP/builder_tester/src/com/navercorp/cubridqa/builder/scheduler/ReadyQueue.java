package com.navercorp.cubridqa.builder.scheduler;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Two-tier ready queue for test scheduling with makespan optimization.
 *
 * <p>Separates tests into "mice" (short tests) and "elephants" (long tests)
 * based on predicted duration threshold. Mice are prioritized using a min-heap
 * by predicted duration with aging boost (shortest first). Elephants are prioritized
 * using a max-heap by predicted duration (longest first) to minimize overall makespan.</p>
 *
 * <p><b>Key Optimization:</b> Elephants use longest-job-first (LJF) scheduling to ensure
 * the critical path (longest tests) starts early in parallel execution, minimizing the
 * time for all tests to complete.</p>
 *
 * <p>Thread-safety: Caller must synchronize access.</p>
 */
public class ReadyQueue {

    private static final long DEFAULT_MICE_THRESHOLD_MS = 20_000L;  // 20 seconds

    private final long miceThresholdMs;

    // Mice: Priority queue sorted by (predicted_duration - age_boost)
    private final PriorityQueue<TestInstance> miceQueue;

    // Elephants: Priority queue sorted by predicted duration (descending - longest first)
    private final PriorityQueue<TestInstance> elephantsQueue;

    public ReadyQueue() {
        this(DEFAULT_MICE_THRESHOLD_MS);
    }

    public ReadyQueue(long miceThresholdMs) {
        this.miceThresholdMs = miceThresholdMs;

        // Mice comparator: sort by effective duration (predicted - age boost)
        this.miceQueue = new PriorityQueue<>(Comparator.comparingLong(this::effectiveDuration));
//        this.miceQueue = new PriorityQueue<>(Comparator.comparingLong(this::effectiveDuration).reversed());

        // Elephants comparator: sort by predicted duration (descending - longest first)
        this.elephantsQueue = new PriorityQueue<>(
            Comparator.comparingLong(TestInstance::getPredictedDurationMs).reversed()
        );
    }

    /**
     * Adds a test to the appropriate queue (mice or elephants).
     */
    public void offer(TestInstance test) {
        if (test.getPredictedDurationMs() <= miceThresholdMs) {
            miceQueue.offer(test);
        } else {
            elephantsQueue.offer(test);
        }
    }

    /**
     * Returns the next mouse test (shortest predicted duration with aging),
     * or null if mice queue is empty.
     */
    public TestInstance pollMouse() {
        return miceQueue.poll();
    }

    /**
     * Returns the next elephant test (longest predicted duration),
     * or null if elephants queue is empty.
     */
    public TestInstance pollElephant() {
        return elephantsQueue.poll();
    }

    /**
     * Removes the given test from either queue.
     */
    public boolean remove(TestInstance test) {
        return miceQueue.remove(test) || elephantsQueue.remove(test);
    }

    /**
     * Returns a snapshot view of all pending tests (across mice and elephants).
     */
    public Set<TestInstance> snapshot() {
        Set<TestInstance> all = new HashSet<>(miceQueue);
        all.addAll(elephantsQueue);
        return all;
    }

    /**
     * Returns true if both queues are empty.
     */
    public boolean isEmpty() {
        return miceQueue.isEmpty() && elephantsQueue.isEmpty();
    }

    /**
     * Returns true if the mice queue is empty.
     */
    public boolean isMiceEmpty() {
        return miceQueue.isEmpty();
    }

    /**
     * Returns true if the elephants queue is empty.
     */
    public boolean isElephantsEmpty() {
        return elephantsQueue.isEmpty();
    }

    /**
     * Returns the total number of pending tests.
     */
    public int size() {
        return miceQueue.size() + elephantsQueue.size();
    }

    /**
     * Returns the number of mice tests.
     */
    public int getMiceCount() {
        return miceQueue.size();
    }

    /**
     * Returns the number of elephant tests.
     */
    public int getElephantsCount() {
        return elephantsQueue.size();
    }

    /**
     * Computes effective duration for mice queue ordering.
     * Applies age boost to reduce effective duration for long-waiting tests.
     */
    private long effectiveDuration(TestInstance test) {
        long waitSeconds = test.getWaitTimeSeconds();
        double ageCap = 300.0;  // 5 minutes cap
        double ageBoost = Math.min(1.0, waitSeconds / ageCap);

        // Reduce effective duration by up to 50% for aged tests
        double reduction = ageBoost * 0.5 * test.getPredictedDurationMs();
        return Math.max(0, test.getPredictedDurationMs() - (long) reduction);
    }

    @Override
    public String toString() {
        return "ReadyQueue{mice=" + miceQueue.size() + ", elephants=" + elephantsQueue.size() + "}";
    }
}
