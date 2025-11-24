package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.scheduler.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Tests for "Pending Until Placed" refactoring:
 * - Node cooldown after 409 responses
 * - Global backpressure detection
 * - Test requeueing on 409
 * - Attempt-based aging boost
 * - Unschedulable detection
 */
public class PendingUntilPlacedTest {

    public static void main(String[] args) {
        System.out.println("=== Pending Until Placed Test Suite ===\n");

        testNodeCooldownAfter409();
        testNodeInCooldownExcludedFromScheduling();
        testGlobalBackpressureDetection();
        testHandle409RequeuesTest();
        testAttemptBoostInAging();
        testUnschedulableDetection();
        test409Integration();

        System.out.println("\n=== All Pending Until Placed tests passed! ===");
    }

    private static void testNodeCooldownAfter409() {
        System.out.println("Test 1: Node cooldown after 409");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60, mockConfig());
        NodeSnapshot snapshot = healthySnapshot("node-1");
        directory.updateSnapshot("node-1", snapshot);

        // Verify node is not in cooldown initially
        NodeSnapshot current = directory.getSnapshot("node-1");
        assert !current.isInCooldown() : "Node should not be in cooldown initially";

        // Trigger 409
        directory.onCapacity409("node-1");

        // Verify node is now in cooldown
        NodeSnapshot afterCooldown = directory.getSnapshot("node-1");
        assert afterCooldown.isInCooldown() : "Node should be in cooldown after 409";

        System.out.println("  ✓ Node enters cooldown after 409");

        // Wait for cooldown to expire
        try {
            Thread.sleep(1100); // Wait 1.1s (cooldown is 1s in mock config)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify cooldown expired
        NodeSnapshot afterExpiry = directory.getSnapshot("node-1");
        assert !afterExpiry.isInCooldown() : "Node cooldown should have expired";

        System.out.println("  ✓ Node cooldown expires after timeout");
        System.out.println();
    }

    private static void testNodeInCooldownExcludedFromScheduling() {
        System.out.println("Test 2: Node in cooldown excluded from scheduling");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60, mockConfig());
        NodeSnapshot snapshot = healthySnapshot("node-1");
        directory.updateSnapshot("node-1", snapshot);

        TestInstance test = baseTest("test.sh").build();

        // Node should be eligible initially
        List<NodeSnapshot> eligibleBefore = directory.getEligibleNodes(test);
        assert eligibleBefore.size() == 1 : "Node should be eligible initially";

        // Put node on cooldown
        directory.onCapacity409("node-1");

        // Node should no longer be eligible
        List<NodeSnapshot> eligibleAfter = directory.getEligibleNodes(test);
        assert eligibleAfter.size() == 0 : "Node in cooldown should not be eligible";

