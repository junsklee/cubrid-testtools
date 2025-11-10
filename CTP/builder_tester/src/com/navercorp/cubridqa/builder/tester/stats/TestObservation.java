package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONObject;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable representation of a single test execution observed by a tester node.
 *
 * <p>This object intentionally mirrors the JSON schema outlined in the
 * scheduling redesign document so it can be appended directly to the
 * wal-style JSONL file.</p>
 */
public final class TestObservation {

    private static final int CURRENT_VERSION = 1;

    private final int version;
    private final Instant timestamp;
    private final String testKey;
    private final String commit;
    private final String baseline;
    private final String executor;
    private final String imageTag;
    private final String buildPackage;
    private final String status;
    private final int attempts;
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
    private final long logSizeKb;
    private final boolean metricsComplete;
    private final Map<String, Object> extra;

    private TestObservation(Builder builder) {
        this.version = builder.version;
        this.timestamp = builder.timestamp;
        this.testKey = builder.testKey;
        this.commit = builder.commit;
        this.baseline = builder.baseline;
        this.executor = builder.executor;
        this.imageTag = builder.imageTag;
        this.buildPackage = builder.buildPackage;
        this.status = builder.status;
        this.attempts = builder.attempts;
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
        this.logSizeKb = builder.logSizeKb;
        this.metricsComplete = builder.metricsComplete;
        if (builder.extra == null || builder.extra.isEmpty()) {
            this.extra = Collections.emptyMap();
        } else {
            this.extra = Collections.unmodifiableMap(new LinkedHashMap<>(builder.extra));
        }
    }

    public int getVersion() {
        return version;
    }

