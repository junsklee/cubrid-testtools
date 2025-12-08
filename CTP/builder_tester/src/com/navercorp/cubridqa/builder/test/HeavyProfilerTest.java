package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.*;

import org.json.JSONObject;

import java.util.*;

/**
 * Unit tests for heavy test classification and related components.
 * 
 * <p>Run with: java -cp ... com.navercorp.cubridqa.builder.test.HeavyProfilerTest</p>
 */
public class HeavyProfilerTest {

    public static void main(String[] args) {
        System.out.println("=== HeavyProfilerTest Suite ===\n");

        // TestProfile Tests
        testTestProfileBuilderNormal();
        testTestProfileBuilderHeavy();
        testTestProfileBuilderExtreme();
        testTestProfileDefaultValues();

        // HeavyProfiler Tests
        testHeavyProfilerComputeGlobalStats();
        testHeavyProfilerProfileTest();
        testHeavyProfilerProfileAll();
        testHeavyProfilerJsonSerialization();

        // HeavyDemandInflator Tests
        testDemandInflatorNormal();
        testDemandInflatorHeavy();
        testDemandInflatorExtremeWithCapacity();

        // ElephantThresholdCalculator Tests
        testElephantThresholdFromProfiles();
        testElephantThresholdMinFloor();
        testPercentileComputation();

        // TestInstance Integration Tests
        testTestInstanceWithHeavyClass();
        testTestInstanceDefaultHeavyClass();

        // ReadyQueue Integration Tests
        testReadyQueueHeavyGoesToElephants();

        System.out.println("\n=== All HeavyProfilerTest tests passed! ===");
    }

    // ============== TestProfile Tests ==============

    private static void testTestProfileBuilderNormal() {
        System.out.println("Test: TestProfile builder creates NORMAL profile");

        TestProfile profile = TestProfile.builder()
                .testKey("shell/test/normal.sh")
                .cpuRatio(1.0)
                .memRatio(1.5)
                .ioRatio(0.8)
                .iopsRatio(1.2)
                .predictedDurationMs(30000)
                .build();

        assertEqual("shell/test/normal.sh", profile.getTestKey());
        assertEqual(TestProfile.HeavyClass.NORMAL, profile.getHeavyClass());
        assertEqual(TestProfile.DominantDimension.NONE, profile.getDominantDim());
        assertFalse(profile.isHeavy());
        assertFalse(profile.isExtreme());

        System.out.println("  PASSED");
    }

    private static void testTestProfileBuilderHeavy() {
        System.out.println("Test: TestProfile builder creates HEAVY profile");

        TestProfile profile = TestProfile.builder()
                .testKey("shell/test/heavy_io.sh")
                .cpuRatio(1.0)
                .memRatio(1.5)
                .ioRatio(2.5)  // Between 2.0 and 4.0 -> HEAVY
                .iopsRatio(1.2)
                .predictedDurationMs(60000)
                .build();

        assertEqual(TestProfile.HeavyClass.HEAVY, profile.getHeavyClass());
        assertEqual(TestProfile.DominantDimension.IO, profile.getDominantDim());
        assertTrue(profile.isHeavy());
        assertFalse(profile.isExtreme());

        System.out.println("  PASSED");
    }

    private static void testTestProfileBuilderExtreme() {
        System.out.println("Test: TestProfile builder creates EXTREME profile");

        TestProfile profile = TestProfile.builder()
                .testKey("shell/test/extreme_cpu.sh")
                .cpuRatio(5.0)  // >= 4.0 -> EXTREME
                .memRatio(1.5)
                .ioRatio(2.0)
                .iopsRatio(1.2)
                .predictedDurationMs(120000)
                .build();

        assertEqual(TestProfile.HeavyClass.EXTREME, profile.getHeavyClass());
        assertEqual(TestProfile.DominantDimension.CPU, profile.getDominantDim());
        assertTrue(profile.isHeavy());
        assertTrue(profile.isExtreme());

        System.out.println("  PASSED");
    }

