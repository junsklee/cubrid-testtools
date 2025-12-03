package com.navercorp.cubridqa.builder.scheduler;

import java.util.logging.Logger;

/**
 * Inflates predicted resource demands for heavy tests to ensure proper isolation.
 *
 * <p>The inflator applies multipliers to predicted demands based on heavy classification:
 * <ul>
 *   <li><b>NORMAL:</b> No inflation (factors = 1.0)</li>
 *   <li><b>HEAVY:</b> Moderate inflation (CPU/MEM 1.3x, IO/IOPS 1.5x)</li>
 *   <li><b>EXTREME:</b> Strong inflation for CPU/MEM (1.5x), and IO/IOPS set to a fraction
 *       of node capacity to force near-exclusive placement</li>
 * </ul>
 * </p>
 *
 * <p>The inflation ensures that:
 * <ul>
 *   <li>Heavy tests get more resource headroom in scheduling decisions</li>
 *   <li>EXTREME tests effectively get one per node (IO prediction near capacity)</li>
 *   <li>Existing {@link NodeDirectory#hasResourceHeadroom} logic naturally prevents co-location</li>
 * </ul>
 * </p>
 */
public class HeavyDemandInflator {

    private static final Logger logger = Logger.getLogger(HeavyDemandInflator.class.getName());

    // Default inflation factors (conservative - avoid over-reserving and delaying tests)
    private static final double NORMAL_FACTOR = 1.0;
    private static final double HEAVY_CPU_FACTOR = 1.15;
    private static final double HEAVY_MEM_FACTOR = 1.15;
    private static final double HEAVY_IO_FACTOR = 1.25;
    private static final double HEAVY_IOPS_FACTOR = 1.25;
    private static final double EXTREME_CPU_FACTOR = 1.25;
    private static final double EXTREME_MEM_FACTOR = 1.25;
    
    // For EXTREME tests: fraction of node capacity to claim
    // 0.5 allows ~2 EXTREME tests per node; lower values allow more coexistence
    private static final double EXTREME_IO_CAPACITY_FRACTION = 0.5;

    /**
     * Configuration for inflation factors.
     */
    public static final class Config {
        public final double heavyCpuFactor;
        public final double heavyMemFactor;
        public final double heavyIoFactor;
        public final double heavyIopsFactor;
        public final double extremeCpuFactor;
        public final double extremeMemFactor;
        public final double extremeIoCapacityFraction;

        public Config() {
            this(HEAVY_CPU_FACTOR, HEAVY_MEM_FACTOR, HEAVY_IO_FACTOR, HEAVY_IOPS_FACTOR,
                 EXTREME_CPU_FACTOR, EXTREME_MEM_FACTOR, EXTREME_IO_CAPACITY_FRACTION);
        }

        public Config(double heavyCpuFactor, double heavyMemFactor, double heavyIoFactor, double heavyIopsFactor,
                     double extremeCpuFactor, double extremeMemFactor, double extremeIoCapacityFraction) {
            this.heavyCpuFactor = heavyCpuFactor;
            this.heavyMemFactor = heavyMemFactor;
            this.heavyIoFactor = heavyIoFactor;
            this.heavyIopsFactor = heavyIopsFactor;
            this.extremeCpuFactor = extremeCpuFactor;
            this.extremeMemFactor = extremeMemFactor;
            this.extremeIoCapacityFraction = extremeIoCapacityFraction;
        }
    }

    /**
     * Result of demand inflation containing all inflated resource values.
     */
    public static final class InflatedDemand {
        public final double cpuPct;
        public final double memMb;
        public final double ioReadMbPerSec;
        public final double ioWriteMbPerSec;
        public final double iops;
        public final double netMbPerSec;
        public final TestProfile.HeavyClass appliedClass;

        public InflatedDemand(double cpuPct, double memMb, double ioReadMbPerSec, double ioWriteMbPerSec,
                             double iops, double netMbPerSec, TestProfile.HeavyClass appliedClass) {
            this.cpuPct = cpuPct;
            this.memMb = memMb;
            this.ioReadMbPerSec = ioReadMbPerSec;
            this.ioWriteMbPerSec = ioWriteMbPerSec;
            this.iops = iops;
            this.netMbPerSec = netMbPerSec;
            this.appliedClass = appliedClass;
        }

