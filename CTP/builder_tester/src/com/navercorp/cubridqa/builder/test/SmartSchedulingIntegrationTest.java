package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.Assignment;
import com.navercorp.cubridqa.builder.scheduler.NodeDirectory;
import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.scheduler.ReadyQueue;
import com.navercorp.cubridqa.builder.scheduler.ScoreFunction;
import com.navercorp.cubridqa.builder.scheduler.SchedulerService;
import com.navercorp.cubridqa.builder.scheduler.TestInstance;
import com.navercorp.cubridqa.builder.tester.stats.BuildContext;
import com.navercorp.cubridqa.builder.tester.stats.NodeHardware;
import com.navercorp.cubridqa.builder.tester.stats.PredictedDemand;
import com.navercorp.cubridqa.builder.tester.stats.Predictor;
import com.navercorp.cubridqa.builder.tester.stats.TestObservation;
import com.navercorp.cubridqa.builder.tester.stats.TestStats;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * High-level integration test that wires together the predictor, ready queue,
 * score function, and scheduler to ensure the smart scheduling flow behaves
 * as documented.
 */
public class SmartSchedulingIntegrationTest {

    private static final BuildContext CONTEXT = new BuildContext("commit-smart", "baseline-smart");
    private static final NodeHardware REFERENCE_HW = NodeHardware.builder()
        .cpuPct(800.0)
        .memMb(32_768.0)
        .ioMbPerSec(600.0)
        .iops(20_000.0)
        .netMbPerSec(125.0)
        .build();

    public static void main(String[] args) {
        System.out.println("=== SmartSchedulingIntegrationTest ===\n");

        testEndToEndSchedulingFlow();

        System.out.println("\n=== Smart scheduling integration test passed! ===");
    }

    private static void testEndToEndSchedulingFlow() {
        Predictor predictor = new Predictor();

        // Build historical stats for a fast mouse test
        TestStats mouseStats = new TestStats("shell/sql/mouse_fast.sh");
        for (int i = 0; i < 40; i++) {
            mouseStats.addObservation(observation("shell/sql/mouse_fast.sh", 8_000, 80.0, 1024.0, Instant.now().minusSeconds(120 - i)));
        }

        // Build stats for a heavy elephant test
        TestStats elephantStats = new TestStats("shell/sql/elephant_long.sh");
        for (int i = 0; i < 80; i++) {
            elephantStats.addObservation(observation("shell/sql/elephant_long.sh", 60_000, 220.0, 4_096.0, Instant.now().minusSeconds(200 - i)));
        }

        PredictedDemand mouseDemand = predictor.predict(mouseStats.getTestKey(), mouseStats, CONTEXT, REFERENCE_HW);
        PredictedDemand elephantDemand = predictor.predict(elephantStats.getTestKey(), elephantStats, CONTEXT, REFERENCE_HW);

        TestInstance mouseInstance = toTestInstance(
            mouseStats.getTestKey(),
            "cubrid:test-smart-mouse",
            "pkgMouse.tar.gz",
            mouseDemand,
            Instant.now().minusSeconds(30)
        );

        TestInstance elephantInstance = toTestInstance(
            elephantStats.getTestKey(),
            "cubrid:test-smart-elephant",
            "pkgElephant.tar.gz",
            elephantDemand,
            Instant.now().minusSeconds(10)
        );

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 120);
        directory.updateSnapshot("node-cache", nodeBuilder("node-cache")
            .addCachedImage("cubrid:test-smart-mouse")
            .addCachedPackage("pkgMouse.tar.gz")
            .cpuPct(500.0)
            .usedCpuPct(150.0)
            .memMb(32_768.0)
            .usedMemMb(10_000.0)
            .build());

        directory.updateSnapshot("node-power", nodeBuilder("node-power")
            .cpuPct(800.0)
            .usedCpuPct(200.0)
            .memMb(64_000.0)
            .usedMemMb(12_000.0)
            .build());