    private static void testTestProfileDefaultValues() {
        System.out.println("Test: TestProfile default values");

        TestProfile defaultProfile = TestProfile.NORMAL_DEFAULT;

        assertEqual("unknown", defaultProfile.getTestKey());
        assertEqual(TestProfile.HeavyClass.NORMAL, defaultProfile.getHeavyClass());
        assertEqual(TestProfile.DominantDimension.NONE, defaultProfile.getDominantDim());
        assertDoubleEqual(1.0, defaultProfile.getCpuRatio(), 0.001);

        System.out.println("  PASSED");
    }

    // ============== HeavyProfiler Tests ==============

    private static void testHeavyProfilerComputeGlobalStats() {
        System.out.println("Test: HeavyProfiler computes global statistics");

        HeavyProfiler profiler = new HeavyProfiler();

        List<HeavyProfiler.RawTestStats> stats = Arrays.asList(
                new HeavyProfiler.RawTestStats("test1", 30000, 50.0, 500.0, 20.0, 5000.0, 10, 60.0, 700.0, 35.0, 6000.0),
                new HeavyProfiler.RawTestStats("test2", 60000, 100.0, 1000.0, 40.0, 10000.0, 10, 120.0, 1400.0, 80.0, 12000.0),
                new HeavyProfiler.RawTestStats("test3", 45000, 75.0, 750.0, 30.0, 7500.0, 10, 90.0, 900.0, 50.0, 9000.0)
        );

        HeavyProfiler.GlobalStats global = profiler.computeGlobalStats(stats);

        assertEqual(3, global.testCount);
        assertDoubleEqual(75.0, global.meanCpuPct, 0.001);
        assertDoubleEqual(750.0, global.meanMemMb, 0.001);
        assertDoubleEqual(30.0, global.meanIoMbPerSec, 0.001);
        assertDoubleEqual(7500.0, global.meanIops, 0.001);

        System.out.println("  PASSED");
    }

    private static void testHeavyProfilerProfileTest() {
        System.out.println("Test: HeavyProfiler profiles individual test");

        HeavyProfiler profiler = new HeavyProfiler();

        HeavyProfiler.GlobalStats global = new HeavyProfiler.GlobalStats(50.0, 500.0, 20.0, 5000.0, 100);
        
        // Test that uses 2x CPU relative to mean -> HEAVY, CPU dominant
        HeavyProfiler.RawTestStats stats = new HeavyProfiler.RawTestStats(
                "shell/test/heavy.sh", 45000, 100.0, 500.0, 20.0, 5000.0, 10,
                120.0, 600.0, 30.0, 6000.0);

        TestProfile profile = profiler.profileTest(stats, global);

        assertEqual("shell/test/heavy.sh", profile.getTestKey());
        assertDoubleEqual(2.0, profile.getCpuRatio(), 0.001);  // 100 / 50 = 2.0
        assertEqual(TestProfile.HeavyClass.HEAVY, profile.getHeavyClass());
        assertEqual(TestProfile.DominantDimension.CPU, profile.getDominantDim());

        System.out.println("  PASSED");
    }

