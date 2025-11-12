package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import org.json.JSONObject;

import java.time.Instant;

/**
 * Tests for {@link NodeSnapshot} read/write I/O parsing from /health responses.
 */
public class NodeSnapshotIoTest {

    public static void main(String[] args) {
        System.out.println("=== NodeSnapshotIoTest Suite ===\n");

        testParseReadWriteFromHealth();
        testLegacyIoBytesPerSecSplitsEvenly();
        testFreeIoReadWriteCalculated();
        testCapacityBytesPerSecGetters();

        System.out.println("\n=== All NodeSnapshotIoTest tests passed! ===");
    }

    private static void testParseReadWriteFromHealth() {
        System.out.println("Test 1: Parse read/write I/O from /health response");

        JSONObject health = new JSONObject();

        // Capacity section with read/write
        JSONObject capacity = new JSONObject();
        capacity.put("cpu_millicores", 8000);  // 8 cores
        capacity.put("mem_bytes", 32L * 1024 * 1024 * 1024);  // 32 GB
        capacity.put("io_read_bytes_per_sec", 100L * 1024 * 1024);  // 100 MB/s read
        capacity.put("io_write_bytes_per_sec", 150L * 1024 * 1024);  // 150 MB/s write
        capacity.put("iops", 20000L);
        capacity.put("net_bytes_per_sec", 125L * 1024 * 1024);
        health.put("capacity", capacity);

        // Utilization section with read/write
        JSONObject utilization = new JSONObject();
        utilization.put("cpu_millicores", 2000);
        utilization.put("mem_bytes", 8L * 1024 * 1024 * 1024);
        utilization.put("io_read_bytes_per_sec", 30L * 1024 * 1024);  // 30 MB/s read used
        utilization.put("io_write_bytes_per_sec", 50L * 1024 * 1024);  // 50 MB/s write used
        utilization.put("iops", 2000L);
        utilization.put("net_bytes_per_sec", 20L * 1024 * 1024);
        utilization.put("tests", 2);
        health.put("utilization_reserved", utilization);

        health.put("status", "healthy");
        health.put("ts", Instant.now().toString());
        health.put("nodeId", "node-1");

        NodeSnapshot snapshot = NodeSnapshot.fromJSON(health);

        assert snapshot.getIoReadMbPerSec() == 100.0 :
            "Read capacity should be 100 MB/s, got " + snapshot.getIoReadMbPerSec();
        assert snapshot.getIoWriteMbPerSec() == 150.0 :
            "Write capacity should be 150 MB/s, got " + snapshot.getIoWriteMbPerSec();
        assert snapshot.getUsedIoReadMbPerSec() == 30.0 :
            "Used read should be 30 MB/s, got " + snapshot.getUsedIoReadMbPerSec();
        assert snapshot.getUsedIoWriteMbPerSec() == 50.0 :
            "Used write should be 50 MB/s, got " + snapshot.getUsedIoWriteMbPerSec();

        System.out.println("  ✓ Parsed read/write I/O from /health response");
        System.out.println();
    }