        System.out.println("  ✓ Node in cooldown excluded from getEligibleNodes()");
        System.out.println();
    }

    private static void testGlobalBackpressureDetection() {
        System.out.println("Test 3: Global backpressure detection");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60, mockConfig());

        // Create a saturated node (no headroom)
        NodeSnapshot saturated = healthySnapshotBuilder("node-1")
            .runningTests(10)
            .maxConcurrentTests(10)
            .usedCpuPct(800.0)  // 800% CPU used
            .cpuPct(800.0)      // 800% CPU capacity
            .build();
        directory.updateSnapshot("node-1", saturated);

        // Create a test that needs resources
        TestInstance test = baseTest("test.sh")
            .predictedCpuPct(50.0)
            .build();

        // hasAnyNodeHeadroomFor should return false
        boolean hasHeadroom = directory.hasAnyNodeHeadroomFor(test);
        assert !hasHeadroom : "Should detect no headroom when cluster is saturated";

        System.out.println("  ✓ Global backpressure detected when cluster is full");

        // Add a node with headroom
        NodeSnapshot available = healthySnapshot("node-2");
        directory.updateSnapshot("node-2", available);

        // Now hasAnyNodeHeadroomFor should return true
        boolean hasHeadroomNow = directory.hasAnyNodeHeadroomFor(test);
        assert hasHeadroomNow : "Should detect headroom when node is available";

        System.out.println("  ✓ Headroom detected when node becomes available");
        System.out.println();
    }

    private static void testHandle409RequeuesTest() {
        System.out.println("Test 4: handle409() requeues test");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60, mockConfig());
        directory.updateSnapshot("node-1", healthySnapshot("node-1"));

        ReadyQueue queue = new ReadyQueue();
        SchedulerService scheduler = new SchedulerService(directory, new ScoreFunction(), queue, 0.8, mockConfig());

        TestInstance test = baseTest("test.sh").build();
        scheduler.offer(Collections.singletonList(test));

        // Assign test
        Optional<Assignment> assignment = scheduler.assignNext();
        assert assignment.isPresent() : "Test should be assigned";
        assert !scheduler.hasPending() : "Queue should be empty after assignment";

        // Simulate 409
        scheduler.handle409(test, "node-1");

        // Test should be back in queue
        assert scheduler.hasPending() : "Test should be requeued after 409";
        assert test.getScheduleAttemptCount() == 1 : "Schedule attempt count should be incremented";

        // Node should be in cooldown
        NodeSnapshot node = directory.getSnapshot("node-1");
        assert node.isInCooldown() : "Node should be in cooldown after 409";

        System.out.println("  ✓ Test requeued after 409");
        System.out.println("  ✓ Schedule attempt count incremented");
        System.out.println("  ✓ Node placed on cooldown");
        System.out.println();
    }

    private static void testAttemptBoostInAging() {
        System.out.println("Test 5: Attempt boost in aging priority");

        ReadyQueue queue = new ReadyQueue();

        // Create two mice tests with same duration
        TestInstance test1 = baseTest("test1.sh")
            .predictedDurationMs(10_000)
            .submittedAt(Instant.now().minusSeconds(30))
            .build();

        TestInstance test2 = baseTest("test2.sh")
            .predictedDurationMs(10_000)
            .submittedAt(Instant.now().minusSeconds(30))
            .build();

        // Simulate test2 having failed scheduling attempts (409s)
        for (int i = 0; i < 5; i++) {
            test2.incrementScheduleAttemptCount();
        }

        // Add to queue
        queue.offer(test1);
        queue.offer(test2);

        // test2 should come out first due to attempt boost
        TestInstance first = queue.pollMouse();
        assert first != null : "Should poll a test";
        assert "test2.sh".equals(first.getTestKey()) : "Test with more attempts should have priority";

        System.out.println("  ✓ Test with more failed attempts gets priority boost");
        System.out.println();
    }

    private static void testUnschedulableDetection() {
        System.out.println("Test 6: Unschedulable detection");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60, mockConfig());
        // Create node with limited capacity
        NodeSnapshot smallNode = healthySnapshotBuilder("node-1")
            .cpuPct(100.0)  // Only 1 CPU core
            .memMb(1024.0)  // Only 1GB RAM
            .build();
        directory.updateSnapshot("node-1", smallNode);

        ReadyQueue queue = new ReadyQueue();
        SchedulerService scheduler = new SchedulerService(directory, new ScoreFunction(), queue, 0.8, mockConfig());

        // Create test that's too big for any node
        TestInstance tooLargeTest = baseTest("huge_test.sh")
            .predictedCpuPct(500.0)  // Needs 5 cores
            .predictedMemMb(10000.0) // Needs 10GB RAM
            .submittedAt(Instant.now().minus(Duration.ofMinutes(15))) // Been waiting 15 minutes
            .build();

        scheduler.offer(Collections.singletonList(tooLargeTest));

        // Trigger multiple "no headroom" rounds
        for (int i = 0; i < 12; i++) {
            tooLargeTest.incrementNoHeadroomRounds();
        }

        // Try to assign - should fail as unschedulable
        Optional<Assignment> assignment = scheduler.assignNext();
        assert !assignment.isPresent() : "Should not assign unschedulable test";
        assert !scheduler.hasPending() : "Unschedulable test should be removed from queue";

        System.out.println("  ✓ Test marked as unschedulable after threshold");
        System.out.println("  ✓ Unschedulable test removed from queue");
        System.out.println();
    }

    private static void test409Integration() {
        System.out.println("Test 7: Full 409 integration flow");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60, mockConfig());
        directory.updateSnapshot("node-1", healthySnapshot("node-1"));

        ReadyQueue queue = new ReadyQueue();
        SchedulerService scheduler = new SchedulerService(directory, new ScoreFunction(), queue, 0.8, mockConfig());

        TestInstance test = baseTest("test.sh").build();
        scheduler.offer(Collections.singletonList(test));

        // Round 1: Assign test
        Optional<Assignment> assignment1 = scheduler.assignNext();
        assert assignment1.isPresent() && assignment1.get().getTargetNodeId().equals("node-1") : "Should assign to node-1";

        // Simulate 409
        scheduler.handle409(test, "node-1");

        // Round 2: Try to assign again - node-1 should be in cooldown
        Optional<Assignment> assignment2 = scheduler.assignNext();
        assert !assignment2.isPresent() : "Should not assign while node in cooldown";

        // Wait for cooldown to expire
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Round 3: After cooldown, should successfully assign
        Optional<Assignment> assignment3 = scheduler.assignNext();
        assert assignment3.isPresent() : "Should assign after cooldown expires";
        assert test.getScheduleAttemptCount() == 1 : "Should have 1 attempt recorded";

        System.out.println("  ✓ Complete 409 flow works correctly");
        System.out.println("  ✓ Node cooldown prevents immediate retry");
        System.out.println("  ✓ Test successfully placed after cooldown expires");
        System.out.println();
    }

    // Helper methods

    private static NodeSnapshot healthySnapshot(String nodeId) {
        return healthySnapshotBuilder(nodeId).build();
    }

    private static NodeSnapshot.Builder healthySnapshotBuilder(String nodeId) {
        return NodeSnapshot.builder()
            .nodeId(nodeId)
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(10)
            .maxWhileHeavy(5)
            .maxAfterHeavy(10)
            .activeLimit(10)
            .heavyRunning(0)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(800.0)  // 8 cores
            .memMb(16384.0) // 16GB
            .ioMbPerSec(200.0)
            .ioReadMbPerSec(200.0)
            .ioWriteMbPerSec(200.0)
            .iops(10000.0)
            .netMbPerSec(1000.0)
            .usedCpuPct(0.0)
            .usedMemMb(0.0)
            .usedIoMbPerSec(0.0)
            .usedIoReadMbPerSec(0.0)
            .usedIoWriteMbPerSec(0.0)
            .usedIops(0.0)
            .usedNetMbPerSec(0.0)
            .degraded(false)
            .diskPressure(false);
    }

    private static TestInstance.Builder baseTest(String testKey) {
        return TestInstance.builder()
            .testKey(testKey)
            .commit("abc123")
            .baseline("def456")
            .buildPackage("/tmp/build.tar.gz")
            .imageTag("test:latest")
            .submittedAt(Instant.now())
            .predictedDurationMs(30_000)
            .predictedCpuPct(50.0)
            .predictedMemMb(512.0)
            .predictedIoMbPerSec(10.0)
            .predictedIoReadMbPerSec(10.0)
            .predictedIoWriteMbPerSec(10.0)
            .predictedIops(100.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.9);
    }

    private static BuilderConfig mockConfig() {
        try {
            // Create a minimal config that returns test-friendly values
            BuilderConfig config = new BuilderConfig("conf/builder.conf") {
                @Override
                public int getRetryNodeCooldownSeconds() {
                    return 1; // 1 second for fast tests
                }

                @Override
                public int getUnschedMaxRounds() {
                    return 10;
                }

                @Override
                public int getUnschedMaxWallclockMinutes() {
                    return 10;
                }
            };
            return config;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create mock config", e);
        }
    }
}
