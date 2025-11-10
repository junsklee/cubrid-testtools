package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.Assignment;
import com.navercorp.cubridqa.builder.scheduler.NodeDirectory;
import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.scheduler.ReadyQueue;
import com.navercorp.cubridqa.builder.scheduler.ScoreFunction;
import com.navercorp.cubridqa.builder.scheduler.SchedulerService;
import com.navercorp.cubridqa.builder.scheduler.TestInstance;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Tests the {@link SchedulerService} using the real ReadyQueue and ScoreFunction components.
 */
public class SchedulerServiceTest {

    public static void main(String[] args) {
        System.out.println("=== SchedulerServiceTest Suite ===\n");

        testMiceAreScheduledBeforeElephants();
        testMouseRequeuedWhenNoEligibleNodes();
        testElephantsChooseBestNode();

        System.out.println("\n=== All SchedulerServiceTest tests passed! ===");
    }

    private static void testMiceAreScheduledBeforeElephants() {
        System.out.println("Test 1: Mice prioritized over elephants");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60);
        directory.updateSnapshot("node-a", healthySnapshot("node-a"));

        ReadyQueue queue = new ReadyQueue(20_000);
        SchedulerService scheduler = new SchedulerService(directory, new ScoreFunction(), queue);

        TestInstance mouse = baseTest("shell/sql/mouse.sh")
            .predictedDurationMs(8_000)
            .build();

        TestInstance elephant = baseTest("shell/sql/elephant.sh")
            .predictedDurationMs(50_000)
            .predictedCpuPct(200.0)
            .build();

        scheduler.offer(Arrays.asList(elephant, mouse)); // Offer out of desired order

        Optional<Assignment> first = scheduler.assignNext();
        assert first.isPresent() : "Assignment should exist";
        assert "shell/sql/mouse.sh".equals(first.get().getTestKey()) : "Mouse should run before elephant";
        assert scheduler.getPendingCount() == 1 : "One elephant should remain queued";

        Optional<Assignment> second = scheduler.assignNext();
        assert second.isPresent() : "Elephant should be scheduled next";
        assert "shell/sql/elephant.sh".equals(second.get().getTestKey()) : "Elephant should be assigned second";
        assert scheduler.getPendingCount() == 0 : "Queue should be empty after assigning both tests";

        System.out.println("  ✓ Mouse assigned first, elephant second");
        System.out.println();
    }

    private static void testMouseRequeuedWhenNoEligibleNodes() {
        System.out.println("Test 2: Mouse re-queued when no eligible nodes are available");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60);

        NodeSnapshot saturated = healthySnapshotBuilder("node-busy")
            .runningTests(4)
            .maxConcurrentTests(4)
            .build();

        directory.updateSnapshot("node-busy", saturated);

        ReadyQueue queue = new ReadyQueue();
        SchedulerService scheduler = new SchedulerService(directory, new ScoreFunction(), queue);

        TestInstance mouse = baseTest("shell/sql/mouse_retry.sh")
            .predictedDurationMs(5_000)
            .build();

        scheduler.offer(Collections.singletonList(mouse));

        Optional<Assignment> assignment = scheduler.assignNext();
        assert !assignment.isPresent() : "No nodes should result in empty assignment";
        assert scheduler.getPendingCount() == 1 : "Mouse should have been re-queued";

        System.out.println("  ✓ Mouse re-queued when no headroom detected");
        System.out.println();
    }

    private static void testElephantsChooseBestNode() {
        System.out.println("Test 3: Elephant scheduling chooses best node");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60);

        NodeSnapshot constrained = healthySnapshotBuilder("node-tight")
            .cpuPct(400.0)
            .usedCpuPct(360.0)  // free 40
            .build();

        NodeSnapshot roomy = healthySnapshotBuilder("node-roomy")
            .cpuPct(400.0)
            .usedCpuPct(120.0)  // free 280
            .build();

        directory.updateSnapshot("node-tight", constrained);
        directory.updateSnapshot("node-roomy", roomy);

        ReadyQueue queue = new ReadyQueue();
        SchedulerService scheduler = new SchedulerService(directory, new ScoreFunction(), queue);

        TestInstance elephant = baseTest("shell/sql/heavy.sh")
            .predictedDurationMs(60_000)
            .predictedCpuPct(250.0)
            .predictedMemMb(2048.0)
            .build();

        scheduler.offer(Collections.singletonList(elephant));

        Optional<Assignment> assignment = scheduler.assignNext();
        assert assignment.isPresent() : "Elephant should be scheduled";
        assert "node-roomy".equals(assignment.get().getTargetNodeId()) :
            "Node with more free CPU should be selected (node-roomy preferred)";

        System.out.println("  ✓ Elephant assigned to node-roomy");
        System.out.println();
    }

    private static TestInstance.Builder baseTest(String testKey) {
        return TestInstance.builder()
            .testKey(testKey)
            .commit("commit-abcdef")
            .baseline("baseline-main")
            .submittedAt(Instant.now().minusSeconds(5))
            .predictedDurationMs(10_000)
            .predictedCpuPct(100.0)
            .predictedMemMb(1024.0)
            .predictedIoMbPerSec(10.0)
            .predictedIops(150.0)
            .predictedNetMbPerSec(5.0);
    }

    private static NodeSnapshot healthySnapshot(String nodeId) {
        return healthySnapshotBuilder(nodeId).build();
    }

    private static NodeSnapshot.Builder healthySnapshotBuilder(String nodeId) {
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
