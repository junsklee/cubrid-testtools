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
 *   <li>Heavy test penalty (avoids placing heavy tests on constrained nodes)</li>
 * </ul>
 * </p>
 *
 * <p>Lower scores are better. The scheduler picks the node with minimum score.</p>
 */
public class ScoreFunction {

    // Scoring weights (configurable)
    private final double w1; // Resource pressure weight (overall)
    private final double w2; // Duration weight
    private final double w3; // Image cache penalty weight
    private final double w4; // Package cache penalty weight
    private final double w5; // Age boost weight (negative)
    private final double w6; // Heavy test penalty weight
    
    // Per-dimension weights for pressure computation (IO-first)
    private final double wIO;  // IO weight multiplier (default 2.50)
    private final double wCPU; // CPU weight (default 1.00)
    private final double wMEM; // Memory weight (default 1.10)
    private final double wNET; // Network weight (default 0.80)

    // Reference values for normalization
    private static final long T_REF_MS = 30_000L;  // 30 seconds reference duration
    private static final double EPSILON = 1.0;      // Small epsilon to avoid division by zero
    private final NodeLoadProvider nodeLoadProvider;

    /**
     * Creates a ScoreFunction with default weights.
     */
    public ScoreFunction() {
        this(0.45, 0.25, 0.15, 0.05, 0.10, 0.10, NodeLoadProvider.NOOP);
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
        this(w1, w2, w3, w4, w5, 0.10, NodeLoadProvider.NOOP);
    }

    /**
     * Creates a ScoreFunction with custom weights and a load provider to account for builder-side
     * in-flight assignments.
     */
    public ScoreFunction(double w1, double w2, double w3, double w4, double w5, NodeLoadProvider loadProvider) {
        this(w1, w2, w3, w4, w5, 0.10, loadProvider);
    }

    /**
     * Creates a ScoreFunction with custom weights including heavy penalty weight.
     *
     * @param w1 Resource pressure weight
     * @param w2 Duration weight
     * @param w3 Image cache penalty weight
     * @param w4 Package cache penalty weight
     * @param w5 Age boost weight (applied as negative)
     * @param w6 Heavy test penalty weight
     * @param loadProvider Provider for in-flight load penalties
     */
    public ScoreFunction(double w1, double w2, double w3, double w4, double w5, double w6,
                        NodeLoadProvider loadProvider) {
        this(w1, w2, w3, w4, w5, w6, 2.50, 1.00, 1.10, 0.80, loadProvider);
    }

    /**
     * Creates a ScoreFunction with custom weights including per-dimension IO/CPU/MEM/NET weights.
     */
    public ScoreFunction(double w1, double w2, double w3, double w4, double w5,
                        double wIO, double wCPU, double wMEM, double wNET,
                        NodeLoadProvider loadProvider) {
        this(w1, w2, w3, w4, w5, 0.10, wIO, wCPU, wMEM, wNET, loadProvider);
    }

    /**
     * Creates a ScoreFunction with all configurable weights.
     *
     * @param w1 Resource pressure weight
     * @param w2 Duration weight
     * @param w3 Image cache penalty weight
     * @param w4 Package cache penalty weight
     * @param w5 Age boost weight (applied as negative)
     * @param w6 Heavy test penalty weight
     * @param wIO I/O dimension weight
     * @param wCPU CPU dimension weight
     * @param wMEM Memory dimension weight
     * @param wNET Network dimension weight
     * @param loadProvider Provider for in-flight load penalties
     */
    public ScoreFunction(double w1, double w2, double w3, double w4, double w5, double w6,
                        double wIO, double wCPU, double wMEM, double wNET,
                        NodeLoadProvider loadProvider) {
        this.w1 = w1;
        this.w2 = w2;
        this.w3 = w3;
        this.w4 = w4;
        this.w5 = w5;
        this.w6 = w6;
        this.wIO = wIO;
        this.wCPU = wCPU;
        this.wMEM = wMEM;
        this.wNET = wNET;
        this.nodeLoadProvider = loadProvider != null ? loadProvider : NodeLoadProvider.NOOP;
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

        // 5. Heavy test penalty (avoid placing heavy tests on constrained nodes)
        double heavyPenalty = computeHeavyPenalty(test, node);

        double baseScore = w1 * pressure + w2 * durScore + w3 * imagePenalty + w4 * pkgPenalty 
                         - w5 * ageBoost + w6 * heavyPenalty;
        double loadPenalty = nodeLoadProvider.getLoadPenalty(node.getNodeId());

        return baseScore + loadPenalty;
    }

    /**
     * Computes a Tetris-style alignment score between a test's demand vector and a node's headroom.
     * Higher is better; it increases when the test consumes resources the node has plenty of and
     * avoids resources that are tight.
     */
    public double alignment(TestInstance test, NodeSnapshot node) {
        double cpuDemand = safeRatio(test.getPredictedCpuPct(), node.getCpuPct());
        double memDemand = safeRatio(test.getPredictedMemMb(), node.getMemMb());
        double ioDemand = safeRatio(Math.max(test.getPredictedIoReadMbPerSec(), test.getPredictedIoWriteMbPerSec()),
                                    node.getIoMbPerSec());

        double cpuHeadroom = safeRatio(node.getFreeCpuPct(), node.getCpuPct());
        double memHeadroom = safeRatio(node.getFreeMemMb(), node.getMemMb());
        double ioHeadroom = safeRatio(Math.min(node.getFreeIoReadMbPerSec(), node.getFreeIoWriteMbPerSec()),
                                      Math.max(node.getIoReadMbPerSec(), node.getIoWriteMbPerSec()));

        return (cpuHeadroom * cpuDemand) + (memHeadroom * memDemand) + (ioHeadroom * ioDemand);
    }

