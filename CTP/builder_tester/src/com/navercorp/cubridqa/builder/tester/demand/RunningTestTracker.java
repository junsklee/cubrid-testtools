package com.navercorp.cubridqa.builder.tester.demand;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe tracker for running tests and their predicted resource demands.
 *
 * <p>Maintains a registry of currently executing tests with their predicted
 * resource usage. Provides real-time utilization snapshots by summing the
 * demands of all running tests.</p>
 *
 * <p>Used by HealthHandler to report accurate utilization instead of
 * conservative fixed estimates.</p>
 *
 * <p>Thread-safety: Uses ConcurrentHashMap for lock-free reads and
 * fine-grained locking on writes.</p>
 */
public class RunningTestTracker {

    private static final Logger logger = Logger.getLogger(RunningTestTracker.class.getName());

    private final ConcurrentHashMap<String, RunningTestInfo> runningTests;

    public RunningTestTracker() {
        this.runningTests = new ConcurrentHashMap<>();
    }

    /**
     * Registers a test as started with its predicted resource demand.
     *
     * @param testId unique identifier for this test execution
     * @param testKey test path/key for logging
     * @param demand predicted resource demands
     */
    public void registerTestStart(String testId, String testKey, PredictedDemand demand) {
        RunningTestInfo info = new RunningTestInfo(testId, testKey, Instant.now(), demand);
        RunningTestInfo previous = runningTests.put(testId, info);

        if (previous != null) {
            logger.log(Level.WARNING, "Test ID {0} was already registered - replacing", testId);
        }

        logger.log(Level.FINE, "Registered test {0} ({1}) with predicted CPU={2}%, mem={3}MB, conf={4}",
                new Object[]{testId, testKey, demand.getCpuPct(), demand.getMemMb(), demand.getConfidence()});
    }

    /**
     * Unregisters a test as completed.
     *
     * @param testId unique identifier for this test execution
     */
    public void unregisterTestEnd(String testId) {
        RunningTestInfo info = runningTests.remove(testId);

        if (info == null) {
            logger.log(Level.WARNING, "Attempted to unregister unknown test ID: {0}", testId);
            return;
        }

        long durationMs = java.time.Duration.between(info.getStartedAt(), Instant.now()).toMillis();
        logger.log(Level.FINE, "Unregistered test {0} ({1}) after {2}ms",
                new Object[]{testId, info.getTestKey(), durationMs});
    }

    /**
     * Returns current utilization snapshot by summing demands of all running tests.
     *
     * <p>Thread-safe: Performs a consistent snapshot of the concurrent map.</p>
     *
     * @return utilization snapshot (never null, empty if no tests running)
     */
    public UtilizationSnapshot getCurrentUtilization() {
        if (runningTests.isEmpty()) {
            return UtilizationSnapshot.empty();
        }

        double totalCpu = 0.0;
        double totalMem = 0.0;
        double totalIo = 0.0;
        double totalIops = 0.0;
        double totalNet = 0.0;
        int defaultCount = 0;

        // Lock-free iteration over concurrent map
        for (RunningTestInfo info : runningTests.values()) {
            PredictedDemand demand = info.getDemand();
            totalCpu += demand.getCpuPct();
            totalMem += demand.getMemMb();
            totalIo += demand.getIoMbPerSec();
            totalIops += demand.getIops();
            totalNet += demand.getNetMbPerSec();

            if (demand.isDefault()) {
                defaultCount++;
            }
        }

        return UtilizationSnapshot.builder()
                .totalCpuPct(totalCpu)
                .totalMemMb(totalMem)
                .totalIoMbPerSec(totalIo)
                .totalIops(totalIops)
                .totalNetMbPerSec(totalNet)
                .testCount(runningTests.size())
                .defaultCount(defaultCount)
                .build();
    }

    /**
     * Returns the number of currently running tests.
     *
     * @return running test count
     */
    public int getRunningTestCount() {
        return runningTests.size();
    }

    /**
     * Clears all tracked tests (for testing/reset purposes).
     */
    public void clear() {
        int cleared = runningTests.size();
        runningTests.clear();
        if (cleared > 0) {
            logger.log(Level.WARNING, "Cleared {0} running test entries", cleared);
        }
    }

    /**
     * Immutable info about a running test.
     */
    private static class RunningTestInfo {
        private final String testId;
        private final String testKey;
        private final Instant startedAt;
        private final PredictedDemand demand;

        RunningTestInfo(String testId, String testKey, Instant startedAt, PredictedDemand demand) {
            this.testId = testId;
            this.testKey = testKey;
            this.startedAt = startedAt;
            this.demand = demand;
        }

        public String getTestId() {
            return testId;
        }

        public String getTestKey() {
            return testKey;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public PredictedDemand getDemand() {
            return demand;
        }
    }
}
