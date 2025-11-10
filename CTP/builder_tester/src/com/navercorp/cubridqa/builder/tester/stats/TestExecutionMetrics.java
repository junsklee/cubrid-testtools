package com.navercorp.cubridqa.builder.tester.stats;

import java.util.Objects;

/**
 * Captures per-attempt execution metrics gathered by a tester node.
 *
 * <p>This is intentionally lightweight – many fields may remain at their default
 * sentinel values (typically {@code -1}) until richer sampling is implemented.
 * The structure exists so later tasks can simply populate additional metrics
 * without refactoring the call sites.</p>
 */
public final class TestExecutionMetrics {

    public static final double UNKNOWN_DOUBLE = -1.0d;
    public static final long UNKNOWN_LONG = -1L;

    private final long durationMs;
    private final double cpuPctMean;
    private final double cpuPctPeak;
    private final double memMbMean;
    private final double memMbPeak;
    private final double ioMbPerSecMean;
    private final double iopsMean;
    private final double netMbPerSecMean;
    private final long bytesReadMb;
    private final long bytesWriteMb;
    private final boolean dockerImageCached;
    private final boolean packageCached;
    private final String dockerImage;
    private final String buildPackageName;
    private final long logSizeBytes;
    private final boolean metricsComplete;

    private TestExecutionMetrics(Builder builder) {
        this.durationMs = builder.durationMs;
        this.cpuPctMean = builder.cpuPctMean;
        this.cpuPctPeak = builder.cpuPctPeak;
        this.memMbMean = builder.memMbMean;
        this.memMbPeak = builder.memMbPeak;
        this.ioMbPerSecMean = builder.ioMbPerSecMean;
        this.iopsMean = builder.iopsMean;
        this.netMbPerSecMean = builder.netMbPerSecMean;
        this.bytesReadMb = builder.bytesReadMb;
        this.bytesWriteMb = builder.bytesWriteMb;
        this.dockerImageCached = builder.dockerImageCached;
        this.packageCached = builder.packageCached;
        this.dockerImage = builder.dockerImage;
        this.buildPackageName = builder.buildPackageName;
        this.logSizeBytes = builder.logSizeBytes;
        this.metricsComplete = builder.metricsComplete;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public double getCpuPctMean() {
        return cpuPctMean;
    }

    public double getCpuPctPeak() {
        return cpuPctPeak;
    }

    public double getMemMbMean() {
        return memMbMean;
    }

    public double getMemMbPeak() {
        return memMbPeak;
    }

    public double getIoMbPerSecMean() {
        return ioMbPerSecMean;
    }

    public double getIopsMean() {
        return iopsMean;
    }

    public double getNetMbPerSecMean() {
        return netMbPerSecMean;
    }

    public long getBytesReadMb() {
        return bytesReadMb;
    }

    public long getBytesWriteMb() {
        return bytesWriteMb;
    }

    public boolean isDockerImageCached() {
        return dockerImageCached;
    }

    public boolean isPackageCached() {
        return packageCached;
    }

    public String getDockerImage() {
        return dockerImage;
    }

    public String getBuildPackageName() {
        return buildPackageName;
    }

    public long getLogSizeBytes() {
        return logSizeBytes;
    }

    public boolean isMetricsComplete() {
        return metricsComplete;
    }

    public static TestExecutionMetrics unknown() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.durationMs = this.durationMs;
        builder.cpuPctMean = this.cpuPctMean;
        builder.cpuPctPeak = this.cpuPctPeak;
        builder.memMbMean = this.memMbMean;
        builder.memMbPeak = this.memMbPeak;
        builder.ioMbPerSecMean = this.ioMbPerSecMean;
        builder.iopsMean = this.iopsMean;
        builder.netMbPerSecMean = this.netMbPerSecMean;
        builder.bytesReadMb = this.bytesReadMb;
        builder.bytesWriteMb = this.bytesWriteMb;
        builder.dockerImageCached = this.dockerImageCached;
        builder.packageCached = this.packageCached;
        builder.dockerImage = this.dockerImage;
        builder.buildPackageName = this.buildPackageName;
        builder.logSizeBytes = this.logSizeBytes;
        builder.metricsComplete = this.metricsComplete;
        return builder;
    }

    public static final class Builder {
        private long durationMs = UNKNOWN_LONG;
        private double cpuPctMean = UNKNOWN_DOUBLE;
        private double cpuPctPeak = UNKNOWN_DOUBLE;
        private double memMbMean = UNKNOWN_DOUBLE;
        private double memMbPeak = UNKNOWN_DOUBLE;
        private double ioMbPerSecMean = UNKNOWN_DOUBLE;
        private double iopsMean = UNKNOWN_DOUBLE;
        private double netMbPerSecMean = UNKNOWN_DOUBLE;
        private long bytesReadMb = UNKNOWN_LONG;
        private long bytesWriteMb = UNKNOWN_LONG;
        private boolean dockerImageCached = false;
        private boolean packageCached = false;
        private String dockerImage;
        private String buildPackageName;
        private long logSizeBytes = UNKNOWN_LONG;
        private boolean metricsComplete = false;

