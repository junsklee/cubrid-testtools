package com.navercorp.cubridqa.builder.tester.demand;

/**
 * Immutable snapshot of current resource utilization based on running test predictions.
 *
 * <p>Represents the sum of predicted resource demands from all currently executing tests.
 * Used by HealthHandler to report accurate real-time utilization instead of conservative
 * fixed estimates.</p>
 *
 * <p>Thread-safe (immutable).</p>
 */
public class UtilizationSnapshot {

    private final double totalCpuPct;
    private final double totalMemMb;
    private final double totalIoMbPerSec;
    private final double totalIops;
    private final double totalNetMbPerSec;
    private final int testCount;
    private final int defaultCount;  // Tests using conservative defaults

    private UtilizationSnapshot(Builder builder) {
        this.totalCpuPct = builder.totalCpuPct;
        this.totalMemMb = builder.totalMemMb;
        this.totalIoMbPerSec = builder.totalIoMbPerSec;
        this.totalIops = builder.totalIops;
        this.totalNetMbPerSec = builder.totalNetMbPerSec;
        this.testCount = builder.testCount;
        this.defaultCount = builder.defaultCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns empty utilization snapshot (no tests running).
     */
    public static UtilizationSnapshot empty() {
        return builder().build();
    }

    // Getters

    public double getTotalCpuPct() {
        return totalCpuPct;
    }

    public double getTotalMemMb() {
        return totalMemMb;
    }

    public double getTotalIoMbPerSec() {
        return totalIoMbPerSec;
    }

    public double getTotalIops() {
        return totalIops;
    }

    public double getTotalNetMbPerSec() {
        return totalNetMbPerSec;
    }

    public int getTestCount() {
        return testCount;
    }

    public int getDefaultCount() {
        return defaultCount;
    }

    /**
     * Returns true if any tests are using default predictions.
     */
    public boolean hasDefaults() {
        return defaultCount > 0;
    }

    /**
     * Returns true if no tests are running.
     */
    public boolean isEmpty() {
        return testCount == 0;
    }

    @Override
    public String toString() {
        return String.format("UtilizationSnapshot{tests=%d, cpu=%.1f%%, mem=%.0fMB, defaults=%d}",
                testCount, totalCpuPct, totalMemMb, defaultCount);
    }

    public static final class Builder {
        private double totalCpuPct = 0.0;
        private double totalMemMb = 0.0;
        private double totalIoMbPerSec = 0.0;
        private double totalIops = 0.0;
        private double totalNetMbPerSec = 0.0;
        private int testCount = 0;
        private int defaultCount = 0;

        private Builder() {
        }

        public Builder totalCpuPct(double val) {
            this.totalCpuPct = val;
            return this;
        }

        public Builder totalMemMb(double val) {
            this.totalMemMb = val;
            return this;
        }

        public Builder totalIoMbPerSec(double val) {
            this.totalIoMbPerSec = val;
            return this;
        }

        public Builder totalIops(double val) {
            this.totalIops = val;
            return this;
        }

        public Builder totalNetMbPerSec(double val) {
            this.totalNetMbPerSec = val;
            return this;
        }

        public Builder testCount(int val) {
            this.testCount = val;
            return this;
        }

        public Builder defaultCount(int val) {
            this.defaultCount = val;
            return this;
        }

        public UtilizationSnapshot build() {
            return new UtilizationSnapshot(this);
        }
    }
}