    private static void testHeavyProfilerProfileAll() {
        System.out.println("Test: HeavyProfiler profiles all tests");

        HeavyProfiler profiler = new HeavyProfiler();

        List<HeavyProfiler.RawTestStats> stats = Arrays.asList(
                new HeavyProfiler.RawTestStats("normal", 30000, 50.0, 500.0, 10.0, 200.0, 10, 60.0, 600.0, 15.0, 250.0),   // All ratios ~1.0
                new HeavyProfiler.RawTestStats("heavy_io", 60000, 50.0, 500.0, 60.0, 200.0, 10, 55.0, 520.0, 120.0, 220.0), // IO = ~6x mean effective
                new HeavyProfiler.RawTestStats("extreme_cpu", 90000, 250.0, 500.0, 10.0, 200.0, 10, 300.0, 520.0, 15.0, 210.0) // CPU = 5x mean
        );

        Map<String, TestProfile> profiles = profiler.profileAll(stats);

        assertEqual(3, profiles.size());
        assertEqual(TestProfile.HeavyClass.NORMAL, profiles.get("normal").getHeavyClass());
        assertEqual(TestProfile.HeavyClass.EXTREME, profiles.get("heavy_io").getHeavyClass()); // 6x >= 4.0
        assertEqual(TestProfile.HeavyClass.EXTREME, profiles.get("extreme_cpu").getHeavyClass()); // 5x >= 4.0

        System.out.println("  PASSED");
    }

    private static void testHeavyProfilerJsonSerialization() {
        System.out.println("Test: HeavyProfiler JSON serialization");

        HeavyProfiler profiler = new HeavyProfiler();

        TestProfile profile = TestProfile.builder()
                .testKey("shell/test/example.sh")
                .cpuRatio(3.0)
                .memRatio(1.5)
                .ioRatio(2.0)
                .iopsRatio(1.0)
                .predictedDurationMs(45000)
                .avgCpuPct(150.0)
                .avgMemMb(750.0)
                .avgIoMbPerSec(40.0)
                .avgIops(5000.0)
                .build();

        JSONObject json = profiler.toJson(profile);
        TestProfile restored = profiler.fromJson(json);

        assertEqual(profile.getTestKey(), restored.getTestKey());
        assertEqual(profile.getHeavyClass(), restored.getHeavyClass());
        assertEqual(profile.getDominantDim(), restored.getDominantDim());
        assertDoubleEqual(profile.getCpuRatio(), restored.getCpuRatio(), 0.001);

        System.out.println("  PASSED");
    }

    // ============== HeavyDemandInflator Tests ==============

    private static void testDemandInflatorNormal() {
        System.out.println("Test: DemandInflator leaves NORMAL tests unchanged");

        HeavyDemandInflator inflator = new HeavyDemandInflator();

        TestProfile normalProfile = TestProfile.builder()
                .testKey("normal.sh")
                .cpuRatio(1.0)
                .memRatio(1.0)
                .ioRatio(1.0)
                .iopsRatio(1.0)
                .build();

        HeavyDemandInflator.InflatedDemand inflated = inflator.inflate(
                normalProfile, 50.0, 512.0, 10.0, 10.0, 200.0, 5.0, null);

        // NORMAL tests should not be inflated
        assertDoubleEqual(50.0, inflated.cpuPct, 0.001);
        assertDoubleEqual(512.0, inflated.memMb, 0.001);
        assertDoubleEqual(10.0, inflated.ioReadMbPerSec, 0.001);
        assertDoubleEqual(10.0, inflated.ioWriteMbPerSec, 0.001);
        assertEqual(TestProfile.HeavyClass.NORMAL, inflated.appliedClass);

        System.out.println("  PASSED");
    }

    private static void testDemandInflatorHeavy() {
        System.out.println("Test: DemandInflator inflates HEAVY tests");

        HeavyDemandInflator inflator = new HeavyDemandInflator();

        TestProfile heavyProfile = TestProfile.builder()
                .testKey("heavy.sh")
                .cpuRatio(2.5)
                .memRatio(1.0)
                .ioRatio(1.0)
                .iopsRatio(1.0)
                .build();

        HeavyDemandInflator.InflatedDemand inflated = inflator.inflate(
                heavyProfile, 50.0, 512.0, 10.0, 10.0, 200.0, 5.0, null);

        // HEAVY tests should be inflated by 1.15x for CPU/MEM, 1.25x for IO (conservative)
        assertDoubleEqual(50.0 * 1.15, inflated.cpuPct, 0.001);
        assertDoubleEqual(512.0 * 1.15, inflated.memMb, 0.001);
        assertDoubleEqual(10.0 * 1.25, inflated.ioReadMbPerSec, 0.001);
        assertDoubleEqual(10.0 * 1.25, inflated.ioWriteMbPerSec, 0.001);
        assertEqual(TestProfile.HeavyClass.HEAVY, inflated.appliedClass);

        System.out.println("  PASSED");
    }

