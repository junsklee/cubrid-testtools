package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.tester.demand.PredictedDemand;
import com.navercorp.cubridqa.builder.tester.stats.NodeHardware;
import org.json.JSONObject;

/**
 * Tests for {@link PredictedDemand} read/write I/O handling, including
 * backward compatibility with legacy ioMbPerSec.
 */
public class PredictedDemandIoTest {

    public static void main(String[] args) {
        System.out.println("=== PredictedDemandIoTest Suite ===\n");

        testReadWriteFieldsFromJson();
        testLegacyIoMbPerSecSplitsEvenly();
        testExplicitReadWriteTakesPrecedence();
        testConservativeDefaultsIncludeReadWrite();
        testPhaseBasedReadWriteTracking();
        testToJsonIncludesReadWrite();

        System.out.println("\n=== All PredictedDemandIoTest tests passed! ===");
    }

    private static void testReadWriteFieldsFromJson() {
        System.out.println("Test 1: Read/write fields parsed from JSON");

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

        assert demand.getIoReadBytesPerSec() == 20 * 1024 * 1024 :
            "Read IO should be 20 MB/s, got " + (demand.getIoReadBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert demand.getIoWriteBytesPerSec() == 30 * 1024 * 1024 :
            "Write IO should be 30 MB/s, got " + (demand.getIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert demand.getIoBytesPerSec() == 50 * 1024 * 1024 :
            "Total IO should be 50 MB/s, got " + (demand.getIoBytesPerSec() / (1024 * 1024)) + " MB/s";

        System.out.println("  ✓ Read: " + (demand.getIoReadBytesPerSec() / (1024 * 1024)) + " MB/s, " +
            "Write: " + (demand.getIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s");
        System.out.println();
    }

    private static void testLegacyIoMbPerSecSplitsEvenly() {
        System.out.println("Test 2: Legacy ioMbPerSec splits 50/50 when read/write not provided");

        JSONObject json = new JSONObject()
            .put("durationMs", 30000)
            .put("cpuMillicores", 500)
            .put("memBytes", 512 * 1024 * 1024)
            .put("ioMbPerSec", 40.0)  // Legacy field: 40 MB/s total
            .put("iops", 200)
            .put("netBytesPerSec", 5 * 1024 * 1024)
            .put("confidence", 0.7);

        PredictedDemand demand = PredictedDemand.fromRequest(json, null);

        long expectedRead = 20 * 1024 * 1024;  // 50% of 40 MB/s
        long expectedWrite = 20 * 1024 * 1024; // 50% of 40 MB/s

        assert Math.abs(demand.getIoReadBytesPerSec() - expectedRead) < 1024 :
            "Read IO should be ~20 MB/s, got " + (demand.getIoReadBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert Math.abs(demand.getIoWriteBytesPerSec() - expectedWrite) < 1024 :
            "Write IO should be ~20 MB/s, got " + (demand.getIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert demand.getIoBytesPerSec() == 40 * 1024 * 1024 :
            "Total IO should be 40 MB/s, got " + (demand.getIoBytesPerSec() / (1024 * 1024)) + " MB/s";

        System.out.println("  ✓ Legacy 40 MB/s split to Read: " + 
            (demand.getIoReadBytesPerSec() / (1024 * 1024)) + " MB/s, Write: " +
            (demand.getIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s");
        System.out.println();
    }

    private static void testExplicitReadWriteTakesPrecedence() {
        System.out.println("Test 3: Explicit read/write takes precedence over legacy ioMbPerSec");

        JSONObject json = new JSONObject()
            .put("durationMs", 30000)
            .put("cpuMillicores", 500)
            .put("memBytes", 512 * 1024 * 1024)
            .put("ioMbPerSec", 100.0)  // Legacy: 100 MB/s (should be ignored)
            .put("ioReadBytesPerSec", 10 * 1024 * 1024)  // Explicit: 10 MB/s read
            .put("ioWriteBytesPerSec", 15 * 1024 * 1024)  // Explicit: 15 MB/s write
            .put("iops", 200)
            .put("netBytesPerSec", 5 * 1024 * 1024)
            .put("confidence", 0.9);

        PredictedDemand demand = PredictedDemand.fromRequest(json, null);

        assert demand.getIoReadBytesPerSec() == 10 * 1024 * 1024 :
            "Read IO should be 10 MB/s (explicit), got " + (demand.getIoReadBytesPerSec() / (1024 * 1024)) + " MB/s";
        assert demand.getIoWriteBytesPerSec() == 15 * 1024 * 1024 :
            "Write IO should be 15 MB/s (explicit), got " + (demand.getIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s";

        System.out.println("  ✓ Explicit read/write values used (not legacy split)");
        System.out.println();
    }

    private static void testConservativeDefaultsIncludeReadWrite() {
        System.out.println("Test 4: Conservative defaults include read/write split");

        NodeHardware hw = NodeHardware.builder()
            .cpuPct(800.0)
            .memMb(32768.0)
            .ioMbPerSec(600.0)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .build();

        PredictedDemand demand = PredictedDemand.conservative(hw);

        assert demand.getIoReadBytesPerSec() > 0 : "Read IO should be > 0";
        assert demand.getIoWriteBytesPerSec() > 0 : "Write IO should be > 0";
        assert demand.getIoReadBytesPerSec() + demand.getIoWriteBytesPerSec() == demand.getIoBytesPerSec() :
            "Read + Write should equal total IO";

        System.out.println("  ✓ Conservative defaults: Read: " + 
            (demand.getIoReadBytesPerSec() / (1024 * 1024)) + " MB/s, Write: " +
            (demand.getIoWriteBytesPerSec() / (1024 * 1024)) + " MB/s");
        System.out.println();
    }

    private static void testPhaseBasedReadWriteTracking() {
        System.out.println("Test 5: Phase-based predictions include read/write");

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

        assert demand.getPhases().size() == 2 : "Should have 2 phases";
        assert demand.getPhases().get(0).ioReadBytesPerSec == 5 * 1024 * 1024 :
            "Phase 1 read should be 5 MB/s";
        assert demand.getPhases().get(0).ioWriteBytesPerSec == 10 * 1024 * 1024 :
            "Phase 1 write should be 10 MB/s";
        assert demand.getPhases().get(1).ioReadBytesPerSec == 15 * 1024 * 1024 :
            "Phase 2 read should be 15 MB/s";
        assert demand.getPhases().get(1).ioWriteBytesPerSec == 30 * 1024 * 1024 :
            "Phase 2 write should be 30 MB/s";

        System.out.println("  ✓ Phase-based read/write IO tracked correctly");
        System.out.println();
    }

    private static void testToJsonIncludesReadWrite() {
        System.out.println("Test 6: toJson() includes read/write fields");

        JSONObject json = new JSONObject()
            .put("durationMs", 30000)
            .put("cpuMillicores", 500)
            .put("memBytes", 512 * 1024 * 1024)
            .put("ioReadBytesPerSec", 25 * 1024 * 1024)
            .put("ioWriteBytesPerSec", 35 * 1024 * 1024)
            .put("iops", 200)
            .put("netBytesPerSec", 5 * 1024 * 1024)
            .put("confidence", 0.85);

        PredictedDemand demand = PredictedDemand.fromRequest(json, null);
        JSONObject output = demand.toJson();

        assert output.has("ioReadBytesPerSec") : "Output should include ioReadBytesPerSec";
        assert output.has("ioWriteBytesPerSec") : "Output should include ioWriteBytesPerSec";
        assert output.getLong("ioReadBytesPerSec") == 25 * 1024 * 1024 :
            "Output read should be 25 MB/s";
        assert output.getLong("ioWriteBytesPerSec") == 35 * 1024 * 1024 :
            "Output write should be 35 MB/s";

        System.out.println("  ✓ JSON serialization includes read/write fields");
        System.out.println();
    }
}







