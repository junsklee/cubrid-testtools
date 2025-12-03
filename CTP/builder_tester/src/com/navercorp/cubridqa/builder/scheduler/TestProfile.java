package com.navercorp.cubridqa.builder.scheduler;

import java.util.Objects;

/**
 * Immutable profile representing a test's heavy classification based on historical resource usage.
 *
 * <p>Tests are classified into three bands based on their resource consumption ratios
 * relative to global means:
 * <ul>
 *   <li>NORMAL: ratio &lt; 2.0 (typical tests)</li>
 *   <li>HEAVY: 2.0 &le; ratio &lt; 4.0 (resource-intensive tests)</li>
 *   <li>EXTREME: ratio &ge; 4.0 (outlier tests requiring near-exclusive node access)</li>
 * </ul>
 * </p>
 *
 * <p>The heavy class is determined by the maximum band across all resource dimensions
 * (CPU, memory, I/O, IOPS). The dominant dimension indicates which resource is most
 * extreme for this test.</p>
 */
public final class TestProfile {

    /**
     * Classification bands for test heaviness.
     */
    public enum HeavyClass {
        /** Normal test - ratio < 2.0 across all dimensions */
        NORMAL,
        /** Heavy test - 2.0 <= max ratio < 4.0 */
        HEAVY,
        /** Extreme test - max ratio >= 4.0 (should be isolated) */
        EXTREME
    }

    /**
     * The resource dimension that dominates this test's profile.
     */
    public enum DominantDimension {
        CPU,
        MEM,
        IO,
        IOPS,
        /** No single dominant dimension (balanced or unknown) */
        NONE
    }

    /** Default profile for unknown tests - assumes NORMAL classification */
    public static final TestProfile NORMAL_DEFAULT = new TestProfile(
            "unknown",
            HeavyClass.NORMAL,
            DominantDimension.NONE,
            1.0, 1.0, 1.0, 1.0,
            0L, 0.0, 0.0, 0.0, 0.0
    );

    // Thresholds for classification bands
    public static final double HEAVY_THRESHOLD = 2.0;
    public static final double EXTREME_THRESHOLD = 4.0;

    private final String testKey;
    private final HeavyClass heavyClass;
    private final DominantDimension dominantDim;

    // Ratios: test_metric / global_mean
    private final double cpuRatio;
    private final double memRatio;
    private final double ioRatio;
    private final double iopsRatio;

    // Raw stats for reference (used for elephant threshold calculation)
    private final long predictedDurationMs;
    private final double avgCpuPct;
    private final double avgMemMb;
    private final double avgIoMbPerSec;
    private final double avgIops;

    public TestProfile(String testKey, HeavyClass heavyClass, DominantDimension dominantDim,
                       double cpuRatio, double memRatio, double ioRatio, double iopsRatio,
                       long predictedDurationMs, double avgCpuPct, double avgMemMb,
                       double avgIoMbPerSec, double avgIops) {
        this.testKey = Objects.requireNonNull(testKey, "testKey");
        this.heavyClass = Objects.requireNonNull(heavyClass, "heavyClass");
        this.dominantDim = Objects.requireNonNull(dominantDim, "dominantDim");
        this.cpuRatio = cpuRatio;
        this.memRatio = memRatio;
        this.ioRatio = ioRatio;
        this.iopsRatio = iopsRatio;
        this.predictedDurationMs = predictedDurationMs;
        this.avgCpuPct = avgCpuPct;
        this.avgMemMb = avgMemMb;
        this.avgIoMbPerSec = avgIoMbPerSec;
        this.avgIops = avgIops;
    }

    public String getTestKey() {
        return testKey;
    }

    public HeavyClass getHeavyClass() {
        return heavyClass;
    }

    public DominantDimension getDominantDim() {
        return dominantDim;
    }

    public double getCpuRatio() {
        return cpuRatio;
    }

    public double getMemRatio() {
        return memRatio;
    }

    public double getIoRatio() {
        return ioRatio;
    }

    public double getIopsRatio() {
        return iopsRatio;
    }

    public long getPredictedDurationMs() {
        return predictedDurationMs;
    }

    public double getAvgCpuPct() {
        return avgCpuPct;
    }

    public double getAvgMemMb() {
        return avgMemMb;
    }

