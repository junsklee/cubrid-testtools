package com.navercorp.cubridqa.builder.scheduler;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Three-tier ready queue for test scheduling with retry prioritization and makespan optimization.
 *
 * <p>Separates tests into three queues:</p>
 * <ul>
 *   <li><b>Retries:</b> Failed tests being retried (highest priority, oldest first)</li>
 *   <li><b>Mice:</b> Short tests (prioritized by shortest first with aging)</li>
 *   <li><b>Elephants:</b> Long tests (prioritized by longest first for makespan optimization)</li>
 * </ul>
 *
 * <p><b>Retry Priority:</b> Retries are always attempted first to prevent starvation.</p>
 * <p><b>Key Optimization:</b> Elephants use longest-job-first (LJF) scheduling to ensure
 * the critical path (longest tests) starts early in parallel execution, minimizing the
 * time for all tests to complete.</p>
 *
 * <p>Thread-safety: Caller must synchronize access.</p>
 */
public class ReadyQueue {

    private static final long DEFAULT_MICE_THRESHOLD_MS = 20_000L;  // 20 seconds

    private final long miceThresholdMs;

    // Retries: Priority queue sorted by submission time (oldest first), then attempt count (most retries first)
    private final PriorityQueue<TestInstance> retriesQueue;

    // Mice: Priority queue sorted by (predicted_duration - age_boost)
    private final PriorityQueue<TestInstance> miceQueue;

    // Elephants: Priority queue sorted by predicted duration (descending - longest first)
    private final PriorityQueue<TestInstance> elephantsQueue;

    public ReadyQueue() {
        this(DEFAULT_MICE_THRESHOLD_MS);
    }

    public ReadyQueue(long miceThresholdMs) {
        this.miceThresholdMs = miceThresholdMs;

        // Retries comparator: sort by submission time (oldest first), then attempt count (most retries first)
        this.retriesQueue = new PriorityQueue<>(
            Comparator.comparing(TestInstance::getSubmittedAt)
                     .thenComparing(TestInstance::getRetryAttempt, Comparator.reverseOrder())
        );

        // Mice comparator: sort by effective duration (predicted - age boost)
        this.miceQueue = new PriorityQueue<>(Comparator.comparingLong(this::effectiveDuration));

        // Elephants comparator: sort by predicted duration (descending - longest first)
        this.elephantsQueue = new PriorityQueue<>(
            Comparator.comparingLong(TestInstance::getPredictedDurationMs).reversed()
        );
    }

    /**
     * Adds a test to the appropriate queue (retries, mice, or elephants).
     * Retries are routed to the retries queue regardless of duration.
     */
    public void offer(TestInstance test) {
        if (test.isRetry()) {
            retriesQueue.offer(test);
        } else if (test.getPredictedDurationMs() <= miceThresholdMs) {
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
     * Returns the next retry test (oldest submission, then most retries),
     * or null if retries queue is empty.
     */
    public TestInstance pollRetry() {
        return retriesQueue.poll();
    }

    /**
     * Returns the next retry test without removing it,
     * or null if retries queue is empty.
     */
    public TestInstance peekRetry() {
        return retriesQueue.peek();
    }

    /**
     * Returns the next mouse test without removing it,
     * or null if mice queue is empty.
     */
    public TestInstance peekMouse() {
        return miceQueue.peek();
    }

    /**
     * Returns the next elephant test without removing it,
     * or null if elephants queue is empty.
     */
    public TestInstance peekElephant() {
        return elephantsQueue.peek();
    }

    /**
     * Removes the given test from any queue.
     */
    public boolean remove(TestInstance test) {
        return retriesQueue.remove(test) || miceQueue.remove(test) || elephantsQueue.remove(test);
    }

    /**
     * Returns a snapshot view of all pending tests (across retries, mice, and elephants).
     */
    public Set<TestInstance> snapshot() {
        Set<TestInstance> all = new HashSet<>(retriesQueue);
        all.addAll(miceQueue);
        all.addAll(elephantsQueue);
        return all;
    }

    /**
     * Returns true if all queues are empty.
     */
    public boolean isEmpty() {
        return retriesQueue.isEmpty() && miceQueue.isEmpty() && elephantsQueue.isEmpty();
    }

    /**
     * Returns true if the retries queue is empty.
     */
    public boolean isRetriesEmpty() {
        return retriesQueue.isEmpty();
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
        return retriesQueue.size() + miceQueue.size() + elephantsQueue.size();
    }

    /**
     * Returns the number of retry tests.
     */
    public int getRetryCount() {
        return retriesQueue.size();
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
        return "ReadyQueue{retries=" + retriesQueue.size() + ", mice=" + miceQueue.size() + ", elephants=" + elephantsQueue.size() + "}";
    }
}
