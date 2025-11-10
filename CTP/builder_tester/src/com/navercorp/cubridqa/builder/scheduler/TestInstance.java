package com.navercorp.cubridqa.builder.scheduler;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a test instance waiting to be scheduled.
 *
 * <p>Includes test identification (key, commit, baseline), submission time
 * for aging calculations, and predicted resource demands.</p>
 */
public class TestInstance {

    private final String testKey;
    private final String commit;
    private final String baseline;
    private final String imageTag;        // Docker image tag if applicable
    private final String buildPackage;    // Build package name if applicable
    private final Instant submittedAt;
    private final long predictedDurationMs;
    private final double predictedCpuPct;
    private final double predictedMemMb;
    private final double predictedIoMbPerSec;
    private final double predictedIops;
    private final double predictedNetMbPerSec;
    private final double confidence;

    private TestInstance(Builder builder) {
        this.testKey = builder.testKey;
        this.commit = builder.commit;
        this.baseline = builder.baseline;
        this.imageTag = builder.imageTag;
        this.buildPackage = builder.buildPackage;
        this.submittedAt = builder.submittedAt;
        this.predictedDurationMs = builder.predictedDurationMs;
        this.predictedCpuPct = builder.predictedCpuPct;
        this.predictedMemMb = builder.predictedMemMb;
        this.predictedIoMbPerSec = builder.predictedIoMbPerSec;
        this.predictedIops = builder.predictedIops;
        this.predictedNetMbPerSec = builder.predictedNetMbPerSec;
        this.confidence = builder.confidence;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTestKey() {
        return testKey;
    }

    public String getCommit() {
        return commit;
    }

    public String getBaseline() {
        return baseline;
    }

    public String getImageTag() {
        return imageTag;
    }

    public String getBuildPackage() {
        return buildPackage;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public long getPredictedDurationMs() {
        return predictedDurationMs;
    }

    public double getPredictedCpuPct() {
        return predictedCpuPct;
    }

    public double getPredictedMemMb() {
        return predictedMemMb;
    }

    public double getPredictedIoMbPerSec() {
        return predictedIoMbPerSec;
    }

    public double getPredictedIops() {
        return predictedIops;
    }

    public double getPredictedNetMbPerSec() {
        return predictedNetMbPerSec;
    }

    public double getConfidence() {
        return confidence;
    }

    /**
     * Returns wait time in seconds since submission.
     */
    public long getWaitTimeSeconds() {
        return java.time.Duration.between(submittedAt, Instant.now()).getSeconds();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TestInstance)) return false;
        TestInstance that = (TestInstance) o;
        return testKey.equals(that.testKey) && commit.equals(that.commit) && baseline.equals(that.baseline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(testKey, commit, baseline);
    }

    @Override
    public String toString() {
        return "TestInstance{test=" + testKey + ", commit=" + commit.substring(0, Math.min(7, commit.length())) + ", tpred=" + predictedDurationMs + "ms}";
    }

    public static final class Builder {
        private String testKey;
        private String commit;
        private String baseline;
        private String imageTag;
        private String buildPackage;
        private Instant submittedAt = Instant.now();
        private long predictedDurationMs = 30000L;
        private double predictedCpuPct = 50.0;
        private double predictedMemMb = 512.0;
        private double predictedIoMbPerSec = 10.0;
        private double predictedIops = 200.0;
        private double predictedNetMbPerSec = 5.0;
        private double confidence = 0.0;

        private Builder() {
        }

        public Builder testKey(String val) {
            this.testKey = val;
            return this;
        }

        public Builder commit(String val) {
            this.commit = val;
            return this;
        }

        public Builder baseline(String val) {
            this.baseline = val;
            return this;
        }

        public Builder imageTag(String val) {
            this.imageTag = val;
            return this;
        }

        public Builder buildPackage(String val) {
            this.buildPackage = val;
            return this;
        }

        public Builder submittedAt(Instant val) {
            this.submittedAt = val;
            return this;
        }

        public Builder predictedDurationMs(long val) {
            this.predictedDurationMs = val;
            return this;
        }

        public Builder predictedCpuPct(double val) {
            this.predictedCpuPct = val;
            return this;
        }

        public Builder predictedMemMb(double val) {
            this.predictedMemMb = val;
            return this;
        }

        public Builder predictedIoMbPerSec(double val) {
            this.predictedIoMbPerSec = val;
            return this;
        }

        public Builder predictedIops(double val) {
            this.predictedIops = val;
            return this;
        }

        public Builder predictedNetMbPerSec(double val) {
            this.predictedNetMbPerSec = val;
            return this;
        }

        public Builder confidence(double val) {
            this.confidence = val;
            return this;
        }

        public TestInstance build() {
            Objects.requireNonNull(testKey, "testKey");
            Objects.requireNonNull(commit, "commit");
            Objects.requireNonNull(baseline, "baseline");
            return new TestInstance(this);
        }
    }
}