    private static void testDemandInflatorExtremeWithCapacity() {
        System.out.println("Test: DemandInflator inflates EXTREME tests to node capacity");

        HeavyDemandInflator inflator = new HeavyDemandInflator();

        TestProfile extremeProfile = TestProfile.builder()
                .testKey("extreme.sh")
                .cpuRatio(5.0)
                .memRatio(1.0)
                .ioRatio(1.0)
                .iopsRatio(1.0)
                .build();

        HeavyDemandInflator.NodeCapacityInfo nodeCapacity = 
                new HeavyDemandInflator.NodeCapacityInfo(500.0, 500.0, 10000.0);

        HeavyDemandInflator.InflatedDemand inflated = inflator.inflate(
                extremeProfile, 50.0, 512.0, 10.0, 10.0, 200.0, 5.0, nodeCapacity);

        // EXTREME tests should claim ~50% of node capacity for I/O (allows ~2 per node)
        assertDoubleEqual(500.0 * 0.5, inflated.ioReadMbPerSec, 0.001);
        assertDoubleEqual(500.0 * 0.5, inflated.ioWriteMbPerSec, 0.001);
        assertDoubleEqual(10000.0 * 0.5, inflated.iops, 0.001);
        assertEqual(TestProfile.HeavyClass.EXTREME, inflated.appliedClass);

        System.out.println("  PASSED");
    }

    // ============== ElephantThresholdCalculator Tests ==============

    private static void testElephantThresholdFromProfiles() {
        System.out.println("Test: ElephantThresholdCalculator computes P75 threshold");

        List<TestProfile> profiles = Arrays.asList(
                createProfileWithDuration("test1", 10000),   // 10s
                createProfileWithDuration("test2", 30000),   // 30s
                createProfileWithDuration("test3", 60000),   // 60s
                createProfileWithDuration("test4", 120000)   // 120s
        );

        // P75 of [10000, 30000, 60000, 120000] should be ~90000
        long threshold = ElephantThresholdCalculator.computeFromProfiles(profiles, 30000);

        // Should be at least the min threshold and around P75
        assertTrue(threshold >= 30000, "threshold >= minFloor");
        assertTrue(threshold >= 60000, "threshold >= P75 lower bound"); // P75 is between 60000 and 120000

        System.out.println("  PASSED");
    }

    private static void testElephantThresholdMinFloor() {
        System.out.println("Test: ElephantThresholdCalculator respects min floor");

        List<TestProfile> profiles = Arrays.asList(
                createProfileWithDuration("test1", 1000),   // 1s
                createProfileWithDuration("test2", 2000),   // 2s
                createProfileWithDuration("test3", 3000),   // 3s
                createProfileWithDuration("test4", 4000)    // 4s
        );

        // P75 would be ~3500ms, but min floor is 60000
        long threshold = ElephantThresholdCalculator.computeFromProfiles(profiles, 60000);

        assertEqual(60000L, threshold);

        System.out.println("  PASSED");
    }

    private static void testPercentileComputation() {
        System.out.println("Test: ElephantThresholdCalculator computes percentiles correctly");

        List<Long> values = Arrays.asList(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);

        assertEqual(10L, ElephantThresholdCalculator.computePercentile(values, 0.0));
        assertEqual(55L, ElephantThresholdCalculator.computePercentile(values, 0.5));
        assertEqual(78L, ElephantThresholdCalculator.computePercentile(values, 0.75));
        assertEqual(100L, ElephantThresholdCalculator.computePercentile(values, 1.0));

        System.out.println("  PASSED");
    }

