package com.navercorp.cubridqa.builder.tester.stats;

import java.util.Objects;

/**
 * Stateless predictor that generates resource demand predictions from test statistics.
 *
 * <p>The predictor uses historical test statistics (EWMA, percentiles, averages) to
 * predict future resource demands, scaling them to the target node's hardware capacity.
 * For unseen tests, it falls back to configurable global defaults.</p>
 *
 * <p>Confidence is computed as a monotonically increasing function of observation count,
 * and low-confidence predictions are inflated by a safety margin.</p>
 */
public class Predictor {

    // Global defaults for unseen tests
    private static final long DEFAULT_DURATION_MS = 30_000L; // 30 seconds
    private static final double DEFAULT_CPU_PCT = 50.0;
    private static final double DEFAULT_MEM_MB = 512.0;
    private static final double DEFAULT_IO_MB_PER_SEC = 10.0;
    private static final double DEFAULT_IOPS = 200.0;
    private static final double DEFAULT_NET_MB_PER_SEC = 5.0;

    // Confidence parameters
    private static final double CONFIDENCE_SATURATION = 20.0; // observations to reach ~95% confidence
    private static final double SAFETY_MARGIN = 0.25; // 25% inflation for low confidence

    /**
     * Predicts resource demand for a test.
     *
     * @param testKey The test identifier
     * @param stats   Historical statistics for the test, or null if unseen
     * @param context Build context (commit, baseline) - currently unused but reserved for future use
     * @param hw      Target node hardware capacity
     * @return Predicted demand with confidence
     */
    public PredictedDemand predict(String testKey, TestStats stats, BuildContext context, NodeHardware hw) {
        Objects.requireNonNull(testKey, "testKey");
        Objects.requireNonNull(hw, "hw");

        if (stats == null || stats.getObservationCount() == 0) {
            return bootstrapPrediction(hw);
        }

        // Compute confidence based on observation count
        double confidence = computeConfidence(stats.getObservationCount());

        // Use EWMA for duration, with P50 as fallback
        long tpredMs;
        if (stats.getDurationEwmaMs() > 0.0) {
            tpredMs = Math.round(stats.getDurationEwmaMs());
        } else {
            double p50 = stats.getDurationP50Ms();
            tpredMs = p50 > 0.0 ? Math.round(p50) : DEFAULT_DURATION_MS;
        }

        // Use averages for resource demands (could use P95 for conservative estimates)
        double cpuPct = stats.getAvgCpuPctMean() > 0.0 ? stats.getAvgCpuPctMean() : DEFAULT_CPU_PCT;
        double memMb = stats.getAvgMemMbMean() > 0.0 ? stats.getAvgMemMbMean() : DEFAULT_MEM_MB;
        double ioMbPerSec = stats.getAvgIoMbPerSecMean() > 0.0 ? stats.getAvgIoMbPerSecMean() : DEFAULT_IO_MB_PER_SEC;
        double iops = stats.getAvgIopsMean() > 0.0 ? stats.getAvgIopsMean() : DEFAULT_IOPS;
        double netMbPerSec = stats.getAvgNetMbPerSecMean() > 0.0 ? stats.getAvgNetMbPerSecMean() : DEFAULT_NET_MB_PER_SEC;

        // Scale resources to target hardware (currently no-op, assumes homogeneous cluster)
        // Future: scale CPU% based on hw.getCpuPct() relative to reference hardware

        // Apply safety margin if confidence is low
        if (confidence < 0.8) {
            double margin = 1.0 + SAFETY_MARGIN * (1.0 - confidence);
            tpredMs = Math.round(tpredMs * margin);
            cpuPct *= margin;
            memMb *= margin;
            ioMbPerSec *= margin;
            iops *= margin;
            netMbPerSec *= margin;
        }

        return PredictedDemand.builder()
                .tpredMs(tpredMs)
                .cpuPct(cpuPct)
                .memMb(memMb)
                .ioMbPerSec(ioMbPerSec)
                .iops(iops)
                .netMbPerSec(netMbPerSec)
                .confidence(confidence)
                .build();
    }

    /**
     * Computes confidence as a function of observation count.
     * Returns a value in [0, 1] that increases monotonically with count.
     *
     * Uses formula: 1 - exp(-count / saturation)
     * - At count=0: confidence=0
     * - At count=saturation: confidence≈0.63
     * - At count=3*saturation: confidence≈0.95
     */
    private double computeConfidence(int count) {
        if (count <= 0) {
            return 0.0;
        }
        return 1.0 - Math.exp(-count / CONFIDENCE_SATURATION);
    }

    /**
     * Returns a bootstrap prediction for unseen tests using global defaults.
     */
    private PredictedDemand bootstrapPrediction(NodeHardware hw) {
        return PredictedDemand.builder()
                .tpredMs(DEFAULT_DURATION_MS)
                .cpuPct(DEFAULT_CPU_PCT)
                .memMb(DEFAULT_MEM_MB)
                .ioMbPerSec(DEFAULT_IO_MB_PER_SEC)
                .iops(DEFAULT_IOPS)
                .netMbPerSec(DEFAULT_NET_MB_PER_SEC)
                .confidence(0.0)
                .build();
    }
}
