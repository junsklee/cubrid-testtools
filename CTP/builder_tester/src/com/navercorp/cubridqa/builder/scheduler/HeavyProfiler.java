package com.navercorp.cubridqa.builder.scheduler;

import org.json.JSONObject;

import java.util.*;
import java.util.logging.Logger;

/**
 * Computes {@link TestProfile} instances from historical test statistics.
 *
 * <p>The profiler analyzes test stats to classify tests into NORMAL, HEAVY, or EXTREME
 * categories based on their resource consumption ratios relative to global means.</p>
 *
 * <p>Classification algorithm:
 * <ol>
 *   <li>Compute global means for CPU, memory, I/O, and IOPS across all tests</li>
 *   <li>For each test, compute ratios: test_metric / global_mean</li>
 *   <li>Classify based on max ratio:
 *       <ul>
 *         <li>ratio &lt; 2.0 → NORMAL</li>
 *         <li>2.0 ≤ ratio &lt; 4.0 → HEAVY</li>
 *         <li>ratio ≥ 4.0 → EXTREME</li>
 *       </ul>
 *   </li>
 *   <li>Identify dominant dimension (argmax of ratios)</li>
 * </ol>
 * </p>
 */
public class HeavyProfiler {

    private static final Logger logger = Logger.getLogger(HeavyProfiler.class.getName());

    // Minimum value to avoid division by zero
    private static final double EPSILON = 0.001;

    /**
     * Global statistics computed from all test data.
     */
    public static final class GlobalStats {
        public final double meanCpuPct;
        public final double meanMemMb;
        public final double meanIoMbPerSec;
        public final double meanIops;
        public final int testCount;

        public GlobalStats(double meanCpuPct, double meanMemMb, double meanIoMbPerSec, double meanIops, int testCount) {
            this.meanCpuPct = meanCpuPct;
            this.meanMemMb = meanMemMb;
            this.meanIoMbPerSec = meanIoMbPerSec;
            this.meanIops = meanIops;
            this.testCount = testCount;
        }

        @Override
        public String toString() {
            return String.format("GlobalStats{cpu=%.2f%%, mem=%.2fMB, io=%.2fMB/s, iops=%.2f, tests=%d}",
                    meanCpuPct, meanMemMb, meanIoMbPerSec, meanIops, testCount);
        }
    }

    /**
     * Raw test stats extracted from JSON.
     */
    public static final class RawTestStats {
        public final String testKey;
        public final long durationEwmaMs;
        public final double cpuPctAvg;
        public final double memMbAvg;
        public final double ioMbPerSecAvg;
        public final double iopsAvg;
        public final int observationCount;

        public RawTestStats(String testKey, long durationEwmaMs, double cpuPctAvg, double memMbAvg,
                           double ioMbPerSecAvg, double iopsAvg, int observationCount) {
            this.testKey = testKey;
            this.durationEwmaMs = durationEwmaMs;
            this.cpuPctAvg = cpuPctAvg;
            this.memMbAvg = memMbAvg;
            this.ioMbPerSecAvg = ioMbPerSecAvg;
            this.iopsAvg = iopsAvg;
            this.observationCount = observationCount;
        }
    }

    /**
     * Computes global statistics from a collection of raw test stats.
     *
     * @param allStats collection of raw test statistics
     * @return computed global means
     */
    public GlobalStats computeGlobalStats(Collection<RawTestStats> allStats) {
        if (allStats == null || allStats.isEmpty()) {
            logger.warning("No test stats provided, returning default global stats");
            return new GlobalStats(50.0, 512.0, 10.0, 200.0, 0);
        }

        double sumCpu = 0.0, sumMem = 0.0, sumIo = 0.0, sumIops = 0.0;
        int count = 0;

        for (RawTestStats stats : allStats) {
            // Only include tests with meaningful observations
            if (stats.observationCount >= 1) {
                sumCpu += stats.cpuPctAvg;
                sumMem += stats.memMbAvg;
                sumIo += stats.ioMbPerSecAvg;
                sumIops += stats.iopsAvg;
                count++;
            }
        }

        if (count == 0) {
            logger.warning("No valid test stats found, returning default global stats");
            return new GlobalStats(50.0, 512.0, 10.0, 200.0, 0);
        }

        return new GlobalStats(
                sumCpu / count,
                sumMem / count,
                sumIo / count,
                sumIops / count,
                count
        );
    }

