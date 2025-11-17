package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.Assignment;
import com.navercorp.cubridqa.builder.scheduler.NodeDirectory;
import com.navercorp.cubridqa.builder.scheduler.NodeMetrics;
import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.scheduler.ReadyQueue;
import com.navercorp.cubridqa.builder.scheduler.ScoreFunction;
import com.navercorp.cubridqa.builder.scheduler.SchedulerService;
import com.navercorp.cubridqa.builder.scheduler.TestInstance;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

/**
 * Verifies pull-based assignment picks IO-light work when the node reports IO pressure.
 */
public class SchedulerServicePullTest {

    public static void main(String[] args) {
        System.out.println("=== SchedulerServicePullTest ===");
        testPullPrefersIoLightWhenIoIsTight();
        System.out.println("=== SchedulerServicePullTest passed ===");
    }

    private static void testPullPrefersIoLightWhenIoIsTight() {
        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 120);
        directory.updateSnapshot("node-pull", baseSnapshot());

        ReadyQueue queue = new ReadyQueue(20_000);
        queue.offer(ioHeavy());
        queue.offer(ioLight());

        SchedulerService scheduler = new SchedulerService(directory, new ScoreFunction(), queue);

        NodeMetrics metrics = NodeMetrics.builder()
                .cpuUsedPct(50.0)
                .memUsedMb(2048.0)
                .ioReadUsedMbPerSec(70.0)
                .ioWriteUsedMbPerSec(70.0)
                .iopsUsed(0.0)
                .netUsedMbPerSec(0.0)
                .runningTests(1)
                .queuedTests(0)
                .build();

        Optional<Assignment> assignment = scheduler.assignForNode("node-pull", metrics);
        if (!assignment.isPresent()) {
            throw new AssertionError("Expected an assignment but got empty");
        }

        if (!"io-light".equals(assignment.get().getTestKey())) {
            throw new AssertionError("IO-light test should be selected when IO is tight");
        }

        if (queue.size() != 1) {
            throw new AssertionError("Exactly one test should remain after assignment");
        }
    }

    private static NodeSnapshot baseSnapshot() {
        return NodeSnapshot.builder()
                .nodeId("node-pull")
                .timestamp(Instant.now())
                .status("healthy")
                .maxConcurrentTests(2)
                .runningTests(1)
                .queuedTests(0)
                .cpuPct(1000.0)
                .usedCpuPct(100.0)
                .memMb(16_384.0)
                .usedMemMb(1_024.0)
                .ioMbPerSec(400.0)
                .ioReadMbPerSec(200.0)
                .ioWriteMbPerSec(200.0)
                .usedIoMbPerSec(0.0)
                .usedIoReadMbPerSec(0.0)
                .usedIoWriteMbPerSec(0.0)
                .iops(10_000.0)
                .usedIops(0.0)
                .netMbPerSec(1_000.0)
                .usedNetMbPerSec(0.0)
                .build();
    }

    private static TestInstance ioHeavy() {
        return TestInstance.builder()
                .testKey("io-heavy")
                .commit("c-heavy")
                .baseline("b-base")
                .predictedDurationMs(60_000)
                .predictedCpuPct(50.0)
                .predictedMemMb(1_024.0)
                .predictedIoReadMbPerSec(80.0)
                .predictedIoWriteMbPerSec(80.0)
                .predictedIops(500.0)
                .predictedNetMbPerSec(10.0)
                .confidence(1.0)
                .build();
    }

    private static TestInstance ioLight() {
        return TestInstance.builder()
                .testKey("io-light")
                .commit("c-light")
                .baseline("b-base")
                .predictedDurationMs(45_000)
                .predictedCpuPct(30.0)
                .predictedMemMb(512.0)
                .predictedIoReadMbPerSec(10.0)
                .predictedIoWriteMbPerSec(10.0)
                .predictedIops(200.0)
                .predictedNetMbPerSec(5.0)
                .confidence(1.0)
                .build();
    }
}
