package com.navercorp.cubridqa.builder.tester.stats;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Comprehensive validation for test observations before persistence.
 *
 * <p>Rejects observations with:
 * - Incomplete metrics (metrics_complete=false)
 * - Invalid/negative resource values
 * - Unrealistic values exceeding hardware limits
 * - Duration outside reasonable bounds
 *
 * <p>Tracks rejection reasons for monitoring and debugging.
 */
public class ObservationValidator {

    private static final Logger logger = Logger.getLogger(ObservationValidator.class.getName());

    // Configuration (can be made configurable later)
    private static final long MAX_TEST_DURATION_MS = TimeUnit.HOURS.toMillis(4); // 4 hours
    private static final double CPU_SPIKE_TOLERANCE = 2.0; // Allow 2x cores for spikes
    private static final double MEM_OVERHEAD_TOLERANCE = 1.05; // Allow 5% over total memory
    private static final double IO_MAX_MB_PER_SEC = 10000.0; // 10 GB/s (generous for NVMe)
    private static final double IOPS_MAX = 1000000.0; // 1M IOPS
    private static final double NET_MAX_MB_PER_SEC = 10000.0; // 10 GB/s

    // Rejection counters
    private final AtomicLong rejectedMetricsIncomplete = new AtomicLong(0);
    private final AtomicLong rejectedInvalidDuration = new AtomicLong(0);
    private final AtomicLong rejectedInvalidCpu = new AtomicLong(0);
    private final AtomicLong rejectedInvalidMemory = new AtomicLong(0);
    private final AtomicLong rejectedInvalidIO = new AtomicLong(0);
    private final AtomicLong rejectedInvalidNetwork = new AtomicLong(0);
    private final AtomicLong rejectedSchemaVersion = new AtomicLong(0);
    private final AtomicLong totalAccepted = new AtomicLong(0);

    // Hardware limits (should be detected from NodeCapacity)
    private final int cpuCores;
    private final double memoryTotalMb;

    public ObservationValidator(int cpuCores, double memoryTotalMb) {
        this.cpuCores = cpuCores > 0 ? cpuCores : 8; // Default to 8 cores
        this.memoryTotalMb = memoryTotalMb > 0 ? memoryTotalMb : 16384.0; // Default to 16 GB
    }

    /**
     * Default constructor uses reasonable defaults.
     */
    public ObservationValidator() {
        this(8, 16384.0);
    }

    /**
     * Validates an observation and returns true if it should be persisted.
     *
     * @param obs The observation to validate
     * @return true if valid, false if should be rejected
     */
    public boolean isValid(TestObservation obs) {
        // Check 1: Metrics must be complete
        if (!obs.isMetricsComplete()) {
            rejectedMetricsIncomplete.incrementAndGet();
            logRejection("metrics_incomplete", obs);
            return false;
        }

        // Check 2: Schema version
        if (obs.getVersion() != 1) {
            rejectedSchemaVersion.incrementAndGet();
            logRejection("unsupported_schema_version=" + obs.getVersion(), obs);
            return false;
        }

        // Check 3: Duration must be positive and reasonable
        long duration = obs.getDurationMs();
        if (duration <= 0 || duration > MAX_TEST_DURATION_MS) {
            rejectedInvalidDuration.incrementAndGet();
            logRejection("invalid_duration=" + duration, obs);
            return false;
        }

        // Check 4: CPU metrics
        double cpuMean = obs.getCpuPctMean();
        double cpuPeak = obs.getCpuPctPeak();
        double maxCpu = cpuCores * 100.0;
        double maxCpuPeak = maxCpu * CPU_SPIKE_TOLERANCE;

        if (cpuMean < 0 || cpuMean > maxCpu * 1.25) {
            rejectedInvalidCpu.incrementAndGet();
            logRejection("invalid_cpu_mean=" + cpuMean, obs);
            return false;
        }

        if (cpuPeak < 0 || cpuPeak > maxCpuPeak) {
            rejectedInvalidCpu.incrementAndGet();
            logRejection("invalid_cpu_peak=" + cpuPeak, obs);
            return false;
        }

        // Check 5: Memory metrics
        double memMean = obs.getMemMbMean();
        double memPeak = obs.getMemMbPeak();
        double maxMem = memoryTotalMb * MEM_OVERHEAD_TOLERANCE;

        if (memMean < 0 || memMean > maxMem) {
            rejectedInvalidMemory.incrementAndGet();
            logRejection("invalid_mem_mean=" + memMean, obs);
            return false;
        }

        if (memPeak <= 0 || memPeak > maxMem) {
            rejectedInvalidMemory.incrementAndGet();
            logRejection("invalid_mem_peak=" + memPeak, obs);
            return false;
        }

        // Check 6: I/O metrics
        double ioMean = obs.getIoMbPerSecMean();
        double iops = obs.getIopsMean();

        if (ioMean < 0 || ioMean > IO_MAX_MB_PER_SEC) {
            rejectedInvalidIO.incrementAndGet();
            logRejection("invalid_io_mean=" + ioMean, obs);
            return false;
        }

        if (iops < 0 || iops > IOPS_MAX) {
            rejectedInvalidIO.incrementAndGet();
            logRejection("invalid_iops=" + iops, obs);
            return false;
        }

        // Check 7: Network metrics
        double netMean = obs.getNetMbPerSecMean();

        if (netMean < 0 || netMean > NET_MAX_MB_PER_SEC) {
            rejectedInvalidNetwork.incrementAndGet();
            logRejection("invalid_net_mean=" + netMean, obs);
            return false;
        }

        // All checks passed
        totalAccepted.incrementAndGet();
        return true;
    }