        private Builder() { }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder cpuPctMean(double cpuPctMean) {
            this.cpuPctMean = cpuPctMean;
            return this;
        }

        public Builder cpuPctPeak(double cpuPctPeak) {
            this.cpuPctPeak = cpuPctPeak;
            return this;
        }

        public Builder memMbMean(double memMbMean) {
            this.memMbMean = memMbMean;
            return this;
        }

        public Builder memMbPeak(double memMbPeak) {
            this.memMbPeak = memMbPeak;
            return this;
        }

        public Builder ioMbPerSecMean(double ioMbPerSecMean) {
            this.ioMbPerSecMean = ioMbPerSecMean;
            return this;
        }

        public Builder iopsMean(double iopsMean) {
            this.iopsMean = iopsMean;
            return this;
        }

        public Builder netMbPerSecMean(double netMbPerSecMean) {
            this.netMbPerSecMean = netMbPerSecMean;
            return this;
        }

        public Builder bytesReadMb(long bytesReadMb) {
            this.bytesReadMb = bytesReadMb;
            return this;
        }

        public Builder bytesWriteMb(long bytesWriteMb) {
            this.bytesWriteMb = bytesWriteMb;
            return this;
        }

        public Builder dockerImageCached(boolean dockerImageCached) {
            this.dockerImageCached = dockerImageCached;
            return this;
        }

        public Builder packageCached(boolean packageCached) {
            this.packageCached = packageCached;
            return this;
        }

        public Builder dockerImage(String dockerImage) {
            this.dockerImage = dockerImage;
            return this;
        }

        public Builder buildPackageName(String buildPackageName) {
            this.buildPackageName = buildPackageName;
            return this;
        }

        public Builder logSizeBytes(long logSizeBytes) {
            this.logSizeBytes = logSizeBytes;
            return this;
        }

        public Builder metricsComplete(boolean metricsComplete) {
            this.metricsComplete = metricsComplete;
            return this;
        }

        public TestExecutionMetrics build() {
            return new TestExecutionMetrics(this);
        }
    }

    @Override
    public String toString() {
        return "TestExecutionMetrics{" +
            "durationMs=" + durationMs +
            ", cpuPctMean=" + cpuPctMean +
            ", cpuPctPeak=" + cpuPctPeak +
            ", memMbMean=" + memMbMean +
            ", memMbPeak=" + memMbPeak +
            ", ioMbPerSecMean=" + ioMbPerSecMean +
            ", iopsMean=" + iopsMean +
            ", netMbPerSecMean=" + netMbPerSecMean +
            ", bytesReadMb=" + bytesReadMb +
            ", bytesWriteMb=" + bytesWriteMb +
            ", dockerImageCached=" + dockerImageCached +
            ", packageCached=" + packageCached +
            ", dockerImage='" + dockerImage + '\'' +
            ", buildPackageName='" + buildPackageName + '\'' +
            ", logSizeBytes=" + logSizeBytes +
            ", metricsComplete=" + metricsComplete +
            '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(durationMs, cpuPctMean, cpuPctPeak, memMbMean, memMbPeak,
            ioMbPerSecMean, iopsMean, netMbPerSecMean, bytesReadMb, bytesWriteMb,
            dockerImageCached, packageCached, dockerImage, buildPackageName, logSizeBytes, metricsComplete);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TestExecutionMetrics)) {
            return false;
        }
        TestExecutionMetrics other = (TestExecutionMetrics) obj;
        return durationMs == other.durationMs &&
            Double.compare(other.cpuPctMean, cpuPctMean) == 0 &&
            Double.compare(other.cpuPctPeak, cpuPctPeak) == 0 &&
            Double.compare(other.memMbMean, memMbMean) == 0 &&
            Double.compare(other.memMbPeak, memMbPeak) == 0 &&
            Double.compare(other.ioMbPerSecMean, ioMbPerSecMean) == 0 &&
            Double.compare(other.iopsMean, iopsMean) == 0 &&
            Double.compare(other.netMbPerSecMean, netMbPerSecMean) == 0 &&
            bytesReadMb == other.bytesReadMb &&
            bytesWriteMb == other.bytesWriteMb &&
            dockerImageCached == other.dockerImageCached &&
            packageCached == other.packageCached &&
            Objects.equals(dockerImage, other.dockerImage) &&
            Objects.equals(buildPackageName, other.buildPackageName) &&
            logSizeBytes == other.logSizeBytes &&
            metricsComplete == other.metricsComplete;
    }
}