        @Override
        public String toString() {
            return String.format("InflatedDemand{cpu=%.1f%%, mem=%.0fMB, io_r=%.1f, io_w=%.1fMB/s, iops=%.0f, class=%s}",
                    cpuPct, memMb, ioReadMbPerSec, ioWriteMbPerSec, iops, appliedClass);
        }
    }

    /**
     * Node capacity information needed for EXTREME test inflation.
     */
    public static final class NodeCapacityInfo {
        public final double ioReadCapacityMbPerSec;
        public final double ioWriteCapacityMbPerSec;
        public final double iopsCapacity;

        public NodeCapacityInfo(double ioReadCapacityMbPerSec, double ioWriteCapacityMbPerSec, double iopsCapacity) {
            this.ioReadCapacityMbPerSec = ioReadCapacityMbPerSec;
            this.ioWriteCapacityMbPerSec = ioWriteCapacityMbPerSec;
            this.iopsCapacity = iopsCapacity;
        }

        /**
         * Creates NodeCapacityInfo from a NodeSnapshot.
         */
        public static NodeCapacityInfo fromSnapshot(NodeSnapshot snapshot) {
            return new NodeCapacityInfo(
                    snapshot.getIoReadMbPerSec(),
                    snapshot.getIoWriteMbPerSec(),
                    snapshot.getIops()
            );
        }

        /**
         * Creates a default capacity for testing or when no node info is available.
         */
        public static NodeCapacityInfo defaultCapacity() {
            return new NodeCapacityInfo(500.0, 500.0, 10000.0);
        }
    }

    private final Config config;

    /**
     * Creates an inflator with default configuration.
     */
    public HeavyDemandInflator() {
        this(new Config());
    }

    /**
     * Creates an inflator with custom configuration.
     */
    public HeavyDemandInflator(Config config) {
        this.config = config;
    }

    /**
     * Inflates demand based on heavy classification and optional node capacity.
     *
     * @param profile the test's heavy profile
     * @param baseCpuPct base predicted CPU percentage
     * @param baseMemMb base predicted memory in MB
     * @param baseIoReadMbPerSec base predicted I/O read throughput
     * @param baseIoWriteMbPerSec base predicted I/O write throughput
     * @param baseIops base predicted IOPS
     * @param baseNetMbPerSec base predicted network throughput
     * @param nodeCapacity node capacity info (required for EXTREME I/O inflation, may be null)
     * @return inflated demand values
     */
    public InflatedDemand inflate(TestProfile profile,
                                   double baseCpuPct, double baseMemMb,
                                   double baseIoReadMbPerSec, double baseIoWriteMbPerSec,
                                   double baseIops, double baseNetMbPerSec,
                                   NodeCapacityInfo nodeCapacity) {

        if (profile == null) {
            return new InflatedDemand(baseCpuPct, baseMemMb, baseIoReadMbPerSec, baseIoWriteMbPerSec,
                    baseIops, baseNetMbPerSec, TestProfile.HeavyClass.NORMAL);
        }

        TestProfile.HeavyClass heavyClass = profile.getHeavyClass();

        switch (heavyClass) {
            case NORMAL:
                return new InflatedDemand(
                        baseCpuPct * NORMAL_FACTOR,
                        baseMemMb * NORMAL_FACTOR,
                        baseIoReadMbPerSec * NORMAL_FACTOR,
                        baseIoWriteMbPerSec * NORMAL_FACTOR,
                        baseIops * NORMAL_FACTOR,
                        baseNetMbPerSec * NORMAL_FACTOR,
                        heavyClass
                );

            case HEAVY:
                return new InflatedDemand(
                        baseCpuPct * config.heavyCpuFactor,
                        baseMemMb * config.heavyMemFactor,
                        baseIoReadMbPerSec * config.heavyIoFactor,
                        baseIoWriteMbPerSec * config.heavyIoFactor,
                        baseIops * config.heavyIopsFactor,
                        baseNetMbPerSec * config.heavyIoFactor,  // Network follows IO factor
                        heavyClass
                );

            case EXTREME:
                // For EXTREME tests, set IO/IOPS to a fraction of node capacity
                // This forces near-exclusive placement (at most 1 EXTREME test per node)
                double inflatedIoRead, inflatedIoWrite, inflatedIops;

                if (nodeCapacity != null) {
                    // Use node capacity to compute near-exclusive demand
                    inflatedIoRead = nodeCapacity.ioReadCapacityMbPerSec * config.extremeIoCapacityFraction;
                    inflatedIoWrite = nodeCapacity.ioWriteCapacityMbPerSec * config.extremeIoCapacityFraction;
                    inflatedIops = nodeCapacity.iopsCapacity * config.extremeIoCapacityFraction;

                    // But don't go below actual base demand (in case test already exceeds fraction)
                    inflatedIoRead = Math.max(inflatedIoRead, baseIoReadMbPerSec * config.heavyIoFactor);
                    inflatedIoWrite = Math.max(inflatedIoWrite, baseIoWriteMbPerSec * config.heavyIoFactor);
                    inflatedIops = Math.max(inflatedIops, baseIops * config.heavyIopsFactor);
                } else {
                    // No node capacity info - use larger multipliers as fallback
                    inflatedIoRead = baseIoReadMbPerSec * 3.0;
                    inflatedIoWrite = baseIoWriteMbPerSec * 3.0;
                    inflatedIops = baseIops * 3.0;
                }

                logger.fine(String.format("EXTREME inflation for %s: IO_R %.1f→%.1f, IO_W %.1f→%.1f, IOPS %.0f→%.0f",
                        profile.getTestKey(),
                        baseIoReadMbPerSec, inflatedIoRead,
                        baseIoWriteMbPerSec, inflatedIoWrite,
                        baseIops, inflatedIops));

                return new InflatedDemand(
                        baseCpuPct * config.extremeCpuFactor,
                        baseMemMb * config.extremeMemFactor,
                        inflatedIoRead,
                        inflatedIoWrite,
                        inflatedIops,
                        baseNetMbPerSec * config.extremeCpuFactor,  // Network follows CPU factor for EXTREME
                        heavyClass
                );

            default:
                // Fallback (should not happen)
                return new InflatedDemand(baseCpuPct, baseMemMb, baseIoReadMbPerSec, baseIoWriteMbPerSec,
                        baseIops, baseNetMbPerSec, TestProfile.HeavyClass.NORMAL);
        }
    }

