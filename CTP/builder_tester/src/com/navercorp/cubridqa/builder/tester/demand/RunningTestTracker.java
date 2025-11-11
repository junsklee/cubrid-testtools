package com.navercorp.cubridqa.builder.tester.demand;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks reservations (reserved utilization) with O(1) rolling totals.
 *
 * <p>States:
 * <ul>
 *   <li>ADMITTED: soft admission (no reservation added yet)</li>
 *   <li>RUNNING: hard reservation counted in totals</li>
 * </ul></p>
 *
 * <p>Supports phase updates: setup → run (adjusts totals).</p>
 *
 * <p>Thread-safe: Uses ConcurrentHashMap + atomic adders for lock-free reads.</p>
 */
public class RunningTestTracker {

    private static final Logger logger = Logger.getLogger(RunningTestTracker.class.getName());

    enum State {
        ADMITTED,
        RUNNING
    }

    static final class RunningTestInfo {
        final String testId;
        final String testKey;
        volatile State state;
        volatile PredictedDemand.Phase currentPhase; // null if single-vector
        volatile int cpuMc;
        volatile long memBytes, ioBps, iops, netBps;
        volatile boolean isDefault;
        final Instant startedAt;

        RunningTestInfo(String testId, String testKey, int cpuMc, long memBytes,
                        long ioBps, long iops, long netBps, boolean isDefault) {
            this.testId = testId;
            this.testKey = testKey;
            this.cpuMc = cpuMc;
            this.memBytes = memBytes;
            this.ioBps = ioBps;
            this.iops = iops;
            this.netBps = netBps;
            this.state = State.ADMITTED;
            this.isDefault = isDefault;
            this.startedAt = Instant.now();
        }
    }

    private final ConcurrentHashMap<String, RunningTestInfo> tests = new ConcurrentHashMap<>();

    // O(1) rolling totals for RESERVED (RUNNING state only)
    private final DoubleAdder totalCpuMc = new DoubleAdder();
    private final LongAdder totalMemBytes = new LongAdder();
    private final LongAdder totalIoBps = new LongAdder();
    private final LongAdder totalIops = new LongAdder();
    private final LongAdder totalNetBps = new LongAdder();
    private final LongAdder totalCount = new LongAdder();
    private final LongAdder defaultCount = new LongAdder();

    /**
     * Admit a test (soft), not counted yet.
     */
    public void admit(String testId, String testKey, PredictedDemand d) {
        PredictedDemand.Phase p = (d.getPhases() != null && !d.getPhases().isEmpty()) ? d.getPhases().get(0) : null;
        int cpu = (p != null ? p.cpuMillicores : d.getCpuMillicores());
        long mem = (p != null ? p.memBytes : d.getMemBytes());
        long io = (p != null ? p.ioBytesPerSec : d.getIoBytesPerSec());
        long iops = (p != null ? p.iops : d.getIops());
        long net = (p != null ? p.netBytesPerSec : d.getNetBytesPerSec());
        boolean isDefault = d.isDefault();

        RunningTestInfo info = new RunningTestInfo(testId, testKey, cpu, mem, io, iops, net, isDefault);
        info.currentPhase = p;
        tests.put(testId, info);

        logger.log(Level.FINE, "Admitted test {0} ({1}) with cpu={2}mCPU, mem={3}B",
                new Object[]{testId, testKey, cpu, mem});
    }

    /**
     * Switch to RUNNING: counts are added to reserved totals exactly once.
     */
    public void startRunning(String testId) {
        RunningTestInfo info = tests.get(testId);
        if (info == null || info.state == State.RUNNING) {
            return;
        }
        info.state = State.RUNNING;
        totalCpuMc.add(info.cpuMc);
        totalMemBytes.add(info.memBytes);
        totalIoBps.add(info.ioBps);
        totalIops.add(info.iops);
        totalNetBps.add(info.netBps);
        totalCount.increment();
        if (info.isDefault) {
            defaultCount.increment();
        }

        logger.log(Level.FINE, "Started running test {0} ({1})",
                new Object[]{testId, info.testKey});
    }

    /**
     * Update to a new phase (e.g., setup → run): adjust reserved totals.
     */
    public void updatePhase(String testId, PredictedDemand.Phase next) {
        RunningTestInfo info = tests.get(testId);
        if (info == null || info.state != State.RUNNING) {
            return;
        }
        // remove prior
        totalCpuMc.add(-info.cpuMc);
        totalMemBytes.add(-info.memBytes);
        totalIoBps.add(-info.ioBps);
        totalIops.add(-info.iops);
        totalNetBps.add(-info.netBps);

        // apply next
        info.currentPhase = next;
        info.cpuMc = next.cpuMillicores;
        info.memBytes = next.memBytes;
        info.ioBps = next.ioBytesPerSec;
        info.iops = next.iops;
        info.netBps = next.netBytesPerSec;
        totalCpuMc.add(info.cpuMc);
        totalMemBytes.add(info.memBytes);
        totalIoBps.add(info.ioBps);
        totalIops.add(info.iops);
        totalNetBps.add(info.netBps);

        logger.log(Level.FINE, "Updated test {0} to phase {1}",
                new Object[]{testId, next.name});
    }

    /**
     * Unregister and subtract from totals if in RUNNING.
     */
    public void unregister(String testId) {
        RunningTestInfo info = tests.remove(testId);
        if (info == null) {
            logger.log(Level.WARNING, "Attempted to unregister unknown test ID: {0}", testId);
            return;
        }
        if (info.state == State.RUNNING) {
            totalCpuMc.add(-info.cpuMc);
            totalMemBytes.add(-info.memBytes);
            totalIoBps.add(-info.ioBps);
            totalIops.add(-info.iops);
            totalNetBps.add(-info.netBps);
            totalCount.decrement();
            if (info.isDefault) {
                defaultCount.decrement();
            }
        }

        long durationMs = java.time.Duration.between(info.startedAt, Instant.now()).toMillis();
        logger.log(Level.FINE, "Unregistered test {0} ({1}) after {2}ms",
                new Object[]{testId, info.testKey, durationMs});
    }

    /**
     * O(1) snapshot of RESERVED totals. Actual usage is sampled elsewhere.
     */
    public UtilizationSnapshot getCurrentUtilization() {
        return UtilizationSnapshot.reserved(
                totalCpuMc.sum(),
                totalMemBytes.sum(),
                totalIoBps.sum(),
                totalIops.sum(),
                totalNetBps.sum(),
                (int) totalCount.sum(),
                (int) defaultCount.sum()
        );
    }

    /**
     * Returns the number of currently tracked tests (including admitted).
     */
    public int getRunningTestCount() {
        return tests.size();
    }

    /**
     * Iteration only for diagnostic dumps (rare).
     */
    public Map<String, RunningTestInfo> debugView() {
        return java.util.Collections.unmodifiableMap(tests);
    }

    /**
     * Clears all tracked tests (for testing/reset purposes).
     */
    public void clear() {
        int cleared = tests.size();
        tests.clear();
        totalCpuMc.reset();
        totalMemBytes.reset();
        totalIoBps.reset();
        totalIops.reset();
        totalNetBps.reset();
        totalCount.reset();
        defaultCount.reset();
        if (cleared > 0) {
            logger.log(Level.WARNING, "Cleared {0} running test entries", cleared);
        }
    }
}