    // ============== TestInstance Integration Tests ==============

    private static void testTestInstanceWithHeavyClass() {
        System.out.println("Test: TestInstance correctly stores heavy classification");

        TestProfile heavyProfile = TestProfile.builder()
                .testKey("heavy.sh")
                .cpuRatio(3.0)
                .memRatio(1.0)
                .ioRatio(1.0)
                .iopsRatio(1.0)
                .build();

        TestInstance instance = TestInstance.builder()
                .testKey("shell/test/heavy.sh")
                .commit("abc1234")
                .baseline("def5678")
                .fromProfile(heavyProfile)
                .build();

        assertEqual(TestProfile.HeavyClass.HEAVY, instance.getHeavyClass());
        assertEqual(TestProfile.DominantDimension.CPU, instance.getDominantDim());
        assertTrue(instance.isHeavy(), "instance should be heavy");
        assertFalse(instance.isExtreme(), "instance should not be extreme");

        System.out.println("  PASSED");
    }

    private static void testTestInstanceDefaultHeavyClass() {
        System.out.println("Test: TestInstance defaults to NORMAL");

        TestInstance instance = TestInstance.builder()
                .testKey("shell/test/normal.sh")
                .commit("abc1234")
                .baseline("def5678")
                .build();

        assertEqual(TestProfile.HeavyClass.NORMAL, instance.getHeavyClass());
        assertFalse(instance.isHeavy(), "instance should not be heavy");

        System.out.println("  PASSED");
    }

    // ============== ReadyQueue Integration Tests ==============

    private static void testReadyQueueHeavyGoesToElephants() {
        System.out.println("Test: ReadyQueue routes HEAVY tests to elephants");

        ReadyQueue queue = new ReadyQueue(60000);  // 60s threshold

        // Create a short test (20s) that's NORMAL -> should go to mice
        TestInstance normalShort = TestInstance.builder()
                .testKey("normal_short.sh")
                .commit("abc")
                .baseline("def")
                .predictedDurationMs(20000)  // 20s < 60s threshold
                .heavyClass(TestProfile.HeavyClass.NORMAL)
                .build();

        // Create a short test (20s) that's HEAVY -> should go to elephants
        TestInstance heavyShort = TestInstance.builder()
                .testKey("heavy_short.sh")
                .commit("abc")
                .baseline("def")
                .predictedDurationMs(20000)  // 20s < 60s threshold
                .heavyClass(TestProfile.HeavyClass.HEAVY)
                .build();

        // Create a long test (90s) that's NORMAL -> should go to elephants
        TestInstance normalLong = TestInstance.builder()
                .testKey("normal_long.sh")
                .commit("abc")
                .baseline("def")
                .predictedDurationMs(90000)  // 90s > 60s threshold
                .heavyClass(TestProfile.HeavyClass.NORMAL)
                .build();

        queue.offer(normalShort);
        queue.offer(heavyShort);
        queue.offer(normalLong);

        // normalShort should be in mice
        assertEqual(1, queue.getMiceCount());
        // heavyShort and normalLong should be in elephants
        assertEqual(2, queue.getElephantsCount());

        System.out.println("  PASSED");
    }

    // ============== Helper Methods ==============

    private static TestProfile createProfileWithDuration(String testKey, long durationMs) {
        return TestProfile.builder()
                .testKey(testKey)
                .cpuRatio(1.0)
                .memRatio(1.0)
                .ioRatio(1.0)
                .iopsRatio(1.0)
                .predictedDurationMs(durationMs)
                .build();
    }

    private static void assertEqual(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertEqual(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertEqual(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertDoubleEqual(double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError("Expected " + expected + " but got " + actual + " (tolerance: " + tolerance + ")");
        }
    }

    private static void assertTrue(boolean condition) {
        assertTrue(condition, "Condition should be true");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition) {
        assertFalse(condition, "Condition should be false");
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }
}