    public double getAvgIoMbPerSec() {
        return avgIoMbPerSec;
    }

    public double getAvgIops() {
        return avgIops;
    }

    /**
     * Returns the maximum ratio across all dimensions.
     */
    public double getMaxRatio() {
        return Math.max(Math.max(cpuRatio, memRatio), Math.max(ioRatio, iopsRatio));
    }

    /**
     * Returns true if this test is classified as HEAVY or EXTREME.
     */
    public boolean isHeavy() {
        return heavyClass != HeavyClass.NORMAL;
    }

    /**
     * Returns true if this test is classified as EXTREME.
     */
    public boolean isExtreme() {
        return heavyClass == HeavyClass.EXTREME;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TestProfile)) return false;
        TestProfile that = (TestProfile) o;
        return testKey.equals(that.testKey);
    }

    @Override
    public int hashCode() {
        return testKey.hashCode();
    }

    @Override
    public String toString() {
        return String.format("TestProfile{testKey='%s', class=%s, dominant=%s, ratios=[cpu=%.2f, mem=%.2f, io=%.2f, iops=%.2f]}",
                testKey, heavyClass, dominantDim, cpuRatio, memRatio, ioRatio, iopsRatio);
    }

    /**
     * Builder for creating TestProfile instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String testKey;
        private double cpuRatio = 1.0;
        private double memRatio = 1.0;
        private double ioRatio = 1.0;
        private double iopsRatio = 1.0;
        private long predictedDurationMs = 0L;
        private double avgCpuPct = 0.0;
        private double avgMemMb = 0.0;
        private double avgIoMbPerSec = 0.0;
        private double avgIops = 0.0;

        private Builder() {}

        public Builder testKey(String val) {
            this.testKey = val;
            return this;
        }

        public Builder cpuRatio(double val) {
            this.cpuRatio = val;
            return this;
        }

        public Builder memRatio(double val) {
            this.memRatio = val;
            return this;
        }

        public Builder ioRatio(double val) {
            this.ioRatio = val;
            return this;
        }

        public Builder iopsRatio(double val) {
            this.iopsRatio = val;
            return this;
        }

        public Builder predictedDurationMs(long val) {
            this.predictedDurationMs = val;
            return this;
        }

        public Builder avgCpuPct(double val) {
            this.avgCpuPct = val;
            return this;
        }

        public Builder avgMemMb(double val) {
            this.avgMemMb = val;
            return this;
        }

        public Builder avgIoMbPerSec(double val) {
            this.avgIoMbPerSec = val;
            return this;
        }

        public Builder avgIops(double val) {
            this.avgIops = val;
            return this;
        }

        /**
         * Builds the TestProfile, computing heavyClass and dominantDim from ratios.
         */
        public TestProfile build() {
            Objects.requireNonNull(testKey, "testKey");

            // Determine heavy class from max ratio
            double maxRatio = Math.max(Math.max(cpuRatio, memRatio), Math.max(ioRatio, iopsRatio));
            HeavyClass heavyClass;
            if (maxRatio >= EXTREME_THRESHOLD) {
                heavyClass = HeavyClass.EXTREME;
            } else if (maxRatio >= HEAVY_THRESHOLD) {
                heavyClass = HeavyClass.HEAVY;
            } else {
                heavyClass = HeavyClass.NORMAL;
            }

            // Determine dominant dimension (argmax of ratios)
            DominantDimension dominantDim = DominantDimension.NONE;
            if (maxRatio >= HEAVY_THRESHOLD) {
                if (cpuRatio >= maxRatio - 0.001) {
                    dominantDim = DominantDimension.CPU;
                } else if (memRatio >= maxRatio - 0.001) {
                    dominantDim = DominantDimension.MEM;
                } else if (ioRatio >= maxRatio - 0.001) {
                    dominantDim = DominantDimension.IO;
                } else if (iopsRatio >= maxRatio - 0.001) {
                    dominantDim = DominantDimension.IOPS;
                }
            }

            return new TestProfile(testKey, heavyClass, dominantDim,
                    cpuRatio, memRatio, ioRatio, iopsRatio,
                    predictedDurationMs, avgCpuPct, avgMemMb, avgIoMbPerSec, avgIops);
        }
    }
}



