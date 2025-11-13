package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.scheduler.ScoreFunction;
import com.navercorp.cubridqa.builder.scheduler.TestInstance;

import java.time.Instant;
import java.util.Collections;

/**
 * Tests for {@link ScoreFunction} I/O-dominant scoring with heavier weights
 * for read/write pressure.
 */
public class ScoreFunctionIoTest {

    public static void main(String[] args) {
        System.out.println("=== ScoreFunctionIoTest Suite ===\n");

        testIoDominantScoring();
        testReadWritePressureWorstCase();
        testConfigurableIoWeights();
        testIoPressureDrivesNodeSelection();
        testAsymmetricIoCapacityAffectsScore();

        System.out.println("\n=== All ScoreFunctionIoTest tests passed! ===");
    }

    private static void testIoDominantScoring() {
        System.out.println("Test 1: I/O pressure dominates scoring");

        // ScoreFunction with default weights (IO=2.50, CPU=1.00, MEM=1.10, NET=0.80)
        ScoreFunction scoreFunction = new ScoreFunction(1.0, 1.0, 1.0, 1.0, 1.0,
            2.50, 1.00, 1.10, 0.80, null);

        // Test with high I/O demand
        TestInstance ioHeavy = TestInstance.builder()
            .testKey("shell/sql/io_heavy.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(80.0)  // High I/O demand
            .predictedIoWriteMbPerSec(80.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .build();

        // Node with limited I/O capacity
        NodeSnapshot tightIo = nodeWithResources("node-tight-io",
            100.0, 100.0,  // 100 MB/s read/write capacity
            50.0, 50.0,    // 50 MB/s read/write used
            200.0, 50.0    // CPU capacity/used
        );

        // Node with limited CPU but plenty of I/O
        NodeSnapshot tightCpu = nodeWithResources("node-tight-cpu",
            500.0, 500.0,  // 500 MB/s read/write capacity
            100.0, 100.0,  // 100 MB/s read/write used
            200.0, 150.0   // CPU capacity/used (tight)
        );

        double tightIoScore = scoreFunction.score(ioHeavy, tightIo);
        double tightCpuScore = scoreFunction.score(ioHeavy, tightCpu);

        // I/O pressure should dominate, so tight I/O node should have higher score
        assert tightIoScore > tightCpuScore :
            "I/O pressure should dominate: tight I/O score=" + tightIoScore +
            " should be > tight CPU score=" + tightCpuScore;

        System.out.println("  ✓ I/O pressure dominates scoring (tight I/O: " +
            String.format("%.3f", tightIoScore) + " > tight CPU: " +
            String.format("%.3f", tightCpuScore) + ")");
        System.out.println();
    }

    private static void testReadWritePressureWorstCase() {
        System.out.println("Test 2: Worst-case read/write pressure used");

        ScoreFunction scoreFunction = new ScoreFunction(1.0, 1.0, 1.0, 1.0, 1.0,
            2.50, 1.00, 1.10, 0.80, null);

        // Test with asymmetric I/O demand (read-heavy)
        TestInstance readHeavy = TestInstance.builder()
            .testKey("shell/sql/read_heavy.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(90.0)  // High read demand
            .predictedIoWriteMbPerSec(10.0) // Low write demand
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .build();

        // Node with limited read capacity
        NodeSnapshot node = nodeWithResources("node-1",
            100.0, 200.0,  // 100 MB/s read, 200 MB/s write capacity
            50.0, 50.0,    // 50 MB/s read/write used
            200.0, 50.0    // CPU capacity/used
        );

        double score = scoreFunction.score(readHeavy, node);

        // Read pressure = 90 / 50 = 1.8
        // Write pressure = 10 / 150 = 0.067
        // IO pressure = max(1.8, 0.067) = 1.8
        // Weighted IO = 2.50 * 1.8 = 4.5
        // This should dominate the score

        assert score > 4.0 : "Score should reflect high read pressure (got " + score + ")";

        System.out.println("  ✓ Worst-case (read) pressure used: score=" + String.format("%.3f", score));
        System.out.println();
    }

    private static void testConfigurableIoWeights() {
        System.out.println("Test 3: Configurable I/O weights affect scoring");

        // Low I/O weight
        ScoreFunction lowIoWeight = new ScoreFunction(1.0, 1.0, 1.0, 1.0, 1.0,
            1.00, 1.00, 1.10, 0.80, null);  // IO weight = 1.00

        // High I/O weight
        ScoreFunction highIoWeight = new ScoreFunction(1.0, 1.0, 1.0, 1.0, 1.0,
            5.00, 1.00, 1.10, 0.80, null);  // IO weight = 5.00

        TestInstance ioHeavy = TestInstance.builder()
            .testKey("shell/sql/io_heavy.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(80.0)
            .predictedIoWriteMbPerSec(80.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .build();

        NodeSnapshot node = nodeWithResources("node-1",
            100.0, 100.0,  // 100 MB/s read/write capacity
            50.0, 50.0,    // 50 MB/s read/write used
            200.0, 50.0    // CPU capacity/used
        );

        double lowScore = lowIoWeight.score(ioHeavy, node);
        double highScore = highIoWeight.score(ioHeavy, node);

        assert highScore > lowScore :
            "Higher I/O weight should produce higher score (low=" + lowScore +
            ", high=" + highScore + ")";

        System.out.println("  ✓ Configurable I/O weights affect scoring (low: " +
            String.format("%.3f", lowScore) + ", high: " +
            String.format("%.3f", highScore) + ")");
        System.out.println();
    }

    private static void testIoPressureDrivesNodeSelection() {
        System.out.println("Test 4: I/O pressure drives node selection");

        ScoreFunction scoreFunction = new ScoreFunction(1.0, 1.0, 1.0, 1.0, 1.0,
            2.50, 1.00, 1.10, 0.80, null);

        TestInstance test = TestInstance.builder()
            .testKey("shell/sql/test.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(60.0)
            .predictedIoWriteMbPerSec(60.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .build();

        // Node with plenty of I/O capacity (lower pressure)
        NodeSnapshot roomyIo = nodeWithResources("node-roomy-io",
            200.0, 200.0,  // 200 MB/s read/write capacity
            50.0, 50.0,    // 50 MB/s read/write used
            200.0, 100.0   // CPU capacity/used
        );

        // Node with limited I/O capacity (higher pressure)
        NodeSnapshot tightIo = nodeWithResources("node-tight-io",
            100.0, 100.0,  // 100 MB/s read/write capacity
            50.0, 50.0,    // 50 MB/s read/write used
            200.0, 100.0   // CPU capacity/used (same as roomy)
        );

        double roomyScore = scoreFunction.score(test, roomyIo);
        double tightScore = scoreFunction.score(test, tightIo);

        // Tight I/O node should have higher score (worse fit)
        assert tightScore > roomyScore :
            "Tight I/O node should have higher score (roomy=" + roomyScore +
            ", tight=" + tightScore + ")";

        System.out.println("  ✓ I/O pressure drives selection (roomy: " +
            String.format("%.3f", roomyScore) + ", tight: " +
            String.format("%.3f", tightScore) + ")");
        System.out.println();
    }

    private static void testAsymmetricIoCapacityAffectsScore() {
        System.out.println("Test 5: Asymmetric I/O capacity affects score");

        ScoreFunction scoreFunction = new ScoreFunction(1.0, 1.0, 1.0, 1.0, 1.0,
            2.50, 1.00, 1.10, 0.80, null);

        // Test with balanced read/write demand
        TestInstance test = TestInstance.builder()
            .testKey("shell/sql/test.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(50.0)
            .predictedIoWriteMbPerSec(50.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .build();

        // Node with symmetric capacity
        NodeSnapshot symmetric = nodeWithResources("node-symmetric",
            100.0, 100.0,  // 100 MB/s read/write capacity
            25.0, 25.0,    // 25 MB/s read/write used
            200.0, 50.0    // CPU capacity/used
        );

        // Node with asymmetric capacity (limited write)
        NodeSnapshot asymmetric = nodeWithResources("node-asymmetric",
            200.0, 50.0,   // 200 MB/s read, 50 MB/s write capacity
            25.0, 25.0,    // 25 MB/s read/write used
            200.0, 50.0    // CPU capacity/used (same)
        );

        double symmetricScore = scoreFunction.score(test, symmetric);
        double asymmetricScore = scoreFunction.score(test, asymmetric);

        // Asymmetric node has limited write capacity (50 - 25 = 25 MB/s free)
        // Write pressure = 50 / 25 = 2.0
        // Symmetric node has 75 MB/s free for both
        // Pressure = 50 / 75 = 0.67
        // Asymmetric should have higher score (worse fit)
        assert asymmetricScore > symmetricScore :
            "Asymmetric capacity should produce higher score (symmetric=" + symmetricScore +
            ", asymmetric=" + asymmetricScore + ")";

        System.out.println("  ✓ Asymmetric capacity affects score (symmetric: " +
            String.format("%.3f", symmetricScore) + ", asymmetric: " +
            String.format("%.3f", asymmetricScore) + ")");
        System.out.println();
    }

    private static NodeSnapshot nodeWithResources(String nodeId,
                                                  double ioReadCap, double ioWriteCap,
                                                  double ioReadUsed, double ioWriteUsed,
                                                  double cpuCap, double cpuUsed) {
        return NodeSnapshot.builder()
            .nodeId(nodeId)
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(cpuCap)
            .memMb(32768.0)
            .ioReadMbPerSec(ioReadCap)
            .ioWriteMbPerSec(ioWriteCap)
            .ioMbPerSec(ioReadCap + ioWriteCap)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(cpuUsed)
            .usedMemMb(8192.0)
            .usedIoReadMbPerSec(ioReadUsed)
            .usedIoWriteMbPerSec(ioWriteUsed)
            .usedIoMbPerSec(ioReadUsed + ioWriteUsed)
            .usedIops(2000.0)
            .usedNetMbPerSec(20.0)
            .build();
    }
}








