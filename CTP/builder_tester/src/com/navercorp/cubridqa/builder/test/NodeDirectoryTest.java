package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.NodeDirectory;
import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.scheduler.TestInstance;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link NodeDirectory} covering health/staleness filtering and
 * concurrency eligibility heuristics.
 */
public class NodeDirectoryTest {

    public static void main(String[] args) {
        System.out.println("=== NodeDirectoryTest Suite ===\n");

        testHealthyNodeFiltering();
        testDiskPressureFallback();
        testEligibleNodesRequireHeadroom();
        testStaleSnapshotDiscardedOnLookup();

        System.out.println("\n=== All NodeDirectoryTest tests passed! ===");
    }

    private static void testHealthyNodeFiltering() {
        System.out.println("Test 1: Healthy node filtering");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 2);

        NodeSnapshot healthy = baseSnapshot("node-healthy")
            .status("healthy")
            .timestamp(Instant.now())
            .runningTests(1)
            .maxConcurrentTests(4)
            .build();

        NodeSnapshot stale = baseSnapshot("node-stale")
            .status("healthy")
            .timestamp(Instant.now().minusSeconds(10))
            .runningTests(0)
            .maxConcurrentTests(2)
            .build();

        NodeSnapshot degraded = baseSnapshot("node-degraded")
            .status("healthy")
            .degraded(true)
            .runningTests(0)
            .maxConcurrentTests(2)
            .build();

        NodeSnapshot diskPressure = baseSnapshot("node-disk")
            .status("healthy")
            .diskPressure(true)
            .runningTests(0)
            .maxConcurrentTests(2)
            .build();

        directory.updateSnapshot(healthy.getNodeId(), healthy);
        directory.updateSnapshot(stale.getNodeId(), stale);
        directory.updateSnapshot(degraded.getNodeId(), degraded);
        directory.updateSnapshot(diskPressure.getNodeId(), diskPressure);

        List<NodeSnapshot> healthyNodes = directory.getHealthyNodes();
        assert healthyNodes.size() == 1 : "Only one node should be considered healthy";
        assert "node-healthy".equals(healthyNodes.get(0).getNodeId()) : "Healthy node should remain available";

        System.out.println("  ✓ Healthy node retained, stale/degraded/disk nodes filtered out");
        System.out.println();
    }

    private static void testDiskPressureFallback() {
        System.out.println("Test 1b: Disk pressure fallback when all nodes are pressured");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 2);

        NodeSnapshot diskOnly = baseSnapshot("node-disk-only")
            .status("healthy")
            .diskPressure(true)
            .timestamp(Instant.now())
            .runningTests(0)
            .maxConcurrentTests(2)
            .build();

        directory.updateSnapshot(diskOnly.getNodeId(), diskOnly);

        List<NodeSnapshot> healthyNodes = directory.getHealthyNodes();
        assert healthyNodes.size() == 1 : "Disk-pressure-only cluster should still be considered available";
        assert "node-disk-only".equals(healthyNodes.get(0).getNodeId()) : "Disk-pressure node should be returned when it is the only option";

        System.out.println("  ✓ Disk-pressure-only node returned to avoid starvation");
        System.out.println();
    }

    private static void testEligibleNodesRequireHeadroom() {
        System.out.println("Test 2: Eligible nodes must have available concurrency");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60);

        NodeSnapshot saturated = baseSnapshot("node-busy")
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(4)
            .timestamp(Instant.now())
            .build();

        NodeSnapshot available = baseSnapshot("node-idle")
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(1)
            .timestamp(Instant.now())
            .build();

        directory.updateSnapshot(saturated.getNodeId(), saturated);
        directory.updateSnapshot(available.getNodeId(), available);

        TestInstance test = TestInstance.builder()
            .testKey("shell/sql/basic/test_concurrency.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedDurationMs(12_000)
            .build();

        List<NodeSnapshot> eligible = directory.getEligibleNodes(test, false);
        assert eligible.size() == 1 : "Only nodes with headroom should be eligible";
        assert "node-idle".equals(eligible.get(0).getNodeId()) : "Idle node should be selected";

        System.out.println("  ✓ Saturated nodes excluded from eligibility");
        System.out.println();
    }

    private static void testStaleSnapshotDiscardedOnLookup() {
        System.out.println("Test 3: Stale snapshot discarded upon lookup");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 1);

        NodeSnapshot oldSnapshot = baseSnapshot("node-old")
            .timestamp(Instant.now().minusSeconds(30))
            .status("healthy")
            .build();

        directory.updateSnapshot(oldSnapshot.getNodeId(), oldSnapshot);
        NodeSnapshot lookup = directory.getSnapshot("node-old");

        assert lookup == null : "Stale snapshot should not be returned";
        assert directory.getNodeCount() == 0 : "Stale snapshot should be removed from directory";

        System.out.println("  ✓ Stale snapshot purged via getSnapshot()");
        System.out.println();
    }

    private static NodeSnapshot.Builder baseSnapshot(String nodeId) {
        return NodeSnapshot.builder()
            .nodeId(nodeId)
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(2)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(400.0)
            .memMb(32_768.0)
            .ioMbPerSec(500.0)
            .ioReadMbPerSec(250.0)  // Split 50/50
            .ioWriteMbPerSec(250.0)
            .iops(20_000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(100.0)
            .usedMemMb(8_000.0)
            .usedIoMbPerSec(100.0)
            .usedIoReadMbPerSec(50.0)  // Split 50/50
            .usedIoWriteMbPerSec(50.0)
            .usedIops(2_000.0)
            .usedNetMbPerSec(20.0);
    }
}
