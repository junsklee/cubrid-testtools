package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.tester.stats.BuildContext;
import com.navercorp.cubridqa.builder.tester.stats.NodeHardware;
import com.navercorp.cubridqa.builder.tester.stats.PredictedDemand;
import com.navercorp.cubridqa.builder.tester.stats.Predictor;
import com.navercorp.cubridqa.builder.tester.stats.TestObservation;
import com.navercorp.cubridqa.builder.tester.stats.TestStats;

import java.time.Instant;

/**
 * Focused tests for {@link Predictor}.
 *
 * <p>Validates that the predictor consumes TestStats correctly, applies the confidence
 * curve, inflates low-confidence predictions, and falls back to bootstrap values when
 * no history exists.</p>
 */
public class PredictorTest {

    private static final String TEST_KEY = "shell/sql/predictor/test_case.sh";
    private static final BuildContext CONTEXT = new BuildContext("commit-a1b2c3d", "baseline-123");
    private static final NodeHardware HARDWARE = NodeHardware.builder()
        .cpuPct(800.0)
        .memMb(32768.0)
        .ioMbPerSec(500.0)
        .iops(20000.0)
        .netMbPerSec(125.0)
        .build();

    public static void main(String[] args) {
        System.out.println("=== PredictorTest Suite ===\n");

        testHighConfidenceUsesEwmaWithoutInflation();
        testLowConfidenceAppliesSafetyMargin();
        testBootstrapDefaultsForUnseenTest();

        System.out.println("\n=== All PredictorTest tests passed! ===");
    }

    private static void testHighConfidenceUsesEwmaWithoutInflation() {
        System.out.println("Test 1: High-confidence predictions follow EWMA");

        Predictor predictor = new Predictor();
        TestStats stats = new TestStats(TEST_KEY);

        // 60 identical observations → confidence ≈ 0.95 (>0.8 so no safety margin)
        for (int i = 0; i < 60; i++) {
            stats.addObservation(observation(TEST_KEY, 40_000, 120.0, 2048.0, Instant.now().minusSeconds(60 - i)));
        }

        PredictedDemand demand = predictor.predict(TEST_KEY, stats, CONTEXT, HARDWARE);

        assert Math.abs(demand.getTpredMs() - 40_000) <= 1 : "EWMA should be 40s without inflation";
        assert Math.abs(demand.getCpuPct() - 120.0) < 0.001 : "CPU mean should be preserved";
        assert Math.abs(demand.getMemMb() - 2048.0) < 0.001 : "Memory mean should be preserved";
        assert demand.getConfidence() > 0.9 : "Confidence should be high after 60 observations";

        System.out.println("  ✓ Duration=" + demand.getTpredMs() + " ms, CPU=" + demand.getCpuPct()
            + "%, mem=" + demand.getMemMb() + " MB, confidence=" + String.format("%.2f", demand.getConfidence()));
        System.out.println();
    }

    private static void testLowConfidenceAppliesSafetyMargin() {
        System.out.println("Test 2: Low-confidence predictions add safety margin");

        Predictor predictor = new Predictor();
        TestStats stats = new TestStats(TEST_KEY);

        stats.addObservation(observation(TEST_KEY, 10_000, 60.0, 1024.0, Instant.now().minusSeconds(10)));
        stats.addObservation(observation(TEST_KEY, 20_000, 60.0, 1024.0, Instant.now().minusSeconds(5)));

        // Expected EWMA after two observations (alpha=0.3): ~13,000 ms
        double baseEwma = stats.getDurationEwmaMs();
        double confidence = 1.0 - Math.exp(-stats.getObservationCount() / 20.0);
        double margin = 1.0 + 0.25 * (1.0 - confidence);
        long expectedDuration = Math.round(baseEwma * margin);

        PredictedDemand demand = predictor.predict(TEST_KEY, stats, CONTEXT, HARDWARE);
        assert demand.getTpredMs() == expectedDuration :
            "Duration should include safety margin (" + expectedDuration + "ms)";
        assert demand.getCpuPct() > 60.0 : "CPU should be inflated when confidence is low";
        assert demand.getConfidence() < 0.2 : "Confidence should be low with only two observations";

        System.out.println("  ✓ Base EWMA=" + Math.round(baseEwma) + " ms → Inflated=" + demand.getTpredMs() + " ms");
        System.out.println();
    }

    private static void testBootstrapDefaultsForUnseenTest() {
        System.out.println("Test 3: Bootstrap defaults for unseen tests");

        Predictor predictor = new Predictor();
        PredictedDemand demand = predictor.predict("shell/new/test.sh", null, CONTEXT, HARDWARE);

        assert demand.getTpredMs() == 30_000 : "Default duration should be 30s";
        assert demand.getCpuPct() == 50.0 : "Default CPU should be 50%";
        assert demand.getMemMb() == 512.0 : "Default memory should be 512 MB";
        assert demand.getConfidence() == 0.0 : "Confidence should be 0 for unseen tests";

        System.out.println("  ✓ Bootstrap prediction returned default values");
        System.out.println();
    }

    private static TestObservation observation(String testKey, long durationMs, double cpuPct, double memMb, Instant timestamp) {
        return TestObservation.builder()
            .testKey(testKey)
            .commit("commit-main")
            .baseline("baseline-main")
            .executor("optimized-docker")
            .status("pass")
            .attempts(1)
            .durationMs(durationMs)
            .cpuPctMean(cpuPct)
            .cpuPctPeak(cpuPct * 1.2)
            .memMbMean(memMb)
            .memMbPeak(memMb * 1.4)
            .ioMbPerSecMean(12.0)
            .iopsMean(250.0)
            .netMbPerSecMean(5.5)
            .bytesReadMb(32)
            .bytesWriteMb(16)
            .dockerImageCached(true)
            .packageCached(true)
            .logSizeKb(64)
            .metricsComplete(true)
            .timestamp(timestamp)
            .build();
    }
}