    /**
     * Profiles all tests in the collection.
     *
     * @param allStats collection of raw test statistics
     * @return map of testKey to TestProfile
     */
    public Map<String, TestProfile> profileAll(Collection<RawTestStats> allStats) {
        GlobalStats global = computeGlobalStats(allStats);
        return profileAll(allStats, global);
    }

    /**
     * Profiles all tests using pre-computed global statistics.
     *
     * @param allStats collection of raw test statistics
     * @param global pre-computed global statistics
     * @return map of testKey to TestProfile
     */
    public Map<String, TestProfile> profileAll(Collection<RawTestStats> allStats, GlobalStats global) {
        Map<String, TestProfile> profiles = new HashMap<>();

        if (allStats == null || allStats.isEmpty()) {
            return profiles;
        }

        int normalCount = 0, heavyCount = 0, extremeCount = 0;

        for (RawTestStats stats : allStats) {
            TestProfile profile = profileTest(stats, global);
            profiles.put(stats.testKey, profile);

            switch (profile.getHeavyClass()) {
                case NORMAL: normalCount++; break;
                case HEAVY: heavyCount++; break;
                case EXTREME: extremeCount++; break;
            }
        }

        logger.info(String.format("Profiled %d tests: %d NORMAL, %d HEAVY, %d EXTREME",
                profiles.size(), normalCount, heavyCount, extremeCount));

        return profiles;
    }

    /**
     * Profiles a single test against global statistics.
     *
     * @param stats raw test statistics
     * @param global global statistics for ratio computation
     * @return computed TestProfile
     */
    public TestProfile profileTest(RawTestStats stats, GlobalStats global) {
        // Compute ratios relative to global means
        double cpuRatio = stats.cpuPctAvg / Math.max(EPSILON, global.meanCpuPct);
        double memRatio = stats.memMbAvg / Math.max(EPSILON, global.meanMemMb);
        double ioRatio = stats.ioMbPerSecAvg / Math.max(EPSILON, global.meanIoMbPerSec);
        double iopsRatio = stats.iopsAvg / Math.max(EPSILON, global.meanIops);

        return TestProfile.builder()
                .testKey(stats.testKey)
                .cpuRatio(cpuRatio)
                .memRatio(memRatio)
                .ioRatio(ioRatio)
                .iopsRatio(iopsRatio)
                .predictedDurationMs(stats.durationEwmaMs)
                .avgCpuPct(stats.cpuPctAvg)
                .avgMemMb(stats.memMbAvg)
                .avgIoMbPerSec(stats.ioMbPerSecAvg)
                .avgIops(stats.iopsAvg)
                .build();
    }