    /**
     * Logs rejection with details for debugging.
     */
    private void logRejection(String reason, TestObservation obs) {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(String.format("Rejected observation: %s, test=%s, commit=%s",
                    reason, obs.getTestKey(), obs.getCommit()));
        }

        // Log summary every 100 rejections
        long totalRejections = getTotalRejected();
        if (totalRejections % 100 == 0) {
            logger.warning("Observation validation summary: " + getMetrics());
        }
    }

    /**
     * Returns total number of rejections across all categories.
     */
    public long getTotalRejected() {
        return rejectedMetricsIncomplete.get()
                + rejectedInvalidDuration.get()
                + rejectedInvalidCpu.get()
                + rejectedInvalidMemory.get()
                + rejectedInvalidIO.get()
                + rejectedInvalidNetwork.get()
                + rejectedSchemaVersion.get();
    }

    /**
     * Returns validation metrics for monitoring.
     */
    public ValidationMetrics getMetrics() {
        return new ValidationMetrics(
                totalAccepted.get(),
                rejectedMetricsIncomplete.get(),
                rejectedInvalidDuration.get(),
                rejectedInvalidCpu.get(),
                rejectedInvalidMemory.get(),
                rejectedInvalidIO.get(),
                rejectedInvalidNetwork.get(),
                rejectedSchemaVersion.get()
        );
    }

    /**
     * Resets all counters (useful for testing).
     */
    public void resetCounters() {
        rejectedMetricsIncomplete.set(0);
        rejectedInvalidDuration.set(0);
        rejectedInvalidCpu.set(0);
        rejectedInvalidMemory.set(0);
        rejectedInvalidIO.set(0);
        rejectedInvalidNetwork.set(0);
        rejectedSchemaVersion.set(0);
        totalAccepted.set(0);
    }

    /**
     * Metrics snapshot for monitoring.
     */
    public static class ValidationMetrics {
        public final long accepted;
        public final long rejectedMetricsIncomplete;
        public final long rejectedInvalidDuration;
        public final long rejectedInvalidCpu;
        public final long rejectedInvalidMemory;
        public final long rejectedInvalidIO;
        public final long rejectedInvalidNetwork;
        public final long rejectedSchemaVersion;
        public final long totalRejected;

        public ValidationMetrics(long accepted, long metricsIncomplete, long invalidDuration,
                                 long invalidCpu, long invalidMemory, long invalidIO,
                                 long invalidNetwork, long schemaVersion) {
            this.accepted = accepted;
            this.rejectedMetricsIncomplete = metricsIncomplete;
            this.rejectedInvalidDuration = invalidDuration;
            this.rejectedInvalidCpu = invalidCpu;
            this.rejectedInvalidMemory = invalidMemory;
            this.rejectedInvalidIO = invalidIO;
            this.rejectedInvalidNetwork = invalidNetwork;
            this.rejectedSchemaVersion = schemaVersion;
            this.totalRejected = metricsIncomplete + invalidDuration + invalidCpu +
                    invalidMemory + invalidIO + invalidNetwork + schemaVersion;
        }

        @Override
        public String toString() {
            double rejectionRate = (accepted + totalRejected) > 0
                    ? (100.0 * totalRejected / (accepted + totalRejected))
                    : 0.0;

            return String.format("ValidationMetrics{accepted=%d, rejected=%d (%.1f%%), " +
                            "reasons: incomplete=%d, duration=%d, cpu=%d, mem=%d, io=%d, net=%d, schema=%d}",
                    accepted, totalRejected, rejectionRate,
                    rejectedMetricsIncomplete, rejectedInvalidDuration, rejectedInvalidCpu,
                    rejectedInvalidMemory, rejectedInvalidIO, rejectedInvalidNetwork,
                    rejectedSchemaVersion);
        }
    }
}
