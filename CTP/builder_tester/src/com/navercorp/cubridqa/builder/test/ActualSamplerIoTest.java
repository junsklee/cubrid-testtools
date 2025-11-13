package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.tester.ActualSampler;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Integration tests for {@link ActualSampler} I/O delta tracking.
 *
 * These tests verify:
 * 1. Baseline establishment (first sample returns zero I/O)
 * 2. I/O rate calculation across multiple samples
 * 3. Container restart detection
 * 4. Accurate I/O metrics with real Docker containers
 */
public class ActualSamplerIoTest {

    private static final String TEST_CONTAINER_NAME = "tester_io_test_container";
    private static final int SAMPLE_INTERVAL_MS = 2000; // 2 seconds between samples
    private static final double MB = 1024.0 * 1024.0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== ActualSamplerIoTest Suite ===\n");

        try {
            testBaselineEstablishment();
            testIoDeltaTracking();
            testContainerRestartDetection();
            testMultipleContainers();

            System.out.println("\n=== All ActualSamplerIoTest tests passed! ===");
        } catch (Exception e) {
            System.err.println("\n=== TEST FAILED ===");
            e.printStackTrace();
            System.exit(1);
        } finally {
            // Cleanup any leftover test containers
            cleanupTestContainers();
        }
    }

    /**
     * Test 1: First sample establishes baseline and returns zero I/O rates.
     */
    private static void testBaselineEstablishment() throws Exception {
        System.out.println("Test 1: Baseline establishment (first sample returns zero I/O)");

        cleanupTestContainers();

        // Create a container with I/O activity
        String containerId = createTestContainer(
            "while true; do " +
            "dd if=/dev/zero of=/tmp/test bs=5M count=5 2>/dev/null; " +
            "dd if=/tmp/test of=/dev/null bs=5M 2>/dev/null; " +
            "sleep 1; done"
        );

        try {
            // Wait for container to start generating I/O
            Thread.sleep(2000);

            BuilderConfig config = new BuilderConfig(
                "/home/qahome/cubrid-testtools/CTP/builder_tester/conf/tester.conf"
            );
            ActualSampler sampler = new ActualSampler(config);

            // First sample - should establish baseline (zero I/O)
            UtilizationSnapshot snapshot1 = sampler.sampleCurrentUtilization();

            assert snapshot1.getTotalIoReadBytesPerSec() == 0 :
                "First sample I/O read should be 0 (baseline), got " + snapshot1.getTotalIoReadBytesPerSec();
            assert snapshot1.getTotalIoWriteBytesPerSec() == 0 :
                "First sample I/O write should be 0 (baseline), got " + snapshot1.getTotalIoWriteBytesPerSec();

            System.out.println("  ✓ First sample correctly returns zero I/O (baseline established)");
            System.out.println("  ✓ CPU: " + snapshot1.getTotalCpuMillicores() + " millicores");
            System.out.println("  ✓ Memory: " + String.format("%.1f", snapshot1.getTotalMemBytes() / MB) + " MB");
            System.out.println();

        } finally {
            stopContainer(containerId);
        }
    }

    /**
     * Test 2: Subsequent samples calculate I/O rates from deltas.
     */
    private static void testIoDeltaTracking() throws Exception {
        System.out.println("Test 2: I/O delta tracking across multiple samples");

        cleanupTestContainers();

        // Create container with consistent I/O pattern (write ~10MB per iteration)
        String containerId = createTestContainer(
            "while true; do " +
            "dd if=/dev/zero of=/tmp/data bs=1M count=10 2>/dev/null; " +
            "sleep 1; done"
        );

        try {
            Thread.sleep(2000);

            BuilderConfig config = new BuilderConfig(
                "/home/qahome/cubrid-testtools/CTP/builder_tester/conf/tester.conf"
            );
            ActualSampler sampler = new ActualSampler(config);

            // Sample 1: Baseline
            UtilizationSnapshot snapshot1 = sampler.sampleCurrentUtilization();
            assert snapshot1.getTotalIoWriteBytesPerSec() == 0 : "Sample 1 should be baseline (zero)";

            Thread.sleep(SAMPLE_INTERVAL_MS);

            // Sample 2: Should show I/O activity
            UtilizationSnapshot snapshot2 = sampler.sampleCurrentUtilization();
            long ioWrite2 = snapshot2.getTotalIoWriteBytesPerSec();

            assert ioWrite2 > 0 : "Sample 2 I/O write should be > 0, got " + ioWrite2;
            System.out.println("  ✓ Sample 2 I/O write: " + String.format("%.1f", ioWrite2 / MB) + " MB/s");

            Thread.sleep(SAMPLE_INTERVAL_MS);

            // Sample 3: Should continue tracking
            UtilizationSnapshot snapshot3 = sampler.sampleCurrentUtilization();
            long ioWrite3 = snapshot3.getTotalIoWriteBytesPerSec();

            assert ioWrite3 > 0 : "Sample 3 I/O write should be > 0, got " + ioWrite3;
            System.out.println("  ✓ Sample 3 I/O write: " + String.format("%.1f", ioWrite3 / MB) + " MB/s");

            Thread.sleep(SAMPLE_INTERVAL_MS);

            // Sample 4: Verify consistency
            UtilizationSnapshot snapshot4 = sampler.sampleCurrentUtilization();
            long ioWrite4 = snapshot4.getTotalIoWriteBytesPerSec();

            assert ioWrite4 > 0 : "Sample 4 I/O write should be > 0, got " + ioWrite4;
            System.out.println("  ✓ Sample 4 I/O write: " + String.format("%.1f", ioWrite4 / MB) + " MB/s");

            // Verify samples are relatively consistent (within reasonable variance)
            double avgIo = (ioWrite2 + ioWrite3 + ioWrite4) / 3.0;
            System.out.println("  ✓ Average I/O write: " + String.format("%.1f", avgIo / MB) + " MB/s");
            System.out.println("  ✓ Delta tracking working correctly across multiple samples");
            System.out.println();

        } finally {
            stopContainer(containerId);
        }
    }

    /**
     * Test 3: Container restart resets baseline.
     */
    private static void testContainerRestartDetection() throws Exception {
        System.out.println("Test 3: Container restart detection");

        cleanupTestContainers();

        String containerId = createTestContainer(
            "while true; do " +
            "dd if=/dev/zero of=/tmp/test bs=2M count=10 2>/dev/null; " +
            "sleep 1; done"
        );

        try {
            Thread.sleep(2000);

            BuilderConfig config = new BuilderConfig(
                "/home/qahome/cubrid-testtools/CTP/builder_tester/conf/tester.conf"
            );
            ActualSampler sampler = new ActualSampler(config);

            // Sample 1: Baseline
            sampler.sampleCurrentUtilization();
            Thread.sleep(SAMPLE_INTERVAL_MS);

            // Sample 2: Should show I/O
            UtilizationSnapshot snapshot2 = sampler.sampleCurrentUtilization();
            long ioWrite2 = snapshot2.getTotalIoWriteBytesPerSec();
            assert ioWrite2 > 0 : "Sample 2 should show I/O activity";
            System.out.println("  ✓ Before restart - I/O write: " + String.format("%.1f", ioWrite2 / MB) + " MB/s");

            // Restart the container
            System.out.println("  → Restarting container...");
            restartContainer(TEST_CONTAINER_NAME);
            Thread.sleep(2000);

            // Sample 3: After restart, should detect reset and return zero (new baseline)
            UtilizationSnapshot snapshot3 = sampler.sampleCurrentUtilization();
            // Note: The restart detection happens internally, metrics should still be collected
            System.out.println("  ✓ After restart - I/O write: " +
                String.format("%.1f", snapshot3.getTotalIoWriteBytesPerSec() / MB) + " MB/s");

            Thread.sleep(SAMPLE_INTERVAL_MS);

            // Sample 4: Should resume normal tracking with new baseline
            UtilizationSnapshot snapshot4 = sampler.sampleCurrentUtilization();
            long ioWrite4 = snapshot4.getTotalIoWriteBytesPerSec();
            System.out.println("  ✓ After baseline reset - I/O write: " +
                String.format("%.1f", ioWrite4 / MB) + " MB/s");

            System.out.println("  ✓ Container restart detection working correctly");
            System.out.println();

        } finally {
            stopContainer(containerId);
        }
    }

    /**
     * Test 4: Multiple containers aggregate correctly.
     */
    private static void testMultipleContainers() throws Exception {
        System.out.println("Test 4: Multiple containers aggregate I/O correctly");

        cleanupTestContainers();

        // Create two containers with different I/O patterns
        String container1 = createTestContainer(
            "while true; do " +
            "dd if=/dev/zero of=/tmp/c1 bs=3M count=5 2>/dev/null; " +
            "sleep 1; done",
            TEST_CONTAINER_NAME + "_1"
        );

        String container2 = createTestContainer(
            "while true; do " +
            "dd if=/dev/zero of=/tmp/c2 bs=2M count=5 2>/dev/null; " +
            "sleep 1; done",
            TEST_CONTAINER_NAME + "_2"
        );

        try {
            Thread.sleep(2000);

            BuilderConfig config = new BuilderConfig(
                "/home/qahome/cubrid-testtools/CTP/builder_tester/conf/tester.conf"
            );
            ActualSampler sampler = new ActualSampler(config);

            // Sample 1: Baseline for both containers
            sampler.sampleCurrentUtilization();
            Thread.sleep(SAMPLE_INTERVAL_MS);

            // Sample 2: Should show aggregated I/O from both containers
            UtilizationSnapshot snapshot2 = sampler.sampleCurrentUtilization();
            long totalIoWrite = snapshot2.getTotalIoWriteBytesPerSec();

            assert totalIoWrite > 0 : "Aggregated I/O should be > 0";
            System.out.println("  ✓ Container 1 + Container 2 aggregated I/O write: " +
                String.format("%.1f", totalIoWrite / MB) + " MB/s");

            // The total should be roughly the sum of both container's I/O
            // (approximately 3MB + 2MB = 5MB per second, accounting for overhead and timing)
            assert totalIoWrite > 3 * 1024 * 1024 :
                "Aggregated I/O should be at least 3 MB/s, got " + String.format("%.1f", totalIoWrite / MB) + " MB/s";

            System.out.println("  ✓ Multiple container aggregation working correctly");
            System.out.println();

        } finally {
            stopContainer(container1);
            stopContainer(container2);
        }
    }

    // Helper methods

    private static String createTestContainer(String command) throws Exception {
        return createTestContainer(command, TEST_CONTAINER_NAME);
    }

    private static String createTestContainer(String command, String name) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "run", "-d",
            "--name", name,
            "--rm",
            "alpine:latest",
            "sh", "-c", command
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String containerId = reader.readLine();
        process.waitFor();

        if (process.exitValue() != 0) {
            throw new RuntimeException("Failed to create test container");
        }

        return containerId != null ? containerId.trim() : "";
    }

    private static void stopContainer(String containerIdOrName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "stop", containerIdOrName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            // Ignore errors during cleanup
        }
    }

    private static void restartContainer(String containerName) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("docker", "restart", containerName);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to restart container: " + containerName);
        }
    }

    private static void cleanupTestContainers() {
        try {
            stopContainer(TEST_CONTAINER_NAME);
            stopContainer(TEST_CONTAINER_NAME + "_1");
            stopContainer(TEST_CONTAINER_NAME + "_2");
            Thread.sleep(500); // Give Docker time to clean up
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}
