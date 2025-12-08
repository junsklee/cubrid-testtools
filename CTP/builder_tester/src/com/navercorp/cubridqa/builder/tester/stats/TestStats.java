package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated statistics for a single test key, maintained in-memory and
 * periodically snapshotted to disk.
 *
 * <p>This class accumulates observations from the WAL and computes summary
 * statistics (EWMA, percentiles, averages) used by the predictor.</p>
 *
 * <p>Mutable for incremental updates but thread-safety must be ensured by
 * the caller (typically TestStatsStore).</p>
 */
public class TestStats {

    private static final double EWMA_ALPHA = 0.3; // weight for new observations
    private static final int MAX_WINDOW_SIZE = 100; // for percentile computation

    private final String testKey;
    private int observationCount;
    private Instant lastUpdated;

    // Duration stats
    private double durationEwmaMs;
    private final List<Long> durationWindow; // for P50, P95

    // Resource stats (averages)
    private double avgCpuPctMean;
    private double avgCpuPctPeak;
    private double avgMemMbMean;
    private double avgMemMbPeak;
    private double avgIoMbPerSecMean;
    private double avgIopsMean;
    private double avgNetMbPerSecMean;

    // Resource peaks (max observed across runs)
    private double maxCpuPctPeak;
    private double maxMemMbPeak;
    private double maxIoMbPerSec;
    private double maxIops;
    private double maxNetMbPerSec;

    // Resource percentiles (P95)
    private final List<Double> cpuPeakWindow;
    private final List<Double> memPeakWindow;

    // Flakiness metrics
    private int failCount;
    private int passCount;
    private int totalAttempts;

    // Cache hit ratios
    private int dockerImageCachedCount;
    private int packageCachedCount;

    public TestStats(String testKey) {
        this.testKey = Objects.requireNonNull(testKey, "testKey");
        this.observationCount = 0;
        this.lastUpdated = Instant.EPOCH;
        this.durationEwmaMs = 0.0;
        this.durationWindow = new ArrayList<>();
        this.avgCpuPctMean = 0.0;
        this.avgCpuPctPeak = 0.0;
        this.avgMemMbMean = 0.0;
        this.avgMemMbPeak = 0.0;
        this.avgIoMbPerSecMean = 0.0;
        this.avgIopsMean = 0.0;
        this.avgNetMbPerSecMean = 0.0;
        this.maxCpuPctPeak = 0.0;
        this.maxMemMbPeak = 0.0;
        this.maxIoMbPerSec = 0.0;
        this.maxIops = 0.0;
        this.maxNetMbPerSec = 0.0;
        this.cpuPeakWindow = new ArrayList<>();
        this.memPeakWindow = new ArrayList<>();
        this.failCount = 0;
        this.passCount = 0;
        this.totalAttempts = 0;
        this.dockerImageCachedCount = 0;
        this.packageCachedCount = 0;
    }

    /**
     * Updates statistics with a new observation.
     */
    public void addObservation(TestObservation obs) {
        observationCount++;
        lastUpdated = obs.getTimestamp();

        // Update duration EWMA and window
        long durMs = obs.getDurationMs();
        if (durationEwmaMs == 0.0) {
            durationEwmaMs = durMs;
        } else {
            durationEwmaMs = EWMA_ALPHA * durMs + (1.0 - EWMA_ALPHA) * durationEwmaMs;
        }
        addToWindow(durationWindow, durMs);

        // Update resource averages (incremental mean)
        avgCpuPctMean = updateAverage(avgCpuPctMean, obs.getCpuPctMean());
        avgCpuPctPeak = updateAverage(avgCpuPctPeak, obs.getCpuPctPeak());
        avgMemMbMean = updateAverage(avgMemMbMean, obs.getMemMbMean());
        avgMemMbPeak = updateAverage(avgMemMbPeak, obs.getMemMbPeak());
        avgIoMbPerSecMean = updateAverage(avgIoMbPerSecMean, obs.getIoMbPerSecMean());
        avgIopsMean = updateAverage(avgIopsMean, obs.getIopsMean());
        avgNetMbPerSecMean = updateAverage(avgNetMbPerSecMean, obs.getNetMbPerSecMean());

        // Update resource windows for percentiles
        addToWindow(cpuPeakWindow, obs.getCpuPctPeak());
        addToWindow(memPeakWindow, obs.getMemMbPeak());

        // Track absolute peaks (ignore sentinel/unknown values)
        maxCpuPctPeak = updatePeak(maxCpuPctPeak, obs.getCpuPctPeak());
        maxMemMbPeak = updatePeak(maxMemMbPeak, obs.getMemMbPeak());
        maxIoMbPerSec = updatePeak(maxIoMbPerSec, obs.getIoMbPerSecMean());
        maxIops = updatePeak(maxIops, obs.getIopsMean());
        maxNetMbPerSec = updatePeak(maxNetMbPerSec, obs.getNetMbPerSecMean());

        // Update flakiness metrics
        totalAttempts += obs.getAttempts();
        if ("pass".equalsIgnoreCase(obs.getStatus())) {
            passCount++;
        } else if ("fail".equalsIgnoreCase(obs.getStatus())) {
            failCount++;
        }

        // Update cache hit counts
        if (obs.isDockerImageCached()) {
            dockerImageCachedCount++;
        }
        if (obs.isPackageCached()) {
            packageCachedCount++;
        }
    }

