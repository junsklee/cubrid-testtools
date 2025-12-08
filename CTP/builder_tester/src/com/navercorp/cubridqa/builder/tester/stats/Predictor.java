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

    // Default peak blending weights (max fraction of gap between mean and observed peak)
    private static final double DEFAULT_PEAK_BLEND_CPU_MAX = 0.40;
    private static final double DEFAULT_PEAK_BLEND_MEM_MAX = 0.60;
    private static final double DEFAULT_PEAK_BLEND_IO_MAX = 0.50;
    private static final double DEFAULT_PEAK_BLEND_IOPS_MAX = 0.50;
    private static final double DEFAULT_PEAK_BLEND_NET_MAX = 0.40;

    private final double peakBlendCpuMax;
    private final double peakBlendMemMax;
    private final double peakBlendIoMax;
    private final double peakBlendIopsMax;
    private final double peakBlendNetMax;

    public Predictor() {
        this(null);
    }

    public Predictor(com.navercorp.cubridqa.builder.BuilderConfig config) {
        this.peakBlendCpuMax = config != null ? config.getPredictorPeakBlendCpuMax() : DEFAULT_PEAK_BLEND_CPU_MAX;
        this.peakBlendMemMax = config != null ? config.getPredictorPeakBlendMemMax() : DEFAULT_PEAK_BLEND_MEM_MAX;
        this.peakBlendIoMax = config != null ? config.getPredictorPeakBlendIoMax() : DEFAULT_PEAK_BLEND_IO_MAX;
        this.peakBlendIopsMax = config != null ? config.getPredictorPeakBlendIopsMax() : DEFAULT_PEAK_BLEND_IOPS_MAX;
        this.peakBlendNetMax = config != null ? config.getPredictorPeakBlendNetMax() : DEFAULT_PEAK_BLEND_NET_MAX;
    }

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

        // Use averages for resource demands, then blend toward peaks to avoid underestimating bursts
        double cpuPctBase = stats.getAvgCpuPctMean() > 0.0 ? stats.getAvgCpuPctMean() : DEFAULT_CPU_PCT;
        double memMbBase = stats.getAvgMemMbMean() > 0.0 ? stats.getAvgMemMbMean() : DEFAULT_MEM_MB;
        double ioMbPerSecBase = stats.getAvgIoMbPerSecMean() > 0.0 ? stats.getAvgIoMbPerSecMean() : DEFAULT_IO_MB_PER_SEC;
        double iopsBase = stats.getAvgIopsMean() > 0.0 ? stats.getAvgIopsMean() : DEFAULT_IOPS;
        double netMbPerSecBase = stats.getAvgNetMbPerSecMean() > 0.0 ? stats.getAvgNetMbPerSecMean() : DEFAULT_NET_MB_PER_SEC;

        double cpuPct = blendWithPeak(cpuPctBase, stats.getMaxCpuPctPeak(), confidence, peakBlendCpuMax);
        double memMb = blendWithPeak(memMbBase, stats.getMaxMemMbPeak(), confidence, peakBlendMemMax);
        double ioMbPerSec = blendWithPeak(ioMbPerSecBase, stats.getMaxIoMbPerSec(), confidence, peakBlendIoMax);
        double iops = blendWithPeak(iopsBase, stats.getMaxIops(), confidence, peakBlendIopsMax);
        double netMbPerSec = blendWithPeak(netMbPerSecBase, stats.getMaxNetMbPerSec(), confidence, peakBlendNetMax);

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

    private double blendWithPeak(double mean, double peak, double confidence, double maxBlend) {
        if (peak <= 0.0 || peak <= mean) {
            return mean;
        }
        double weight = Math.max(0.0, Math.min(maxBlend, (1.0 - confidence) * maxBlend));
        return mean + (peak - mean) * weight;
    }
}