    public Instant getTimestamp() {
        return timestamp;
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

    public String getExecutor() {
        return executor;
    }

    public String getImageTag() {
        return imageTag;
    }

    public String getBuildPackage() {
        return buildPackage;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
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

    public long getLogSizeKb() {
        return logSizeKb;
    }

    public boolean isMetricsComplete() {
        return metricsComplete;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    /**
        * Serialize as a single JSON object suitable for JSONL streams.
        */
    public String toJsonLine() {
        JSONObject json = new JSONObject();
        json.put("v", version);
        json.put("ts", DateTimeFormatter.ISO_INSTANT.format(timestamp));
        json.put("testKey", testKey);
        if (commit != null) {
            json.put("commit", commit);
        }
        if (baseline != null) {
            json.put("baseline", baseline);
        }
        if (executor != null) {
            json.put("executor", executor);
        }
        if (imageTag != null) {
            json.put("imageTag", imageTag);
        }
        if (buildPackage != null) {
            json.put("buildPackage", buildPackage);
        }
        if (status != null) {
            json.put("status", status);
        }
        json.put("attempts", attempts);
        json.put("duration_ms", durationMs);
        json.put("cpu_pct_mean", cpuPctMean);
        json.put("cpu_pct_peak", cpuPctPeak);
        json.put("mem_mb_mean", memMbMean);
        json.put("mem_mb_peak", memMbPeak);
        json.put("io_mb_s_mean", ioMbPerSecMean);
        json.put("iops_mean", iopsMean);
        json.put("net_mb_s_mean", netMbPerSecMean);
        json.put("bytes_read_mb", bytesReadMb);
        json.put("bytes_write_mb", bytesWriteMb);
        json.put("docker_image_cached", dockerImageCached);
        json.put("package_cached", packageCached);
        json.put("log_size_kb", logSizeKb);
        json.put("metrics_complete", metricsComplete);
        if (!extra.isEmpty()) {
            json.put("extra", extra);
        }
        return json.toString();
    }

    /**
     * Deserializes a TestObservation from a JSON object (for WAL replay).
     */
    public static TestObservation fromJSON(JSONObject json) {
        Builder builder = builder();

        if (json.has("v")) {
            // Version field exists but we don't use it for now
        }
        if (json.has("ts")) {
            builder.timestamp(Instant.parse(json.getString("ts")));
        }
        builder.testKey(json.getString("testKey"));

        if (json.has("commit")) {
            builder.commit(json.getString("commit"));
        }
        if (json.has("baseline")) {
            builder.baseline(json.getString("baseline"));
        }
        if (json.has("executor")) {
            builder.executor(json.getString("executor"));
        }
        if (json.has("imageTag")) {
            builder.imageTag(json.getString("imageTag"));
        }
        if (json.has("buildPackage")) {
            builder.buildPackage(json.getString("buildPackage"));
        }
        if (json.has("status")) {
            builder.status(json.getString("status"));
        }
        if (json.has("attempts")) {
            builder.attempts(json.getInt("attempts"));
        }
        if (json.has("duration_ms")) {
            builder.durationMs(json.getLong("duration_ms"));
        }
        if (json.has("cpu_pct_mean")) {
            builder.cpuPctMean(json.getDouble("cpu_pct_mean"));
        }
        if (json.has("cpu_pct_peak")) {
            builder.cpuPctPeak(json.getDouble("cpu_pct_peak"));
        }
        if (json.has("mem_mb_mean")) {
            builder.memMbMean(json.getDouble("mem_mb_mean"));
        }
        if (json.has("mem_mb_peak")) {
            builder.memMbPeak(json.getDouble("mem_mb_peak"));
        }
        if (json.has("io_mb_s_mean")) {
            builder.ioMbPerSecMean(json.getDouble("io_mb_s_mean"));
        }
        if (json.has("iops_mean")) {
            builder.iopsMean(json.getDouble("iops_mean"));
        }
        if (json.has("net_mb_s_mean")) {
            builder.netMbPerSecMean(json.getDouble("net_mb_s_mean"));
        }
        if (json.has("bytes_read_mb")) {
            builder.bytesReadMb(json.getLong("bytes_read_mb"));
        }
        if (json.has("bytes_write_mb")) {
            builder.bytesWriteMb(json.getLong("bytes_write_mb"));
        }
        if (json.has("docker_image_cached")) {
            builder.dockerImageCached(json.getBoolean("docker_image_cached"));
        }
        if (json.has("package_cached")) {
            builder.packageCached(json.getBoolean("package_cached"));
        }
        if (json.has("log_size_kb")) {
            builder.logSizeKb(json.getLong("log_size_kb"));
        }
        if (json.has("metrics_complete")) {
            builder.metricsComplete(json.getBoolean("metrics_complete"));
        }

        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int version = CURRENT_VERSION;
        private Instant timestamp = Instant.now();
        private String testKey;
        private String commit;
        private String baseline;
        private String executor;
        private String imageTag;
        private String buildPackage;
        private String status;
        private int attempts = 1;
        private long durationMs = TestExecutionMetrics.UNKNOWN_LONG;
        private double cpuPctMean = TestExecutionMetrics.UNKNOWN_DOUBLE;
        private double cpuPctPeak = TestExecutionMetrics.UNKNOWN_DOUBLE;
        private double memMbMean = TestExecutionMetrics.UNKNOWN_DOUBLE;
        private double memMbPeak = TestExecutionMetrics.UNKNOWN_DOUBLE;
        private double ioMbPerSecMean = TestExecutionMetrics.UNKNOWN_DOUBLE;
        private double iopsMean = TestExecutionMetrics.UNKNOWN_DOUBLE;
        private double netMbPerSecMean = TestExecutionMetrics.UNKNOWN_DOUBLE;
        private long bytesReadMb = TestExecutionMetrics.UNKNOWN_LONG;
        private long bytesWriteMb = TestExecutionMetrics.UNKNOWN_LONG;
        private boolean dockerImageCached;
        private boolean packageCached;
        private long logSizeKb = TestExecutionMetrics.UNKNOWN_LONG;
        private boolean metricsComplete;
        private Map<String, Object> extra;

        private Builder() { }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
            return this;
        }

        public Builder testKey(String testKey) {
            this.testKey = testKey;
            return this;
        }

        public Builder commit(String commit) {
            this.commit = commit;
            return this;
        }

        public Builder baseline(String baseline) {
            this.baseline = baseline;
            return this;
        }

        public Builder executor(String executor) {
            this.executor = executor;
            return this;
        }

        public Builder imageTag(String imageTag) {
            this.imageTag = imageTag;
            return this;
        }

        public Builder buildPackage(String buildPackage) {
            this.buildPackage = buildPackage;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder attempts(int attempts) {
            this.attempts = attempts;
            return this;
        }

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

        public Builder logSizeKb(long logSizeKb) {
            this.logSizeKb = logSizeKb;
            return this;
        }

        public Builder metricsComplete(boolean metricsComplete) {
            this.metricsComplete = metricsComplete;
            return this;
        }

        public Builder extra(String key, Object value) {
            if (extra == null) {
                extra = new LinkedHashMap<>();
            }
            extra.put(key, value);
            return this;
        }

        public TestObservation build() {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(testKey, "testKey");
            return new TestObservation(this);
        }
    }
}

