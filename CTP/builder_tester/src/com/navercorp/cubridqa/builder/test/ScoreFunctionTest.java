package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.scheduler.ScoreFunction;
import com.navercorp.cubridqa.builder.scheduler.TestInstance;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Tests for {@link ScoreFunction}, ensuring weighting behaves as designed.
 */
public class ScoreFunctionTest {

    public static void main(String[] args) {
        System.out.println("=== ScoreFunctionTest Suite ===\n");

        testCachePenaltiesAffectScore();
        testAgeBoostReducesScore();
        testDominantResourceDrivesPressure();

        System.out.println("\n=== All ScoreFunctionTest tests passed! ===");
    }

    private static void testCachePenaltiesAffectScore() {
        System.out.println("Test 1: Cache penalties influence score");

        ScoreFunction scoreFunction = new ScoreFunction();

        TestInstance test = baseTest("shell/sql/cache/test.sh")
            .imageTag("cubrid:test-img")
            .buildPackage("pkg.tar.gz")
            .predictedDurationMs(25_000)
            .build();

        NodeSnapshot cachedNode = nodeWithResources(
            "node-cache",
            Collections.singleton("cubrid:test-img"),
            Collections.singleton("pkg.tar.gz"),
            200.0
        );
        NodeSnapshot coldNode = nodeWithResources(
            "node-cold",
            Collections.<String>emptySet(),
            Collections.<String>emptySet(),
            200.0
        );

        double cachedScore = scoreFunction.score(test, cachedNode);
        double coldScore = scoreFunction.score(test, coldNode);

        assert coldScore - cachedScore >= 0.19 :
            "Missing image/package should incur combined penalty (expected ≥0.20, got " + (coldScore - cachedScore) + ")";

        System.out.println("  ✓ Cached score=" + String.format("%.3f", cachedScore) +
            ", cold score=" + String.format("%.3f", coldScore));
        System.out.println();
    }

    private static void testAgeBoostReducesScore() {
        System.out.println("Test 2: Queue age reduces score");

        ScoreFunction scoreFunction = new ScoreFunction();
        NodeSnapshot node = nodeWithResources(
            "node",
            Collections.<String>emptySet(),
            Collections.<String>emptySet(),
            200.0
        );

        TestInstance young = baseTest("shell/sql/age/test.sh")
            .submittedAt(Instant.now())
            .build();

        TestInstance aged = baseTest("shell/sql/age/test.sh")
            .submittedAt(Instant.now().minusSeconds(600)) // 10 minutes → capped boost
            .build();

        double youngScore = scoreFunction.score(young, node);
        double agedScore = scoreFunction.score(aged, node);
        double delta = youngScore - agedScore;

        assert delta >= 0.09 && delta <= 0.11 :
            "Age boost should reduce score by ≈0.10 (observed " + delta + ")";

        System.out.println("  ✓ Young score=" + String.format("%.3f", youngScore) +
            ", aged score=" + String.format("%.3f", agedScore));
        System.out.println();
    }

    private static void testDominantResourceDrivesPressure() {
        System.out.println("Test 3: Dominant resource pressure drives node choice");

        ScoreFunction scoreFunction = new ScoreFunction();

        TestInstance cpuHeavy = baseTest("shell/sql/pressure/test.sh")
            .predictedCpuPct(300.0)
            .predictedMemMb(512.0)
            .predictedDurationMs(40_000)
            .build();

        NodeSnapshot tightCpu = nodeWithResources(
            "node-tight",
            Collections.<String>emptySet(),
            Collections.<String>emptySet(),
            550.0
        );
        NodeSnapshot roomyCpu = nodeWithResources(
            "node-roomy",
            Collections.<String>emptySet(),
            Collections.<String>emptySet(),
            100.0
        );

        double tightScore = scoreFunction.score(cpuHeavy, tightCpu);
        double roomyScore = scoreFunction.score(cpuHeavy, roomyCpu);

        assert tightScore > roomyScore :
            "Node with limited CPU should produce higher score (tight=" + tightScore + ", roomy=" + roomyScore + ")";

        System.out.println("  ✓ Tight score=" + String.format("%.3f", tightScore) +
            ", roomy score=" + String.format("%.3f", roomyScore));
        System.out.println();
    }

    private static TestInstance.Builder baseTest(String testKey) {
        return TestInstance.builder()
            .testKey(testKey)
            .commit("commit-123")
            .baseline("baseline-123")
            .imageTag(null)
            .buildPackage(null)
            .predictedDurationMs(15_000)
            .predictedCpuPct(120.0)
            .predictedMemMb(1024.0)
            .predictedIoMbPerSec(10.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0);
    }

    private static NodeSnapshot nodeWithResources(String nodeId, Set<String> images, Set<String> packages, double usedCpuPct) {
        NodeSnapshot.Builder builder = NodeSnapshot.builder()
            .nodeId(nodeId)
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(1)
            .queuedTests(0)
            .cpuPct(600.0)
            .usedCpuPct(usedCpuPct)
            .memMb(32_768.0)
            .usedMemMb(8_192.0)
            .ioMbPerSec(600.0)
            .usedIoMbPerSec(100.0)
            .iops(20_000.0)
            .usedIops(2_000.0)
            .netMbPerSec(125.0)
            .usedNetMbPerSec(20.0);

        images.forEach(builder::addCachedImage);
        packages.forEach(builder::addCachedPackage);
        return builder.build();
    }
}
