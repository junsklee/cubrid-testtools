package com.navercorp.cubridqa.builder.scheduler;

/**
 * Multi-resource bin-packing score function for test placement.
 *
 * <p>Implements a weighted scoring formula that considers:
 * <ul>
 *   <li>Resource pressure (dominant resource fairness)</li>
 *   <li>Test duration (relative to reference)</li>
 *   <li>Cache locality (image and package presence)</li>
 *   <li>Queue aging (fairness boost for waiting tests)</li>
 * </ul>
 * </p>
 *
 * <p>Lower scores are better. The scheduler picks the node with minimum score.</p>
 */
public class ScoreFunction {

    // Scoring weights (configurable)
    private final double w1; // Resource pressure weight
    private final double w2; // Duration weight
    private final double w3; // Image cache penalty weight
    private final double w4; // Package cache penalty weight
    private final double w5; // Age boost weight (negative)

    // Reference values for normalization
    private static final long T_REF_MS = 30_000L;  // 30 seconds reference duration
    private static final double EPSILON = 1.0;      // Small epsilon to avoid division by zero

    /**
     * Creates a ScoreFunction with default weights.
     */
    public ScoreFunction() {
        this(0.45, 0.25, 0.15, 0.05, 0.10);
    }

    /**
     * Creates a ScoreFunction with custom weights.
     *
     * @param w1 Resource pressure weight
     * @param w2 Duration weight
     * @param w3 Image cache penalty weight
     * @param w4 Package cache penalty weight
     * @param w5 Age boost weight (applied as negative)
     */
    public ScoreFunction(double w1, double w2, double w3, double w4, double w5) {
        this.w1 = w1;
        this.w2 = w2;
        this.w3 = w3;
        this.w4 = w4;
        this.w5 = w5;
    }

    /**
     * Computes the placement score for a test on a node.
     *
     * @param test The test instance to place
     * @param node The candidate node snapshot
     * @return Score (lower is better)
     */
    public double score(TestInstance test, NodeSnapshot node) {
        // 1. Resource pressure (dominant resource, bin-packing)
        double pressure = computePressure(test, node);

        // 2. Duration score (normalized)
        double durScore = (double) test.getPredictedDurationMs() / T_REF_MS;

        // 3. Cache penalties (0 if cached, 1 if not)
        double imagePenalty = computeImagePenalty(test, node);
        double pkgPenalty = computePackagePenalty(test, node);

        // 4. Age boost (fairness for long-waiting tests)
        double ageBoost = computeAgeBoost(test);

        // Combined score
        return w1 * pressure + w2 * durScore + w3 * imagePenalty + w4 * pkgPenalty - w5 * ageBoost;
    }

    /**
     * Computes resource pressure using dominant resource fairness.
     * Returns max(q_d / f_d) across all resource dimensions.
     */
    private double computePressure(TestInstance test, NodeSnapshot node) {
        double cpuPressure = test.getPredictedCpuPct() / Math.max(EPSILON, node.getFreeCpuPct());
        double memPressure = test.getPredictedMemMb() / Math.max(EPSILON, node.getFreeMemMb());
        double ioPressure = test.getPredictedIoMbPerSec() / Math.max(EPSILON, node.getFreeIoMbPerSec());
        double iopsPressure = test.getPredictedIops() / Math.max(EPSILON, node.getFreeIops());
        double netPressure = test.getPredictedNetMbPerSec() / Math.max(EPSILON, node.getFreeNetMbPerSec());

        return Math.max(Math.max(Math.max(Math.max(cpuPressure, memPressure), ioPressure), iopsPressure), netPressure);
    }

    /**
     * Returns 0 if the required Docker image is cached on the node, 1 otherwise.
     */
    private double computeImagePenalty(TestInstance test, NodeSnapshot node) {
        if (test.getImageTag() == null || test.getImageTag().isEmpty()) {
            return 0.0;  // No image required (direct executor)
        }
        return node.getCachedImages().contains(test.getImageTag()) ? 0.0 : 1.0;
    }

    /**
     * Returns 0 if the required build package is cached on the node, 1 otherwise.
     */
    private double computePackagePenalty(TestInstance test, NodeSnapshot node) {
        if (test.getBuildPackage() == null || test.getBuildPackage().isEmpty()) {
            return 0.0;  // No package info available
        }
        return node.getCachedPackages().contains(test.getBuildPackage()) ? 0.0 : 1.0;
    }

    /**
     * Computes age boost based on queue wait time.
     * Returns a value in [0, 1] that increases with wait time.
     */
    private double computeAgeBoost(TestInstance test) {
        long waitSeconds = test.getWaitTimeSeconds();
        double ageCap = 300.0;  // 5 minutes cap
        return Math.min(1.0, waitSeconds / ageCap);
    }

    /**
     * Returns a human-readable explanation of the score breakdown.
     */
    public String explainScore(TestInstance test, NodeSnapshot node, double totalScore) {
        double pressure = computePressure(test, node);
        double durScore = (double) test.getPredictedDurationMs() / T_REF_MS;
        double imagePenalty = computeImagePenalty(test, node);
        double pkgPenalty = computePackagePenalty(test, node);
        double ageBoost = computeAgeBoost(test);

        return String.format(
            "Score=%.3f [pressure=%.3f(%.2f), dur=%.3f(%.2f), img=%.1f(%.2f), pkg=%.1f(%.2f), age=%.3f(-%.2f)]",
            totalScore,
            pressure, w1 * pressure,
            durScore, w2 * durScore,
            imagePenalty, w3 * imagePenalty,
            pkgPenalty, w4 * pkgPenalty,
            ageBoost, w5 * ageBoost
        );
    }
}