    private double updateAverage(double currentAvg, double newValue) {
        if (newValue < 0.0) {
            return currentAvg; // ignore unknown/sentinel values
        }
        if (observationCount == 1) {
            return newValue;
        }
        return ((observationCount - 1) * currentAvg + newValue) / observationCount;
    }

    private double updatePeak(double currentPeak, double newValue) {
        if (newValue < 0.0) {
            return currentPeak;
        }
        return Math.max(currentPeak, newValue);
    }

    private <T extends Number> void addToWindow(List<T> window, T value) {
        window.add(value);
        if (window.size() > MAX_WINDOW_SIZE) {
            window.remove(0); // FIFO eviction
        }
    }

    // Getters

    public String getTestKey() {
        return testKey;
    }

    public int getObservationCount() {
        return observationCount;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public double getDurationEwmaMs() {
        return durationEwmaMs;
    }

    public double getDurationP50Ms() {
        return percentile(durationWindow, 50);
    }

    public double getDurationP95Ms() {
        return percentile(durationWindow, 95);
    }

    public double getAvgCpuPctMean() {
        return avgCpuPctMean;
    }

    public double getAvgCpuPctPeak() {
        return avgCpuPctPeak;
    }

    public double getCpuPctP95() {
        return percentile(cpuPeakWindow, 95);
    }

    public double getAvgMemMbMean() {
        return avgMemMbMean;
    }

    public double getAvgMemMbPeak() {
        return avgMemMbPeak;
    }

    public double getMemMbP95() {
        return percentile(memPeakWindow, 95);
    }

    public double getAvgIoMbPerSecMean() {
        return avgIoMbPerSecMean;
    }

    public double getAvgIopsMean() {
        return avgIopsMean;
    }

    public double getAvgNetMbPerSecMean() {
        return avgNetMbPerSecMean;
    }

    public double getMaxCpuPctPeak() {
        return maxCpuPctPeak;
    }

    public double getMaxMemMbPeak() {
        return maxMemMbPeak;
    }

    public double getMaxIoMbPerSec() {
        return maxIoMbPerSec;
    }

    public double getMaxIops() {
        return maxIops;
    }

    public double getMaxNetMbPerSec() {
        return maxNetMbPerSec;
    }

    public double getFailRate() {
        int total = passCount + failCount;
        return total == 0 ? 0.0 : (double) failCount / total;
    }

    public double getRetryMean() {
        return observationCount == 0 ? 0.0 : (double) totalAttempts / observationCount;
    }

    public double getDockerImageCacheHitRatio() {
        return observationCount == 0 ? 0.0 : (double) dockerImageCachedCount / observationCount;
    }

    public double getPackageCacheHitRatio() {
        return observationCount == 0 ? 0.0 : (double) packageCachedCount / observationCount;
    }

    /**
     * Computes the given percentile from a window of values.
     * Returns 0.0 if window is empty.
     */
    private <T extends Number> double percentile(List<T> window, int p) {
        if (window.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>();
        for (T val : window) {
            if (val.doubleValue() >= 0.0) {
                sorted.add(val.doubleValue());
            }
        }
        if (sorted.isEmpty()) {
            return 0.0;
        }
        Collections.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /**
     * Serializes to JSON for snapshot persistence.
     */
    public JSONObject toJSON() {
        JSONObject obj = new JSONObject();
        obj.put("testKey", testKey);
        obj.put("observationCount", observationCount);
        obj.put("lastUpdated", lastUpdated.toString());
        obj.put("durationEwmaMs", durationEwmaMs);
        obj.put("avgCpuPctMean", avgCpuPctMean);
        obj.put("avgCpuPctPeak", avgCpuPctPeak);
        obj.put("avgMemMbMean", avgMemMbMean);
        obj.put("avgMemMbPeak", avgMemMbPeak);
        obj.put("avgIoMbPerSecMean", avgIoMbPerSecMean);
        obj.put("avgIopsMean", avgIopsMean);
        obj.put("avgNetMbPerSecMean", avgNetMbPerSecMean);
        obj.put("maxCpuPctPeak", maxCpuPctPeak);
        obj.put("maxMemMbPeak", maxMemMbPeak);
        obj.put("maxIoMbPerSec", maxIoMbPerSec);
        obj.put("maxIops", maxIops);
        obj.put("maxNetMbPerSec", maxNetMbPerSec);
        obj.put("failCount", failCount);
        obj.put("passCount", passCount);
        obj.put("totalAttempts", totalAttempts);
        obj.put("dockerImageCachedCount", dockerImageCachedCount);
        obj.put("packageCachedCount", packageCachedCount);
        return obj;
    }

    /**
     * Reconstructs from JSON snapshot.
     */
    public static TestStats fromJSON(JSONObject obj) {
        String testKey = obj.getString("testKey");
        TestStats stats = new TestStats(testKey);
        stats.observationCount = obj.getInt("observationCount");
        stats.lastUpdated = Instant.parse(obj.getString("lastUpdated"));
        stats.durationEwmaMs = obj.getDouble("durationEwmaMs");
        stats.avgCpuPctMean = obj.getDouble("avgCpuPctMean");
        stats.avgCpuPctPeak = obj.getDouble("avgCpuPctPeak");
        stats.avgMemMbMean = obj.getDouble("avgMemMbMean");
        stats.avgMemMbPeak = obj.getDouble("avgMemMbPeak");
        stats.avgIoMbPerSecMean = obj.getDouble("avgIoMbPerSecMean");
        stats.avgIopsMean = obj.getDouble("avgIopsMean");
        stats.avgNetMbPerSecMean = obj.getDouble("avgNetMbPerSecMean");
        stats.maxCpuPctPeak = obj.optDouble("maxCpuPctPeak", 0.0);
        stats.maxMemMbPeak = obj.optDouble("maxMemMbPeak", 0.0);
        stats.maxIoMbPerSec = obj.optDouble("maxIoMbPerSec", 0.0);
        stats.maxIops = obj.optDouble("maxIops", 0.0);
        stats.maxNetMbPerSec = obj.optDouble("maxNetMbPerSec", 0.0);
        stats.failCount = obj.getInt("failCount");
        stats.passCount = obj.getInt("passCount");
        stats.totalAttempts = obj.getInt("totalAttempts");
        stats.dockerImageCachedCount = obj.getInt("dockerImageCachedCount");
        stats.packageCachedCount = obj.getInt("packageCachedCount");
        stats.backfillPeaksIfMissing();
        return stats;
    }

    /**
     * Serializes to JSON for latest.json.gz export.
     *
     * <p>This format is designed for importing test statistics from another node,
     * providing a human-readable and importable snapshot of test performance data.
     */
    public JSONObject toLatestJSON() {
        JSONObject obj = new JSONObject();
        obj.put("last_obs_ts", lastUpdated.toString());
        obj.put("runs", observationCount);

        // Duration stats
        JSONObject duration = new JSONObject();
        duration.put("p50", getDurationP50Ms());
        duration.put("p95", getDurationP95Ms());
        duration.put("ewma", durationEwmaMs);
        obj.put("duration_ms", duration);

        // CPU stats
        JSONObject cpu = new JSONObject();
        cpu.put("avg", avgCpuPctMean);
        cpu.put("p95", getCpuPctP95());
        cpu.put("peak", bestPeak(maxCpuPctPeak, getCpuPctP95(), avgCpuPctMean));
        obj.put("cpu_pct", cpu);

        // Memory stats
        JSONObject mem = new JSONObject();
        mem.put("avg", avgMemMbMean);
        mem.put("p95", getMemMbP95());
        mem.put("peak", bestPeak(maxMemMbPeak, getMemMbP95(), avgMemMbMean));
        obj.put("mem_mb", mem);

        // I/O stats
        JSONObject io = new JSONObject();
        io.put("avg", avgIoMbPerSecMean);
        io.put("peak", bestPeak(maxIoMbPerSec, avgIoMbPerSecMean, 0.0));
        obj.put("io_mb_s", io);

        // IOPS stats
        JSONObject iops = new JSONObject();
        iops.put("avg", avgIopsMean);
        iops.put("peak", bestPeak(maxIops, avgIopsMean, 0.0));
        obj.put("iops", iops);

        // Network stats
        JSONObject net = new JSONObject();
        net.put("avg", avgNetMbPerSecMean);
        net.put("peak", bestPeak(maxNetMbPerSec, avgNetMbPerSecMean, 0.0));
        obj.put("net_mb_s", net);

        // Flakiness stats
        JSONObject flaky = new JSONObject();
        flaky.put("fail_rate", getFailRate());
        flaky.put("retry_mean", getRetryMean());
        obj.put("flakiness", flaky);

        return obj;
    }

    /**
     * Reconstructs from latest.json.gz format for importing test statistics.
     */
    public static TestStats fromLatestJSON(String testKey, JSONObject obj) {
        TestStats stats = new TestStats(testKey);

        stats.lastUpdated = Instant.parse(obj.getString("last_obs_ts"));
        stats.observationCount = obj.getInt("runs");

        // Duration stats
        if (obj.has("duration_ms")) {
            JSONObject duration = obj.getJSONObject("duration_ms");
            stats.durationEwmaMs = duration.optDouble("ewma", 0.0);
        }

        // CPU stats
        if (obj.has("cpu_pct")) {
            JSONObject cpu = obj.getJSONObject("cpu_pct");
            stats.avgCpuPctMean = cpu.optDouble("avg", 0.0);
            stats.maxCpuPctPeak = cpu.optDouble("peak", 0.0);
        }

        // Memory stats
        if (obj.has("mem_mb")) {
            JSONObject mem = obj.getJSONObject("mem_mb");
            stats.avgMemMbMean = mem.optDouble("avg", 0.0);
            stats.maxMemMbPeak = mem.optDouble("peak", 0.0);
        }

        // I/O stats
        if (obj.has("io_mb_s")) {
            JSONObject io = obj.getJSONObject("io_mb_s");
            stats.avgIoMbPerSecMean = io.optDouble("avg", 0.0);
            stats.maxIoMbPerSec = io.optDouble("peak", 0.0);
        }

        // IOPS stats
        if (obj.has("iops")) {
            JSONObject iops = obj.getJSONObject("iops");
            stats.avgIopsMean = iops.optDouble("avg", 0.0);
            stats.maxIops = iops.optDouble("peak", 0.0);
        }

        // Network stats
        if (obj.has("net_mb_s")) {
            JSONObject net = obj.getJSONObject("net_mb_s");
            stats.avgNetMbPerSecMean = net.optDouble("avg", 0.0);
            stats.maxNetMbPerSec = net.optDouble("peak", 0.0);
        }

        // Flakiness stats - reconstruct from ratios
        if (obj.has("flakiness")) {
            JSONObject flaky = obj.getJSONObject("flakiness");
            double failRate = Math.max(0.0, Math.min(1.0, flaky.optDouble("fail_rate", 0.0)));
            double retryMean = Math.max(0.0, flaky.optDouble("retry_mean", 0.0));

            // Estimate counts based on fail rate and observation count
            stats.failCount = (int) Math.round(stats.observationCount * failRate);
            stats.passCount = stats.observationCount - stats.failCount;
            stats.totalAttempts = Math.max(0, (int) Math.round(stats.observationCount * retryMean));
        }

        stats.backfillPeaksIfMissing();
        return stats;
    }

    private void backfillPeaksIfMissing() {
        if (maxCpuPctPeak <= 0.0) {
            maxCpuPctPeak = bestPeak(maxCpuPctPeak, getCpuPctP95(), Math.max(avgCpuPctPeak, avgCpuPctMean));
        }
        if (maxMemMbPeak <= 0.0) {
            maxMemMbPeak = bestPeak(maxMemMbPeak, getMemMbP95(), Math.max(avgMemMbPeak, avgMemMbMean));
        }
        if (maxIoMbPerSec <= 0.0) {
            maxIoMbPerSec = bestPeak(maxIoMbPerSec, avgIoMbPerSecMean, 0.0);
        }
        if (maxIops <= 0.0) {
            maxIops = bestPeak(maxIops, avgIopsMean, 0.0);
        }
        if (maxNetMbPerSec <= 0.0) {
            maxNetMbPerSec = bestPeak(maxNetMbPerSec, avgNetMbPerSecMean, 0.0);
        }
    }

    private double bestPeak(double primary, double secondary, double fallback) {
        double candidate = primary;
        if (candidate <= 0.0) {
            candidate = secondary > 0.0 ? secondary : fallback;
        }
        return Math.max(0.0, candidate);
    }
}
