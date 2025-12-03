package com.navercorp.cubridqa.builder.scheduler;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Computes the elephant queue threshold based on test duration statistics.
 *
 * <p>The elephant threshold determines which tests go into the "elephant" queue
 * (scheduled with longest-job-first) versus the "mice" queue (shortest-job-first).
 * Using a percentile-based threshold adapts to the actual test suite distribution.</p>
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Collect predicted durations from all known test profiles</li>
 *   <li>Compute P75 (75th percentile) of durations</li>
 *   <li>Apply minimum floor (default 60s) to ensure reasonable threshold</li>
 *   <li>Result: {@code max(P75(durations), minFloorMs)}</li>
 * </ol>
 * </p>
 *
 * <p>This approach ensures:
 * <ul>
 *   <li>~25% of tests become "elephants" (longest tests)</li>
 *   <li>Threshold adapts as test suite evolves</li>
 *   <li>Minimum floor prevents too many elephants if suite is mostly fast tests</li>
 * </ul>
 * </p>
 */
public class ElephantThresholdCalculator {

    private static final Logger logger = Logger.getLogger(ElephantThresholdCalculator.class.getName());

    /** Default minimum threshold (60 seconds) */
    public static final long DEFAULT_MIN_THRESHOLD_MS = 60_000L;

    /** Default percentile to use for threshold computation */
    public static final double DEFAULT_PERCENTILE = 0.75;

    /** Fallback threshold when no data is available */
    public static final long FALLBACK_THRESHOLD_MS = 60_000L;

    /**
     * Computes the elephant threshold from test profiles.
     *
     * @param profiles collection of test profiles
     * @param minThresholdMs minimum threshold floor
     * @return computed threshold in milliseconds
     */
    public static long computeFromProfiles(Collection<TestProfile> profiles, long minThresholdMs) {
        if (profiles == null || profiles.isEmpty()) {
            logger.warning("No profiles provided, using fallback threshold: " + FALLBACK_THRESHOLD_MS + "ms");
            return Math.max(FALLBACK_THRESHOLD_MS, minThresholdMs);
        }

        // Extract durations from profiles
        List<Long> durations = profiles.stream()
                .map(TestProfile::getPredictedDurationMs)
                .filter(d -> d > 0)  // Filter out zeros/unknowns
                .sorted()
                .collect(Collectors.toList());

        if (durations.isEmpty()) {
            logger.warning("No valid durations in profiles, using fallback threshold: " + FALLBACK_THRESHOLD_MS + "ms");
            return Math.max(FALLBACK_THRESHOLD_MS, minThresholdMs);
        }

        long p75 = computePercentile(durations, DEFAULT_PERCENTILE);
        long threshold = Math.max(p75, minThresholdMs);

        logger.info(String.format("Elephant threshold computed: P75=%dms, min=%dms, final=%dms (from %d tests)",
                p75, minThresholdMs, threshold, durations.size()));

        return threshold;
    }

    /**
     * Computes the elephant threshold from raw duration values.
     *
     * @param durations collection of duration values in milliseconds
     * @param minThresholdMs minimum threshold floor
     * @return computed threshold in milliseconds
     */
    public static long computeFromDurations(Collection<Long> durations, long minThresholdMs) {
        if (durations == null || durations.isEmpty()) {
            logger.warning("No durations provided, using fallback threshold: " + FALLBACK_THRESHOLD_MS + "ms");
            return Math.max(FALLBACK_THRESHOLD_MS, minThresholdMs);
        }

        List<Long> sorted = durations.stream()
                .filter(d -> d != null && d > 0)
                .sorted()
                .collect(Collectors.toList());

        if (sorted.isEmpty()) {
            return Math.max(FALLBACK_THRESHOLD_MS, minThresholdMs);
        }

        long p75 = computePercentile(sorted, DEFAULT_PERCENTILE);
        return Math.max(p75, minThresholdMs);
    }

    /**
     * Computes the elephant threshold with default minimum.
     *
     * @param profiles collection of test profiles
     * @return computed threshold in milliseconds
     */
    public static long computeFromProfiles(Collection<TestProfile> profiles) {
        return computeFromProfiles(profiles, DEFAULT_MIN_THRESHOLD_MS);
    }

