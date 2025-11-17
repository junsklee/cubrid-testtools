package com.navercorp.cubridqa.builder.scheduler;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks cluster-wide mix of running tests by duration class to bias scheduling toward targets.
 *
 * <p>State is minimal and concurrency-safe. Targets are expressed as fractions of total
 * concurrency; callers pass in the current cluster concurrency when making decisions.</p>
 */
public class ClusterMixTracker {

    private final AtomicInteger runningShort = new AtomicInteger();
    private final AtomicInteger runningMedium = new AtomicInteger();
    private final AtomicInteger runningLong = new AtomicInteger();

    private final double targetLongFraction;
    private final double targetMediumFraction;
    private final double targetShortFraction;

    public ClusterMixTracker(double targetLongFraction, double targetMediumFraction, double targetShortFraction) {
        double total = targetLongFraction + targetMediumFraction + targetShortFraction;
        if (total <= 0.0) {
            throw new IllegalArgumentException("Mix targets must sum to a positive value");
        }
        this.targetLongFraction = targetLongFraction / total;
        this.targetMediumFraction = targetMediumFraction / total;
        this.targetShortFraction = targetShortFraction / total;
    }

    public void onStart(TestClassifier.DurationClass durationClass) {
        delta(durationClass, 1);
    }

    public void onFinish(TestClassifier.DurationClass durationClass) {
        delta(durationClass, -1);
    }

    public int getRunningCount(TestClassifier.DurationClass durationClass) {
        switch (durationClass) {
            case SHORT:
                return runningShort.get();
            case MEDIUM:
                return runningMedium.get();
            case LONG:
            default:
                return runningLong.get();
        }
    }

    /**
     * Returns true if the current running fraction for the class is below its target.
     * The denominator uses the larger of observed running count and provided totalConcurrency
     * to avoid division by very small numbers during ramp-up.
     */
    public boolean isBelowTarget(TestClassifier.DurationClass durationClass, int totalConcurrency) {
        int totalRunning = runningShort.get() + runningMedium.get() + runningLong.get();
        int denominator = Math.max(1, Math.max(totalRunning, totalConcurrency));
        double ratio = getRunningCount(durationClass) / (double) denominator;

        switch (durationClass) {
            case SHORT:
                return ratio < targetShortFraction;
            case MEDIUM:
                return ratio < targetMediumFraction;
            case LONG:
            default:
                return ratio < targetLongFraction;
        }
    }

    private void delta(TestClassifier.DurationClass durationClass, int delta) {
        switch (durationClass) {
            case SHORT:
                runningShort.addAndGet(delta);
                break;
            case MEDIUM:
                runningMedium.addAndGet(delta);
                break;
            case LONG:
            default:
                runningLong.addAndGet(delta);
                break;
        }
    }
}