    /**
     * Convenience method to inflate a TestInstance.Builder in place.
     *
     * @param builder the TestInstance.Builder to modify
     * @param profile the test's heavy profile
     * @param nodeCapacity node capacity info (may be null)
     * @return the modified builder (for chaining)
     */
    public TestInstance.Builder inflateBuilder(TestInstance.Builder builder,
                                                TestProfile profile,
                                                NodeCapacityInfo nodeCapacity,
                                                double baseCpuPct, double baseMemMb,
                                                double baseIoReadMbPerSec, double baseIoWriteMbPerSec,
                                                double baseIops, double baseNetMbPerSec) {

        InflatedDemand inflated = inflate(profile, baseCpuPct, baseMemMb,
                baseIoReadMbPerSec, baseIoWriteMbPerSec, baseIops, baseNetMbPerSec, nodeCapacity);

        return builder
                .predictedCpuPct(inflated.cpuPct)
                .predictedMemMb(inflated.memMb)
                .predictedIoReadMbPerSec(inflated.ioReadMbPerSec)
                .predictedIoWriteMbPerSec(inflated.ioWriteMbPerSec)
                .predictedIops(inflated.iops)
                .predictedNetMbPerSec(inflated.netMbPerSec);
    }

    /**
     * Returns the configuration being used.
     */
    public Config getConfig() {
        return config;
    }

    /**
     * Creates a Config from BuilderConfig values.
     */
    public static Config fromBuilderConfig(double heavyCpuFactor, double heavyMemFactor,
                                           double heavyIoFactor, double extremeIoCapacityFraction) {
        return new Config(
                heavyCpuFactor,
                heavyMemFactor,
                heavyIoFactor,
                heavyIoFactor,  // IOPS uses same factor as IO
                1.5,            // Extreme CPU factor (fixed)
                1.5,            // Extreme MEM factor (fixed)
                extremeIoCapacityFraction
        );
    }
}