    /**
     * Computes resource pressure using dominant resource fairness with IO-first weighting.
     * Returns max(w_d × (q_d / f_d)) across all resource dimensions, with IO weighted heavily.
     */
    private double computePressure(TestInstance test, NodeSnapshot node) {
        double cpuPressure = test.getPredictedCpuPct() / Math.max(EPSILON, node.getFreeCpuPct());
        double memPressure = test.getPredictedMemMb() / Math.max(EPSILON, node.getFreeMemMb());
        
        // I/O-first: Compute separate read/write pressure, take the worse, and apply heavy weight
        double ioReadPressure = test.getPredictedIoReadMbPerSec() / Math.max(EPSILON, node.getFreeIoReadMbPerSec());
        double ioWritePressure = test.getPredictedIoWriteMbPerSec() / Math.max(EPSILON, node.getFreeIoWriteMbPerSec());
        double ioPressure = Math.max(ioReadPressure, ioWritePressure); // Take worse of read/write
        
        double iopsPressure = test.getPredictedIops() / Math.max(EPSILON, node.getFreeIops());
        double netPressure = test.getPredictedNetMbPerSec() / Math.max(EPSILON, node.getFreeNetMbPerSec());

        // Apply per-dimension weights (IO-dominant)
        double weightedIo = wIO * ioPressure;
        double weightedCpu = wCPU * cpuPressure;
        double weightedMem = wMEM * memPressure;
        double weightedNet = wNET * netPressure;
        double weightedIops = wIO * iopsPressure; // IOPS also gets IO weight

        // Return the maximum weighted pressure (IO will dominate due to higher weight)
        return Math.max(Math.max(Math.max(Math.max(weightedIo, weightedCpu), weightedMem), weightedIops), weightedNet);
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
     * Computes a penalty for placing heavy tests on constrained nodes.
     *
     * <p>This is a SOFT preference, not a blocker. Heavy tests will still run even
     * on constrained nodes if that's the only option (e.g., single-tester scenario).
     * The penalty is a small nudge to prefer less-loaded nodes when multiple are available.</p>
     *
     * <p>For HEAVY and EXTREME tests, this penalty slightly prefers nodes that:
     * <ul>
     *   <li>Are not under disk pressure</li>
     *   <li>Have more free I/O headroom</li>
     * </ul>
     * </p>
     *
     * @param test The test instance
     * @param node The candidate node
     * @return Penalty value (0 for NORMAL tests, small values for heavy tests to nudge spreading)
     */
    private double computeHeavyPenalty(TestInstance test, NodeSnapshot node) {
        // No penalty for normal tests
        if (!test.isHeavy()) {
            return 0.0;
        }

        double penalty = 0.0;

        // Small penalty if node is under disk pressure - prefer other nodes but don't block
        if (node.isDiskPressure()) {
            penalty += 2.0;  // Small nudge, not a blocker
        }

        // Penalty based on current I/O utilization - prefer less busy nodes
        double freeIoReadFrac = safeRatio(node.getFreeIoReadMbPerSec(), node.getIoReadMbPerSec());
        double freeIoWriteFrac = safeRatio(node.getFreeIoWriteMbPerSec(), node.getIoWriteMbPerSec());
        double freeIoFrac = Math.min(freeIoReadFrac, freeIoWriteFrac);

        // Linear penalty: 0 when node is idle (freeIoFrac=1), up to 1.0 when fully loaded (freeIoFrac=0)
        double utilization = 1.0 - freeIoFrac;
        penalty += utilization;  // Range: 0.0 to 1.0

        // Additional small penalty for EXTREME tests on nodes with other running tests
        // This gently encourages spreading EXTREME tests across nodes
        if (test.isExtreme() && utilization > 0.1) {
            penalty += 0.5;  // Small additional nudge
        }

        return penalty;
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
        double heavyPenalty = computeHeavyPenalty(test, node);

        String heavyInfo = test.isHeavy() 
            ? String.format(", heavy=%.2f(%.2f)", heavyPenalty, w6 * heavyPenalty)
            : "";

        return String.format(
            "Score=%.3f [pressure=%.3f(%.2f), dur=%.3f(%.2f), img=%.1f(%.2f), pkg=%.1f(%.2f), age=%.3f(-%.2f)%s]",
            totalScore,
            pressure, w1 * pressure,
            durScore, w2 * durScore,
            imagePenalty, w3 * imagePenalty,
            pkgPenalty, w4 * pkgPenalty,
            ageBoost, w5 * ageBoost,
            heavyInfo
        );
    }

    @FunctionalInterface
    public interface NodeLoadProvider {
        NodeLoadProvider NOOP = nodeId -> 0.0;
        double getLoadPenalty(String nodeId);
    }

    private double safeRatio(double numerator, double denominator) {
        return Math.max(0.0, numerator) / Math.max(EPSILON, denominator);
    }
}