    /**
     * Computes a percentile from a sorted list of values.
     *
     * @param sortedValues sorted list of values
     * @param percentile percentile to compute (0.0 to 1.0)
     * @return the percentile value
     */
    public static long computePercentile(List<Long> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0L;
        }

        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }

        double index = percentile * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper) {
            return sortedValues.get(lower);
        }

        // Linear interpolation between adjacent values
        double fraction = index - lower;
        long lowerVal = sortedValues.get(lower);
        long upperVal = sortedValues.get(upper);

        return Math.round(lowerVal + fraction * (upperVal - lowerVal));
    }

    /**
     * Returns statistics about the duration distribution.
     */
    public static DurationStats computeStats(Collection<TestProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return new DurationStats(0, 0, 0, 0, 0, 0, 0);
        }

        List<Long> durations = profiles.stream()
                .map(TestProfile::getPredictedDurationMs)
                .filter(d -> d > 0)
                .sorted()
                .collect(Collectors.toList());

        if (durations.isEmpty()) {
            return new DurationStats(0, 0, 0, 0, 0, 0, 0);
        }

        long min = durations.get(0);
        long max = durations.get(durations.size() - 1);
        long p25 = computePercentile(durations, 0.25);
        long p50 = computePercentile(durations, 0.50);
        long p75 = computePercentile(durations, 0.75);
        long p95 = computePercentile(durations, 0.95);
        double mean = durations.stream().mapToLong(Long::longValue).average().orElse(0.0);

        return new DurationStats(durations.size(), min, max, p25, p50, p75, p95, mean);
    }

    /**
     * Summary statistics for duration distribution.
     */
    public static final class DurationStats {
        public final int count;
        public final long minMs;
        public final long maxMs;
        public final long p25Ms;
        public final long p50Ms;
        public final long p75Ms;
        public final long p95Ms;
        public final double meanMs;

        public DurationStats(int count, long minMs, long maxMs, long p25Ms, long p50Ms, long p75Ms, long p95Ms) {
            this(count, minMs, maxMs, p25Ms, p50Ms, p75Ms, p95Ms, 0.0);
        }

        public DurationStats(int count, long minMs, long maxMs, long p25Ms, long p50Ms, long p75Ms, long p95Ms, double meanMs) {
            this.count = count;
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.p25Ms = p25Ms;
            this.p50Ms = p50Ms;
            this.p75Ms = p75Ms;
            this.p95Ms = p95Ms;
            this.meanMs = meanMs;
        }

        /**
         * Returns the count of tests that would be elephants at a given threshold.
         */
        public int countElephantsAt(long thresholdMs, Collection<TestProfile> profiles) {
            if (profiles == null) return 0;
            return (int) profiles.stream()
                    .filter(p -> p.getPredictedDurationMs() > thresholdMs)
                    .count();
        }

        @Override
        public String toString() {
            return String.format("DurationStats{count=%d, min=%dms, max=%dms, p25=%dms, p50=%dms, p75=%dms, p95=%dms, mean=%.0fms}",
                    count, minMs, maxMs, p25Ms, p50Ms, p75Ms, p95Ms, meanMs);
        }
    }

    /**
     * Utility to suggest an appropriate threshold based on desired elephant count.
     *
     * @param profiles test profiles
     * @param targetElephantCount desired number of elephants
     * @param minThresholdMs minimum threshold floor
     * @return suggested threshold
     */
    public static long suggestThreshold(Collection<TestProfile> profiles, int targetElephantCount, long minThresholdMs) {
        if (profiles == null || profiles.isEmpty() || targetElephantCount <= 0) {
            return minThresholdMs;
        }

        List<Long> sortedDurations = profiles.stream()
                .map(TestProfile::getPredictedDurationMs)
                .filter(d -> d > 0)
                .sorted(Comparator.reverseOrder())  // Descending
                .collect(Collectors.toList());

        if (sortedDurations.isEmpty()) {
            return minThresholdMs;
        }

        // Find the duration at position targetElephantCount
        int index = Math.min(targetElephantCount - 1, sortedDurations.size() - 1);
        long suggestedThreshold = sortedDurations.get(index);

        return Math.max(suggestedThreshold, minThresholdMs);
    }
}