    /**
     * Parses raw test stats from a JSON object (latest.json format).
     *
     * <p>Expected JSON structure per test:
     * <pre>
     * {
     *   "testKey": "shell/path/to/test.sh",
     *   "observation_count": 50,
     *   "duration_ms": { "ewma": 45000, "p50": 43000, "p95": 52000 },
     *   "cpu_pct": { "avg": 75.5, "p95": 120.0 },
     *   "mem_mb": { "avg": 1024, "p95": 1500 },
     *   "io_mb_s": { "avg": 25.5 },
     *   "iops": { "avg": 5000 }
     * }
     * </pre>
     * </p>
     *
     * @param testKey the test identifier
     * @param json JSON object containing test statistics
     * @return parsed RawTestStats, or null if parsing fails
     */
    public RawTestStats parseFromJson(String testKey, JSONObject json) {
        try {
            // WAL format uses "runs", others may use "observation_count" or "counts"
            int observationCount = json.optInt("runs", 
                    json.optInt("observation_count", json.optInt("counts", 0)));
            
            // Duration
            long durationEwmaMs = 0L;
            if (json.has("duration_ms")) {
                JSONObject duration = json.getJSONObject("duration_ms");
                durationEwmaMs = duration.optLong("ewma", duration.optLong("p50", 30000L));
            }

            // CPU
            double cpuPctAvg = 0.0;
            if (json.has("cpu_pct")) {
                JSONObject cpu = json.getJSONObject("cpu_pct");
                cpuPctAvg = cpu.optDouble("avg", cpu.optDouble("mean", 50.0));
            }

            // Memory
            double memMbAvg = 0.0;
            if (json.has("mem_mb")) {
                JSONObject mem = json.getJSONObject("mem_mb");
                memMbAvg = mem.optDouble("avg", mem.optDouble("mean", 512.0));
            }

            // I/O
            double ioMbPerSecAvg = 0.0;
            if (json.has("io_mb_s")) {
                JSONObject io = json.getJSONObject("io_mb_s");
                ioMbPerSecAvg = io.optDouble("avg", io.optDouble("mean", 10.0));
            }

            // IOPS
            double iopsAvg = 0.0;
            if (json.has("iops")) {
                JSONObject iops = json.getJSONObject("iops");
                iopsAvg = iops.optDouble("avg", iops.optDouble("mean", 200.0));
            }

            return new RawTestStats(testKey, durationEwmaMs, cpuPctAvg, memMbAvg, ioMbPerSecAvg, iopsAvg, observationCount);

        } catch (Exception e) {
            logger.warning("Failed to parse test stats for " + testKey + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Converts a TestProfile to JSON for persistence.
     *
     * @param profile the profile to convert
     * @return JSON representation
     */
    public JSONObject toJson(TestProfile profile) {
        JSONObject json = new JSONObject();
        json.put("testKey", profile.getTestKey());
        json.put("heavyClass", profile.getHeavyClass().name());
        json.put("dominantDim", profile.getDominantDim().name());
        json.put("cpuRatio", profile.getCpuRatio());
        json.put("memRatio", profile.getMemRatio());
        json.put("ioRatio", profile.getIoRatio());
        json.put("iopsRatio", profile.getIopsRatio());
        json.put("predictedDurationMs", profile.getPredictedDurationMs());
        json.put("avgCpuPct", profile.getAvgCpuPct());
        json.put("avgMemMb", profile.getAvgMemMb());
        json.put("avgIoMbPerSec", profile.getAvgIoMbPerSec());
        json.put("avgIops", profile.getAvgIops());
        return json;
    }

    /**
     * Parses a TestProfile from JSON.
     *
     * @param json JSON object containing profile data
     * @return parsed TestProfile
     */
    public TestProfile fromJson(JSONObject json) {
        String testKey = json.getString("testKey");
        
        // Parse enums with fallback
        TestProfile.HeavyClass heavyClass;
        try {
            heavyClass = TestProfile.HeavyClass.valueOf(json.optString("heavyClass", "NORMAL"));
        } catch (IllegalArgumentException e) {
            heavyClass = TestProfile.HeavyClass.NORMAL;
        }

        TestProfile.DominantDimension dominantDim;
        try {
            dominantDim = TestProfile.DominantDimension.valueOf(json.optString("dominantDim", "NONE"));
        } catch (IllegalArgumentException e) {
            dominantDim = TestProfile.DominantDimension.NONE;
        }

        return new TestProfile(
                testKey,
                heavyClass,
                dominantDim,
                json.optDouble("cpuRatio", 1.0),
                json.optDouble("memRatio", 1.0),
                json.optDouble("ioRatio", 1.0),
                json.optDouble("iopsRatio", 1.0),
                json.optLong("predictedDurationMs", 0L),
                json.optDouble("avgCpuPct", 0.0),
                json.optDouble("avgMemMb", 0.0),
                json.optDouble("avgIoMbPerSec", 0.0),
                json.optDouble("avgIops", 0.0)
        );
    }

    /**
     * Returns summary statistics about classified profiles.
     */
    public static String summarize(Map<String, TestProfile> profiles) {
        int normal = 0, heavy = 0, extreme = 0;
        int cpuDom = 0, memDom = 0, ioDom = 0, iopsDom = 0;

        for (TestProfile p : profiles.values()) {
            switch (p.getHeavyClass()) {
                case NORMAL: normal++; break;
                case HEAVY: heavy++; break;
                case EXTREME: extreme++; break;
            }
            switch (p.getDominantDim()) {
                case CPU: cpuDom++; break;
                case MEM: memDom++; break;
                case IO: ioDom++; break;
                case IOPS: iopsDom++; break;
                default: break;
            }
        }

        return String.format(
                "Profiles: %d total [%d NORMAL, %d HEAVY, %d EXTREME] " +
                "Dominant: [%d CPU, %d MEM, %d IO, %d IOPS]",
                profiles.size(), normal, heavy, extreme, cpuDom, memDom, ioDom, iopsDom
        );
    }
}



