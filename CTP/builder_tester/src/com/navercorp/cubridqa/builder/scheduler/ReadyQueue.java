package com.navercorp.cubridqa.builder.scheduler;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Two-tier ready queue for test scheduling.
 *
 * <p>Separates tests into "mice" (short tests) and "elephants" (long tests)
 * based on predicted duration threshold. Mice are prioritized using a min-heap
 * by predicted duration with aging boost. Elephants are scored on-demand.</p>
 *
 * <p>Thread-safety: Caller must synchronize access.</p>
 */
public class ReadyQueue {

    private static final long DEFAULT_MICE_THRESHOLD_MS = 20_000L;  // 20 seconds

    private final long miceThresholdMs;

    // Mice: Priority queue sorted by (predicted_duration - age_boost)
    private final PriorityQueue<TestInstance> miceQueue;

    // Elephants: Unordered set, scored at assignment time
    private final Set<TestInstance> elephantsSet;

    public ReadyQueue() {
        this(DEFAULT_MICE_THRESHOLD_MS);
    }

    public ReadyQueue(long miceThresholdMs) {
        this.miceThresholdMs = miceThresholdMs;

        // Mice comparator: sort by effective duration (predicted - age boost)
        this.miceQueue = new PriorityQueue<>(Comparator.comparingLong(this::effectiveDuration));

        this.elephantsSet = new HashSet<>();
    }

    /**
     * Adds a test to the appropriate queue (mice or elephants).
     */
    public void offer(TestInstance test) {
        if (test.getPredictedDurationMs() <= miceThresholdMs) {
            miceQueue.offer(test);
        } else {
            elephantsSet.add(test);
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
     * Returns all elephant tests for scoring by the scheduler.
     */
    public Set<TestInstance> getElephants() {
        return new HashSet<>(elephantsSet);
    }

    /**
     * Removes an elephant test after it's been assigned.
     */
    public boolean removeElephant(TestInstance test) {
        return elephantsSet.remove(test);
    }

    /**
     * Returns true if both queues are empty.
     */
    public boolean isEmpty() {
        return miceQueue.isEmpty() && elephantsSet.isEmpty();
    }

    /**
     * Returns the total number of pending tests.
     */
    public int size() {
        return miceQueue.size() + elephantsSet.size();
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
        return elephantsSet.size();
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
        return "ReadyQueue{mice=" + miceQueue.size() + ", elephants=" + elephantsSet.size() + "}";
    }
}