        ReadyQueue readyQueue = new ReadyQueue(20_000);
        SchedulerService scheduler = new SchedulerService(directory, new ScoreFunction(), readyQueue);

        scheduler.offer(Arrays.asList(mouseInstance, elephantInstance));
        assert readyQueue.getMiceCount() == 1 : "Mouse should be routed to mice queue";
        assert readyQueue.getElephantsCount() == 1 : "Elephant should be routed to elephant set";

        Assignment first = scheduler.assignNext().orElseThrow(() -> new AssertionError("Mouse assignment expected"));
        assert "shell/sql/mouse_fast.sh".equals(first.getTestKey()) : "Mouse should dispatch first";
        assert "node-cache".equals(first.getTargetNodeId()) : "Node with cached image/package should be chosen";

        Assignment second = scheduler.assignNext().orElseThrow(() -> new AssertionError("Elephant assignment expected"));
        assert "shell/sql/elephant_long.sh".equals(second.getTestKey()) : "Elephant should dispatch second";
        assert "node-power".equals(second.getTargetNodeId()) : "High-capacity node should be selected for elephant";
        assert readyQueue.isEmpty() : "Ready queue should be empty after assignments";

        System.out.println("  ✓ Mouse assigned to node-cache (cache hit)");
        System.out.println("  ✓ Elephant assigned to node-power (capacity advantage)");
    }

    private static TestObservation observation(String key, long durationMs, double cpuPct, double memMb, Instant timestamp) {
        return TestObservation.builder()
            .testKey(key)
            .commit("commit-smart")
            .baseline("baseline-smart")
            .executor("optimized-docker")
            .status("pass")
            .attempts(1)
            .durationMs(durationMs)
            .cpuPctMean(cpuPct)
            .cpuPctPeak(cpuPct * 1.2)
            .memMbMean(memMb)
            .memMbPeak(memMb * 1.3)
            .ioMbPerSecMean(15.0)
            .iopsMean(300.0)
            .netMbPerSecMean(6.0)
            .bytesReadMb(48)
            .bytesWriteMb(24)
            .dockerImageCached(true)
            .packageCached(true)
            .logSizeKb(80)
            .metricsComplete(true)
            .timestamp(timestamp)
            .build();
    }

    private static TestInstance toTestInstance(String testKey, String imageTag, String buildPackage,
                                               PredictedDemand demand, Instant submittedAt) {
        double ioMbPerSec = demand.getIoMbPerSec();
        // Split IO 50/50 for read/write (default behavior)
        double ioReadMbPerSec = ioMbPerSec / 2.0;
        double ioWriteMbPerSec = ioMbPerSec - ioReadMbPerSec;
        return TestInstance.builder()
            .testKey(testKey)
            .commit("commit-smart")
            .baseline("baseline-smart")
            .imageTag(imageTag)
            .buildPackage(buildPackage)
            .submittedAt(submittedAt)
            .predictedDurationMs(demand.getTpredMs())
            .predictedCpuPct(demand.getCpuPct())
            .predictedMemMb(demand.getMemMb())
            .predictedIoMbPerSec(ioMbPerSec)
            .predictedIoReadMbPerSec(ioReadMbPerSec)
            .predictedIoWriteMbPerSec(ioWriteMbPerSec)
            .predictedIops(demand.getIops())
            .predictedNetMbPerSec(demand.getNetMbPerSec())
            .confidence(demand.getConfidence())
            .build();
    }

    private static NodeSnapshot.Builder nodeBuilder(String nodeId) {
        return NodeSnapshot.builder()
            .nodeId(nodeId)
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(1)
            .queuedTests(0)
            .cpuPct(600.0)
            .usedCpuPct(200.0)
            .memMb(32_768.0)
            .usedMemMb(8_000.0)
            .ioMbPerSec(600.0)
            .usedIoMbPerSec(100.0)
            .iops(20_000.0)
            .usedIops(2_000.0)
            .netMbPerSec(125.0)
            .usedNetMbPerSec(20.0);
    }
}
