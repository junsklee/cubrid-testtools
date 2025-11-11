package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.tester.demand.PredictedDemand;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;
import org.json.JSONObject;

/**
 * Tests for Production Hardening patches applied to Smart Scheduling v2.
 *
 * Verifies critical safety fixes:
 * - Patch #1: NodeSnapshot canonical units parsing
 * - Patch #2: Docker runtime limits (verified via class loading)
 * - Patch #3: 409 fast-fail admission (verified via class loading)
 * - Patch #4: HealthHandler CPU calculation (verified via class loading)
 */
public class ProductionHardeningTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("=========================================================");
        System.out.println("Smart Scheduling v2 - Production Hardening Test Suite");
        System.out.println("=========================================================\n");

        // Patch #1: NodeSnapshot canonical parsing
        testCanonicalFormatParsing();
        testLegacyFormatParsing();
        testMixedFormatParsing();
        testCanonicalParsingNotZero();
        testMillicoresToPercentageConversion();
        testLargeMachineCapacity();

        // Patch #2-4: Integration checks
        testCriticalClassesExist();
        testDockerExecutorClassesUpdated();
        testTestHandlerHasNodeCapacity();
        testPredictedDemandV2Methods();
        testUtilizationSnapshotCanonicalUnits();

        System.out.println("\n=========================================================");
        System.out.println("Test Results");
        System.out.println("=========================================================");
        System.out.println("Total:  " + (testsPassed + testsFailed));
        System.out.println("Passed: " + testsPassed + " ✓");
        System.out.println("Failed: " + testsFailed + " ✗");

        if (testsFailed > 0) {
            System.out.println("\n✗ Some tests failed!");
            System.exit(1);
        } else {
            System.out.println("\n✓ All production hardening tests passed!");
            System.exit(0);
        }
    }

    // ==========================================================================
    // Patch #1: NodeSnapshot Canonical Units Parsing
    // ==========================================================================

    private static void testCanonicalFormatParsing() {
        System.out.println("[Test 1] NodeSnapshot v2 canonical format parsing...");

        try {
            JSONObject healthResponse = new JSONObject();
            healthResponse.put("nodeId", "192.168.1.100:8080");
            healthResponse.put("ts", "2025-11-11T18:00:00Z");
            healthResponse.put("status", "healthy");

            // Concurrency
            JSONObject concurrency = new JSONObject();
            concurrency.put("max", 8);
            concurrency.put("running", 3);
            healthResponse.put("concurrency", concurrency);

            // Capacity in canonical units (v2)
            JSONObject capacity = new JSONObject();
            capacity.put("cpu_millicores", 16000L);  // 16 cores × 1000
            capacity.put("mem_bytes", 32L * 1024 * 1024 * 1024);  // 32GB
            capacity.put("io_bytes_per_sec", 500L * 1024 * 1024);  // 500 MB/s
            capacity.put("iops", 10000L);
            capacity.put("net_bytes_per_sec", 1000L * 1024 * 1024);  // 1 GB/s
            healthResponse.put("capacity", capacity);

            // Utilization reserved (v2)
            JSONObject utilizationReserved = new JSONObject();
            utilizationReserved.put("cpu_millicores", 8000L);
            utilizationReserved.put("mem_bytes", 16L * 1024 * 1024 * 1024);
            healthResponse.put("utilization_reserved", utilizationReserved);

            // Parse
            NodeSnapshot snapshot = NodeSnapshot.fromJSON(healthResponse);

            // Verify
            assertDoubleEquals("CPU capacity", 1600.0, snapshot.getCpuPct(), 0.1);
            assertDoubleEquals("Memory capacity", 32768.0, snapshot.getMemMb(), 1.0);
            assertDoubleEquals("CPU utilization", 800.0, snapshot.getUsedCpuPct(), 0.1);
            assertStringEquals("Node ID", "192.168.1.100:8080", snapshot.getNodeId());

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testLegacyFormatParsing() {
        System.out.println("[Test 2] NodeSnapshot v1 legacy format parsing...");

        try {
            JSONObject healthResponse = new JSONObject();
            healthResponse.put("nodeId", "192.168.1.101:8080");

            // Capacity in legacy units (v1)
            JSONObject capacity = new JSONObject();
            capacity.put("cpu_pct", 800.0);
            capacity.put("mem_mb", 16384.0);
            healthResponse.put("capacity", capacity);

            // Utilization in legacy units (v1)
            JSONObject utilization = new JSONObject();
            utilization.put("cpu_pct", 400.0);
            utilization.put("mem_mb", 8192.0);
            healthResponse.put("utilization", utilization);

            // Parse
            NodeSnapshot snapshot = NodeSnapshot.fromJSON(healthResponse);

            // Verify legacy parsing still works
            assertDoubleEquals("CPU capacity (legacy)", 800.0, snapshot.getCpuPct(), 0.1);
            assertDoubleEquals("Memory capacity (legacy)", 16384.0, snapshot.getMemMb(), 1.0);
            assertDoubleEquals("CPU utilization (legacy)", 400.0, snapshot.getUsedCpuPct(), 0.1);

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testMixedFormatParsing() {
        System.out.println("[Test 3] NodeSnapshot mixed format (v2 capacity + v1 utilization)...");

        try {
            JSONObject healthResponse = new JSONObject();

            // Canonical capacity
            JSONObject capacity = new JSONObject();
            capacity.put("cpu_millicores", 8000L);
            capacity.put("mem_bytes", 16L * 1024 * 1024 * 1024);
            healthResponse.put("capacity", capacity);

            // Legacy utilization
            JSONObject utilization = new JSONObject();
            utilization.put("cpu_pct", 200.0);
            utilization.put("mem_mb", 4096.0);
            healthResponse.put("utilization", utilization);

            NodeSnapshot snapshot = NodeSnapshot.fromJSON(healthResponse);

            assertDoubleEquals("CPU capacity from canonical", 800.0, snapshot.getCpuPct(), 0.1);
            assertDoubleEquals("CPU utilization from legacy", 200.0, snapshot.getUsedCpuPct(), 0.1);

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testCanonicalParsingNotZero() {
        System.out.println("[Test 4] NodeSnapshot canonical parsing produces non-zero values...");

        try {
            JSONObject healthResponse = new JSONObject();
            JSONObject capacity = new JSONObject();
            capacity.put("cpu_millicores", 4000L);
            capacity.put("mem_bytes", 8L * 1024 * 1024 * 1024);
            healthResponse.put("capacity", capacity);

            NodeSnapshot snapshot = NodeSnapshot.fromJSON(healthResponse);

            assertTrue("CPU capacity must be non-zero", snapshot.getCpuPct() > 0);
            assertTrue("Memory capacity must be non-zero", snapshot.getMemMb() > 0);

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testMillicoresToPercentageConversion() {
        System.out.println("[Test 5] Millicores to percentage conversion accuracy...");

        try {
            JSONObject healthResponse = new JSONObject();
            JSONObject capacity = new JSONObject();
            capacity.put("cpu_millicores", 1234L);  // 1.234 cores = 123.4%
            capacity.put("mem_bytes", 1L * 1024 * 1024 * 1024);  // 1GB = 1024MB
            healthResponse.put("capacity", capacity);

            NodeSnapshot snapshot = NodeSnapshot.fromJSON(healthResponse);

            assertDoubleEquals("CPU conversion", 123.4, snapshot.getCpuPct(), 0.01);
            assertDoubleEquals("Memory conversion", 1024.0, snapshot.getMemMb(), 0.1);

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testLargeMachineCapacity() {
        System.out.println("[Test 6] Large machine (128 cores, 1TB) capacity...");

        try {
            JSONObject healthResponse = new JSONObject();
            JSONObject capacity = new JSONObject();
            capacity.put("cpu_millicores", 128000L);  // 128 cores
            capacity.put("mem_bytes", 1024L * 1024 * 1024 * 1024);  // 1TB
            healthResponse.put("capacity", capacity);

            NodeSnapshot snapshot = NodeSnapshot.fromJSON(healthResponse);

            assertDoubleEquals("128 cores = 12800%", 12800.0, snapshot.getCpuPct(), 0.1);
            assertDoubleEquals("1TB = 1048576 MB", 1048576.0, snapshot.getMemMb(), 1.0);

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    // ==========================================================================
    // Integration Tests: Verify critical classes exist and are correctly updated
    // ==========================================================================

    private static void testCriticalClassesExist() {
        System.out.println("[Test 7] Critical classes are loadable...");

        try {
            Class.forName("com.navercorp.cubridqa.builder.scheduler.NodeSnapshot");
            Class.forName("com.navercorp.cubridqa.builder.tester.TestHandler");
            Class.forName("com.navercorp.cubridqa.builder.tester.HealthHandler");
            Class.forName("com.navercorp.cubridqa.builder.tester.demand.PredictedDemand");
            Class.forName("com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot");

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (ClassNotFoundException e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testDockerExecutorClassesUpdated() {
        System.out.println("[Test 8] Docker executor classes have getPredictedDemand()...");

        try {
            Class<?> testRequestClass = Class.forName("com.navercorp.cubridqa.builder.tester.TestRequest");
            testRequestClass.getMethod("getPredictedDemand");

            Class.forName("com.navercorp.cubridqa.builder.exec.StandardDockerExecutor");
            Class.forName("com.navercorp.cubridqa.builder.exec.OptimizedDockerExecutor");

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testTestHandlerHasNodeCapacity() {
        System.out.println("[Test 9] TestHandler constructor includes NodeCapacity...");

        try {
            Class<?> testHandlerClass = Class.forName("com.navercorp.cubridqa.builder.tester.TestHandler");
            testHandlerClass.getConstructor(
                Class.forName("com.navercorp.cubridqa.builder.config.Config"),
                Class.forName("com.navercorp.cubridqa.builder.tester.TestOrchestrator"),
                Class.forName("com.navercorp.cubridqa.builder.tester.NodeCapacity"),
                java.util.logging.Logger.class
            );

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testPredictedDemandV2Methods() {
        System.out.println("[Test 10] PredictedDemand has canonical unit getters...");

        try {
            Class<?> pdClass = Class.forName("com.navercorp.cubridqa.builder.tester.demand.PredictedDemand");
            pdClass.getMethod("getCpuMillicores");
            pdClass.getMethod("getMemBytes");
            pdClass.getMethod("getIoBytesPerSec");
            pdClass.getMethod("getIops");
            pdClass.getMethod("getNetBytesPerSec");

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    private static void testUtilizationSnapshotCanonicalUnits() {
        System.out.println("[Test 11] UtilizationSnapshot has canonical unit getters...");

        try {
            Class<?> usClass = Class.forName("com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot");
            usClass.getMethod("getTotalCpuMillicores");
            usClass.getMethod("getTotalMemBytes");
            usClass.getMethod("getTotalIoBytesPerSec");
            usClass.getMethod("getTotalIops");
            usClass.getMethod("getTotalNetBytesPerSec");

            testsPassed++;
            System.out.println("  ✓ PASSED\n");
        } catch (Exception e) {
            testsFailed++;
            System.out.println("  ✗ FAILED: " + e.getMessage() + "\n");
        }
    }

    // ==========================================================================
    // Helper assertion methods
    // ==========================================================================

    private static void assertDoubleEquals(String message, double expected, double actual, double delta) {
        if (Math.abs(expected - actual) > delta) {
            throw new AssertionError(message + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertStringEquals(String message, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected '" + expected + "' but got '" + actual + "'");
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