    private static void testLegacyIoBytesPerSecSplitsEvenly() {
        System.out.println("Test 2: Legacy io_bytes_per_sec splits 50/50");

        JSONObject health = new JSONObject();

        // Capacity section with legacy total IO
        JSONObject capacity = new JSONObject();
        capacity.put("cpu_millicores", 8000);
        capacity.put("mem_bytes", 32L * 1024 * 1024 * 1024);
        capacity.put("io_bytes_per_sec", 200L * 1024 * 1024);  // Legacy: 200 MB/s total
        capacity.put("iops", 20000L);
        capacity.put("net_bytes_per_sec", 125L * 1024 * 1024);
        health.put("capacity", capacity);

        // Utilization section with legacy total IO
        JSONObject utilization = new JSONObject();
        utilization.put("cpu_millicores", 2000);
        utilization.put("mem_bytes", 8L * 1024 * 1024 * 1024);
        utilization.put("io_bytes_per_sec", 80L * 1024 * 1024);  // Legacy: 80 MB/s total
        utilization.put("iops", 2000L);
        utilization.put("net_bytes_per_sec", 20L * 1024 * 1024);
        utilization.put("tests", 2);
        health.put("utilization_reserved", utilization);

        health.put("status", "healthy");
        health.put("ts", Instant.now().toString());
        health.put("nodeId", "node-1");

        NodeSnapshot snapshot = NodeSnapshot.fromJSON(health);

        // Should split 200 MB/s total → 100 MB/s read, 100 MB/s write
        assert Math.abs(snapshot.getIoReadMbPerSec() - 100.0) < 0.1 :
            "Read capacity should be ~100 MB/s (split from 200), got " + snapshot.getIoReadMbPerSec();
        assert Math.abs(snapshot.getIoWriteMbPerSec() - 100.0) < 0.1 :
            "Write capacity should be ~100 MB/s (split from 200), got " + snapshot.getIoWriteMbPerSec();
        // Should split 80 MB/s total → 40 MB/s read, 40 MB/s write
        assert Math.abs(snapshot.getUsedIoReadMbPerSec() - 40.0) < 0.1 :
            "Used read should be ~40 MB/s (split from 80), got " + snapshot.getUsedIoReadMbPerSec();
        assert Math.abs(snapshot.getUsedIoWriteMbPerSec() - 40.0) < 0.1 :
            "Used write should be ~40 MB/s (split from 80), got " + snapshot.getUsedIoWriteMbPerSec();

        System.out.println("  ✓ Legacy io_bytes_per_sec split 50/50");
        System.out.println();
    }

    private static void testFreeIoReadWriteCalculated() {
        System.out.println("Test 3: Free read/write I/O calculated correctly");

        NodeSnapshot snapshot = NodeSnapshot.builder()
            .nodeId("node-1")
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(800.0)
            .memMb(32768.0)
            .ioReadMbPerSec(100.0)  // 100 MB/s read capacity
            .ioWriteMbPerSec(150.0) // 150 MB/s write capacity
            .ioMbPerSec(250.0)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(200.0)
            .usedMemMb(8192.0)
            .usedIoReadMbPerSec(30.0)  // 30 MB/s read used
            .usedIoWriteMbPerSec(50.0) // 50 MB/s write used
            .usedIoMbPerSec(80.0)
            .usedIops(2000.0)
            .usedNetMbPerSec(20.0)
            .build();

        double freeRead = snapshot.getFreeIoReadMbPerSec();
        double freeWrite = snapshot.getFreeIoWriteMbPerSec();

        assert Math.abs(freeRead - 70.0) < 0.1 :
            "Free read should be 70 MB/s (100 - 30), got " + freeRead;
        assert Math.abs(freeWrite - 100.0) < 0.1 :
            "Free write should be 100 MB/s (150 - 50), got " + freeWrite;

        System.out.println("  ✓ Free read: " + freeRead + " MB/s, Free write: " + freeWrite + " MB/s");
        System.out.println();
    }

    private static void testCapacityBytesPerSecGetters() {
        System.out.println("Test 4: Capacity bytes/sec getters work correctly");

        NodeSnapshot snapshot = NodeSnapshot.builder()
            .nodeId("node-1")
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(800.0)
            .memMb(32768.0)
            .ioReadMbPerSec(100.0)  // 100 MB/s read
            .ioWriteMbPerSec(150.0) // 150 MB/s write
            .ioMbPerSec(250.0)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(200.0)
            .usedMemMb(8192.0)
            .usedIoReadMbPerSec(30.0)
            .usedIoWriteMbPerSec(50.0)
            .usedIoMbPerSec(80.0)
            .usedIops(2000.0)
            .usedNetMbPerSec(20.0)
            .build();

        long readBps = snapshot.getIoReadCapacityBytesPerSec();
        long writeBps = snapshot.getIoWriteCapacityBytesPerSec();

        long expectedRead = (long) (100.0 * 1024 * 1024);
        long expectedWrite = (long) (150.0 * 1024 * 1024);

        assert readBps == expectedRead :
            "Read capacity should be " + expectedRead + " bytes/s, got " + readBps;
        assert writeBps == expectedWrite :
            "Write capacity should be " + expectedWrite + " bytes/s, got " + writeBps;

        System.out.println("  ✓ Capacity getters: Read=" + (readBps / (1024 * 1024)) +
            " MB/s, Write=" + (writeBps / (1024 * 1024)) + " MB/s");
        System.out.println();
    }
}

