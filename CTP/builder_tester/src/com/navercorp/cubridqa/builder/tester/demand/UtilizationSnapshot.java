package com.navercorp.cubridqa.builder.tester.demand;

/**
 * Immutable snapshot of resource utilization.
 *
 * <p>Can represent either:
 * <ul>
 *   <li><b>Reserved</b>: sum of predicted resource demands (reservations)</li>
 *   <li><b>Actual</b>: sampled current usage from cgroups/Docker stats</li>
 * </ul></p>
 *
 * <p>Uses canonical units (cpuMillicores, memBytes, etc.) for consistency.</p>
 *
 * <p>Thread-safe (immutable).</p>
 */
public class UtilizationSnapshot {

    private final double totalCpuMillicores;
    private final long totalMemBytes;
    private final long totalIoBytesPerSec;
    private final long totalIoReadBytesPerSec;
    private final long totalIoWriteBytesPerSec;
    private final long totalIops;
    private final long totalNetBytesPerSec;
    private final int testCount;
    private final int defaultCount;  // Tests using conservative defaults

    private UtilizationSnapshot(double cpuMc, long memBytes, long ioBps, long iops, long netBps,
                                int testCount, int defaultCount, long ioReadBps, long ioWriteBps) {
        this.totalCpuMillicores = cpuMc;
        this.totalMemBytes = memBytes;
        this.totalIoBytesPerSec = ioBps;
        this.totalIoReadBytesPerSec = Math.max(0, ioReadBps);
        this.totalIoWriteBytesPerSec = Math.max(0, ioWriteBps);
        this.totalIops = iops;
        this.totalNetBytesPerSec = netBps;
        this.testCount = testCount;
        this.defaultCount = defaultCount;
    }

    /**
     * Factory method for reserved utilization (from predictions).
     */
    public static UtilizationSnapshot reserved(double cpuMc, long memBytes, long ioBps,
                                               long iops, long netBps, int testCount, int defaultCount,
                                               long ioReadBps, long ioWriteBps) {
        return new UtilizationSnapshot(cpuMc, memBytes, ioBps, iops, netBps, testCount, defaultCount, ioReadBps, ioWriteBps);
    }

    /**
     * Factory method for actual utilization (from sampling).
     */
    public static UtilizationSnapshot actual(double cpuMc, long memBytes, long ioBps,
                                             long iops, long netBps, long ioReadBps, long ioWriteBps) {
        return new UtilizationSnapshot(cpuMc, memBytes, ioBps, iops, netBps, 0, 0, ioReadBps, ioWriteBps);
    }

    /**
     * Returns empty utilization snapshot (no tests running).
     */
    public static UtilizationSnapshot empty() {
        return new UtilizationSnapshot(0.0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // Getters (canonical units)

    public double getTotalCpuMillicores() {
        return totalCpuMillicores;
    }

    public long getTotalMemBytes() {
        return totalMemBytes;
    }

    public long getTotalIoBytesPerSec() {
        return totalIoBytesPerSec;
    }

    public long getTotalIoReadBytesPerSec() {
        return totalIoReadBytesPerSec;
    }

    public long getTotalIoWriteBytesPerSec() {
        return totalIoWriteBytesPerSec;
    }

    public long getTotalIops() {
        return totalIops;
    }

    public long getTotalNetBytesPerSec() {
        return totalNetBytesPerSec;
    }

    public int getTestCount() {
        return testCount;
    }

    public int getDefaultCount() {
        return defaultCount;
    }

    // TODO: DEPRECATED - Legacy getters for backward compatibility (map canonical → legacy)
    // These methods are kept for compatibility with existing code that expects legacy units.
    // New code should use the canonical getters above (getTotalCpuMillicores, getTotalMemBytes, etc.)
    // Once all calling code is migrated to canonical units, these methods can be removed.

    /**
     * @deprecated Use {@link #getTotalCpuMillicores()} instead. This method converts mCPU to percentage.
     */
    @Deprecated
    public double getTotalCpuPct() {
        return totalCpuMillicores / 10.0; // Convert mCPU to %
    }

    /**
     * @deprecated Use {@link #getTotalMemBytes()} instead. This method converts bytes to MB.
     */
    @Deprecated
    public double getTotalMemMb() {
        return totalMemBytes / (1024.0 * 1024.0); // Convert bytes to MB
    }

    /**
     * @deprecated Use {@link #getTotalIoBytesPerSec()} instead. This method converts B/s to MB/s.
     */
    @Deprecated
    public double getTotalIoMbPerSec() {
        return totalIoBytesPerSec / (1024.0 * 1024.0); // Convert B/s to MB/s
    }

    /**
     * @deprecated Use {@link #getTotalNetBytesPerSec()} instead. This method converts B/s to MB/s.
     */
    @Deprecated
    public double getTotalNetMbPerSec() {
        return totalNetBytesPerSec / (1024.0 * 1024.0); // Convert B/s to MB/s
    }

    /**
     * Returns true if any tests are using default predictions.
     */
    public boolean hasDefaults() {
        return defaultCount > 0;
    }

    /**
     * Returns true if no tests are running.
     */
    public boolean isEmpty() {
        return testCount == 0;
    }

    @Override
    public String toString() {
        return String.format("UtilizationSnapshot{tests=%d, cpu=%.0fmCPU, mem=%dB, defaults=%d}",
                testCount, totalCpuMillicores, totalMemBytes, defaultCount);
    }
}
