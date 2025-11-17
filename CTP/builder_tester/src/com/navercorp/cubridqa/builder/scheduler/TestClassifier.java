package com.navercorp.cubridqa.builder.scheduler;

import java.util.Objects;

/**
 * Classifies tests into duration × IO buckets to support mix targets and safety caps.
 *
 * <p>This is a lightweight helper: it only computes classes based on configured
 * thresholds and the predicted demand already present on {@link TestInstance}.
 * There are no side effects or mutable state.</p>
 */
public class TestClassifier {

    public enum DurationClass { SHORT, MEDIUM, LONG }

    public enum IoClass { LIGHT, MEDIUM, HEAVY }

    public static final class TestClass {
        private final DurationClass durationClass;
        private final IoClass ioClass;

        public TestClass(DurationClass durationClass, IoClass ioClass) {
            this.durationClass = Objects.requireNonNull(durationClass, "durationClass");
            this.ioClass = Objects.requireNonNull(ioClass, "ioClass");
        }

        public DurationClass getDurationClass() {
            return durationClass;
        }

        public IoClass getIoClass() {
            return ioClass;
        }

        public boolean isLongAndIoHeavy() {
            return durationClass == DurationClass.LONG && ioClass == IoClass.HEAVY;
        }

        public boolean isLongAndIoMedium() {
            return durationClass == DurationClass.LONG && ioClass == IoClass.MEDIUM;
        }

        public boolean isLongAndIoLight() {
            return durationClass == DurationClass.LONG && ioClass == IoClass.LIGHT;
        }

        @Override
        public String toString() {
            return durationClass + "_" + ioClass;
        }
    }

    private final long shortThresholdMs;
    private final long mediumThresholdMs;
    private final double ioLightThreshold;
    private final double ioHeavyThreshold;

    /**
     * @param shortThresholdMs  Duration at or below this is SHORT
     * @param mediumThresholdMs Duration above shortThresholdMs and at/below this is MEDIUM; above is LONG
     * @param ioLightThreshold  IO throughput at or below this is IO-light
     * @param ioHeavyThreshold  IO throughput above this is IO-heavy; between is IO-medium
     */
    public TestClassifier(long shortThresholdMs,
                          long mediumThresholdMs,
                          double ioLightThreshold,
                          double ioHeavyThreshold) {
        if (shortThresholdMs <= 0 || mediumThresholdMs <= 0 || mediumThresholdMs < shortThresholdMs) {
            throw new IllegalArgumentException("Duration thresholds must be positive and medium >= short");
        }
        if (ioLightThreshold < 0 || ioHeavyThreshold <= 0 || ioHeavyThreshold < ioLightThreshold) {
            throw new IllegalArgumentException("IO thresholds must be non-negative and heavy > light");
        }
        this.shortThresholdMs = shortThresholdMs;
        this.mediumThresholdMs = mediumThresholdMs;
        this.ioLightThreshold = ioLightThreshold;
        this.ioHeavyThreshold = ioHeavyThreshold;
    }

    /**
     * Classify a test using its predicted duration and IO throughput.
     */
    public TestClass classify(TestInstance test) {
        Objects.requireNonNull(test, "test");

        DurationClass durationClass = classifyDuration(test.getPredictedDurationMs());
        IoClass ioClass = classifyIo(test.getPredictedIoReadMbPerSec(), test.getPredictedIoWriteMbPerSec());

        return new TestClass(durationClass, ioClass);
    }

    private DurationClass classifyDuration(long predictedDurationMs) {
        if (predictedDurationMs <= shortThresholdMs) {
            return DurationClass.SHORT;
        }
        if (predictedDurationMs <= mediumThresholdMs) {
            return DurationClass.MEDIUM;
        }
        return DurationClass.LONG;
    }

    private IoClass classifyIo(double ioReadMbPerSec, double ioWriteMbPerSec) {
        // Use total sustained IO as a coarse proxy; this keeps the classifier simple and robust.
        double totalIo = Math.max(0.0, ioReadMbPerSec) + Math.max(0.0, ioWriteMbPerSec);

        if (totalIo <= ioLightThreshold) {
            return IoClass.LIGHT;
        }
        if (totalIo <= ioHeavyThreshold) {
            return IoClass.MEDIUM;
        }
        return IoClass.HEAVY;
    }
}
