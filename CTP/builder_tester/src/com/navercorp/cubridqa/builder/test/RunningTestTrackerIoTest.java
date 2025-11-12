package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.tester.demand.PredictedDemand;
import com.navercorp.cubridqa.builder.tester.demand.RunningTestTracker;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;
import org.json.JSONObject;

/**
 * Tests for {@link RunningTestTracker} read/write I/O tracking.
 */
public class RunningTestTrackerIoTest {

    public static void main(String[] args) {
        System.out.println("=== RunningTestTrackerIoTest Suite ===\n");

        testTrackReadWriteSeparately();
        testUpdatePhaseChangesReadWrite();
        testUnregisterReleasesReadWrite();
        testUtilizationSnapshotIncludesReadWrite();
        testMultipleTestsAggregateReadWrite();

        System.out.println("\n=== All RunningTestTrackerIoTest tests passed! ===");
    }

    private static void testTrackReadWriteSeparately() {
        System.out.println("Test 1: Read/write I/O tracked separately");

        RunningTestTracker tracker = new RunningTestTracker();

        JSONObject json = new JSONObject()
            .put("durationMs", 30000)
            .put("cpuMillicores", 500)
            .put("memBytes", 512 * 1024 * 1024)
            .put("ioReadBytesPerSec", 20 * 1024 * 1024)  // 20 MB/s read
            .put("ioWriteBytesPerSec", 30 * 1024 * 1024)  // 30 MB/s write
            .put("iops", 200)
            .put("netBytesPerSec", 5 * 1024 * 1024)
            .put("confidence", 0.8);

        PredictedDemand demand = PredictedDemand.fromRequest(json, null);

        tracker.admit("test-1", "shell/sql/test1.sh", demand);
        tracker.startRunning("test-1");

        UtilizationSnapshot snapshot = tracker.getCurrentUtilization();

        assert snapshot.getTotalIoReadBytesPerSec() == 20 * 1024 * 1024 :
            "Total read should be 20 MB/s, got " + (snapshot.getTotalIoReadBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert snapshot.getTotalIoWriteBytesPerSec() == 30 * 1024 * 1024 :
            "Total write should be 30 MB/s, got " + (snapshot.getTotalIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert snapshot.getTotalIoBytesPerSec() == 50 * 1024 * 1024 :
            "Total IO should be 50 MB/s, got " + (snapshot.getTotalIoBytesPerSec() / (1024 * 1024)) + " MB/s";

        System.out.println("  ✓ Read: " + (snapshot.getTotalIoReadBytesPerSec() / (1024 * 1024)) + " MB/s, " +
            "Write: " + (snapshot.getTotalIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s");
        System.out.println();
    }

    private static void testUpdatePhaseChangesReadWrite() {
        System.out.println("Test 2: Phase update changes read/write tracking");

        RunningTestTracker tracker = new RunningTestTracker();

        JSONObject json = new JSONObject()
            .put("durationMs", 60000)
            .put("cpuMillicores", 500)
            .put("memBytes", 512 * 1024 * 1024)
            .put("ioReadBytesPerSec", 10 * 1024 * 1024)
            .put("ioWriteBytesPerSec", 20 * 1024 * 1024)
            .put("iops", 200)
            .put("netBytesPerSec", 5 * 1024 * 1024)
            .put("confidence", 0.8);

        JSONObject phase1 = new JSONObject()
            .put("name", "setup")
            .put("durationMs", 20000)
            .put("cpuMillicores", 300)
            .put("memBytes", 256 * 1024 * 1024)
            .put("ioReadBytesPerSec", 5 * 1024 * 1024)
            .put("ioWriteBytesPerSec", 10 * 1024 * 1024)
            .put("iops", 100)
            .put("netBytesPerSec", 2 * 1024 * 1024);

        JSONObject phase2 = new JSONObject()
            .put("name", "run")
            .put("durationMs", 40000)
            .put("cpuMillicores", 700)
            .put("memBytes", 768 * 1024 * 1024)
            .put("ioReadBytesPerSec", 15 * 1024 * 1024)
            .put("ioWriteBytesPerSec", 30 * 1024 * 1024)
            .put("iops", 300)
            .put("netBytesPerSec", 8 * 1024 * 1024);

        json.put("phases", new org.json.JSONArray().put(phase1).put(phase2));

        PredictedDemand demand = PredictedDemand.fromRequest(json, null);

        tracker.admit("test-1", "shell/sql/test1.sh", demand);
        tracker.startRunning("test-1");

        // Initially should use phase 1 (or default if no phase set)
        UtilizationSnapshot snapshot1 = tracker.getCurrentUtilization();
        long initialRead = snapshot1.getTotalIoReadBytesPerSec();
        long initialWrite = snapshot1.getTotalIoWriteBytesPerSec();

        // Update to phase 2
        tracker.updatePhase("test-1", demand.getPhases().get(1));

        UtilizationSnapshot snapshot2 = tracker.getCurrentUtilization();
        assert snapshot2.getTotalIoReadBytesPerSec() == 15 * 1024 * 1024 :
            "After phase update, read should be 15 MB/s";
        assert snapshot2.getTotalIoWriteBytesPerSec() == 30 * 1024 * 1024 :
            "After phase update, write should be 30 MB/s";

        System.out.println("  ✓ Phase update changed read/write tracking");
        System.out.println();
    }

    private static void testUnregisterReleasesReadWrite() {
        System.out.println("Test 3: Unregister releases read/write reservations");

        RunningTestTracker tracker = new RunningTestTracker();

        JSONObject json = new JSONObject()
            .put("durationMs", 30000)
            .put("cpuMillicores", 500)
            .put("memBytes", 512 * 1024 * 1024)
            .put("ioReadBytesPerSec", 25 * 1024 * 1024)
            .put("ioWriteBytesPerSec", 35 * 1024 * 1024)
            .put("iops", 200)
            .put("netBytesPerSec", 5 * 1024 * 1024)
            .put("confidence", 0.8);

        PredictedDemand demand = PredictedDemand.fromRequest(json, null);

        tracker.admit("test-1", "shell/sql/test1.sh", demand);
        tracker.startRunning("test-1");

        UtilizationSnapshot before = tracker.getCurrentUtilization();
        assert before.getTotalIoReadBytesPerSec() > 0 : "Should have read IO reserved";
        assert before.getTotalIoWriteBytesPerSec() > 0 : "Should have write IO reserved";

        tracker.unregister("test-1");

        UtilizationSnapshot after = tracker.getCurrentUtilization();
        assert after.getTotalIoReadBytesPerSec() == 0 : "Read IO should be released";
        assert after.getTotalIoWriteBytesPerSec() == 0 : "Write IO should be released";
        assert after.getTestCount() == 0 : "Test count should be 0";

        System.out.println("  ✓ Unregister released read/write reservations");
        System.out.println();
    }

    private static void testUtilizationSnapshotIncludesReadWrite() {
        System.out.println("Test 4: UtilizationSnapshot includes read/write totals");

        RunningTestTracker tracker = new RunningTestTracker();

        JSONObject json1 = new JSONObject()
            .put("durationMs", 30000)
            .put("cpuMillicores", 500)
            .put("memBytes", 512 * 1024 * 1024)
            .put("ioReadBytesPerSec", 10 * 1024 * 1024)
            .put("ioWriteBytesPerSec", 15 * 1024 * 1024)
            .put("iops", 200)
            .put("netBytesPerSec", 5 * 1024 * 1024)
            .put("confidence", 0.8);

        JSONObject json2 = new JSONObject()
            .put("durationMs", 30000)
            .put("cpuMillicores", 300)
            .put("memBytes", 256 * 1024 * 1024)
            .put("ioReadBytesPerSec", 20 * 1024 * 1024)
            .put("ioWriteBytesPerSec", 25 * 1024 * 1024)
            .put("iops", 150)
            .put("netBytesPerSec", 3 * 1024 * 1024)
            .put("confidence", 0.7);

        PredictedDemand demand1 = PredictedDemand.fromRequest(json1, null);
        PredictedDemand demand2 = PredictedDemand.fromRequest(json2, null);

        tracker.admit("test-1", "shell/sql/test1.sh", demand1);
        tracker.startRunning("test-1");
        tracker.admit("test-2", "shell/sql/test2.sh", demand2);
        tracker.startRunning("test-2");

        UtilizationSnapshot snapshot = tracker.getCurrentUtilization();

        long expectedRead = 30 * 1024 * 1024;  // 10 + 20 MB/s
        long expectedWrite = 40 * 1024 * 1024; // 15 + 25 MB/s

        assert snapshot.getTotalIoReadBytesPerSec() == expectedRead :
            "Total read should be 30 MB/s, got " + (snapshot.getTotalIoReadBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert snapshot.getTotalIoWriteBytesPerSec() == expectedWrite :
            "Total write should be 40 MB/s, got " + (snapshot.getTotalIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert snapshot.getTestCount() == 2 : "Should have 2 tests";

        System.out.println("  ✓ Snapshot aggregates read/write across tests");
        System.out.println();
    }

    private static void testMultipleTestsAggregateReadWrite() {
        System.out.println("Test 5: Multiple tests aggregate read/write correctly");

        RunningTestTracker tracker = new RunningTestTracker();

        // Add 3 tests with different read/write patterns
        for (int i = 1; i <= 3; i++) {
            JSONObject json = new JSONObject()
                .put("durationMs", 30000)
                .put("cpuMillicores", 500)
                .put("memBytes", 512 * 1024 * 1024)
                .put("ioReadBytesPerSec", i * 5 * 1024 * 1024)  // 5, 10, 15 MB/s
                .put("ioWriteBytesPerSec", i * 10 * 1024 * 1024) // 10, 20, 30 MB/s
                .put("iops", 200)
                .put("netBytesPerSec", 5 * 1024 * 1024)
                .put("confidence", 0.8);

            PredictedDemand demand = PredictedDemand.fromRequest(json, null);
            tracker.admit("test-" + i, "shell/sql/test" + i + ".sh", demand);
            tracker.startRunning("test-" + i);
        }

        UtilizationSnapshot snapshot = tracker.getCurrentUtilization();

        long expectedRead = 30 * 1024 * 1024;  // 5 + 10 + 15 MB/s
        long expectedWrite = 60 * 1024 * 1024; // 10 + 20 + 30 MB/s

        assert snapshot.getTotalIoReadBytesPerSec() == expectedRead :
            "Total read should be 30 MB/s, got " + (snapshot.getTotalIoReadBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert snapshot.getTotalIoWriteBytesPerSec() == expectedWrite :
            "Total write should be 60 MB/s, got " + (snapshot.getTotalIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert snapshot.getTestCount() == 3 : "Should have 3 tests";

        // Unregister one test
        tracker.unregister("test-2");

        UtilizationSnapshot after = tracker.getCurrentUtilization();
        long expectedReadAfter = 20 * 1024 * 1024;  // 5 + 15 MB/s (test-2 removed)
        long expectedWriteAfter = 40 * 1024 * 1024; // 10 + 30 MB/s

        assert after.getTotalIoReadBytesPerSec() == expectedReadAfter :
            "After unregister, read should be 20 MB/s";
        assert after.getTotalIoWriteBytesPerSec() == expectedWriteAfter :
            "After unregister, write should be 40 MB/s";
        assert after.getTestCount() == 2 : "Should have 2 tests after unregister";

        System.out.println("  ✓ Multiple tests aggregate and release correctly");
        System.out.println();
    }
}

