package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.scheduler.NodeDirectory;
import com.navercorp.cubridqa.builder.scheduler.NodeSnapshot;
import com.navercorp.cubridqa.builder.scheduler.TestInstance;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Tests for {@link NodeDirectory} I/O-first admission checks with separate
 * read/write margins and safety headroom.
 */
public class NodeDirectoryIoTest {

    public static void main(String[] args) {
        System.out.println("=== NodeDirectoryIoTest Suite ===\n");

        testReadWriteHeadroomChecks();
        testSafetyHeadroomEnforced();
        testConfigurableMargins();
        testReadWriteAsymmetricDemands();
        testZeroIoDemandBypassesCheck();

        System.out.println("\n=== All NodeDirectoryIoTest tests passed! ===");
    }

    private static void testReadWriteHeadroomChecks() {
        System.out.println("Test 1: Separate read/write headroom checks");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60);

        // Node with 100 MB/s read, 100 MB/s write capacity
        NodeSnapshot node = NodeSnapshot.builder()
            .nodeId("node-1")
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(800.0)
            .memMb(32768.0)
            .ioReadMbPerSec(100.0)
            .ioWriteMbPerSec(100.0)
            .ioMbPerSec(200.0)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(100.0)
            .usedMemMb(8192.0)
            .usedIoReadMbPerSec(20.0)  // 20 MB/s read used
            .usedIoWriteMbPerSec(30.0) // 30 MB/s write used
            .usedIoMbPerSec(50.0)
            .usedIops(2000.0)
            .usedNetMbPerSec(20.0)
            .build();

        directory.updateSnapshot(node.getNodeId(), node);

        // Test with read-heavy demand (should pass)
        TestInstance readHeavy = TestInstance.builder()
            .testKey("shell/sql/read_heavy.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(30.0)  // 30 MB/s read needed
            .predictedIoWriteMbPerSec(10.0) // 10 MB/s write needed
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.8)
            .build();

        List<NodeSnapshot> eligible1 = directory.getEligibleNodes(readHeavy);
        assert eligible1.size() == 1 : "Read-heavy test should be eligible";

        // Test with write-heavy demand that exceeds capacity (should fail)
        TestInstance writeHeavy = TestInstance.builder()
            .testKey("shell/sql/write_heavy.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(10.0)
            .predictedIoWriteMbPerSec(80.0)  // 80 MB/s write needed (exceeds free capacity)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.8)
            .build();

        List<NodeSnapshot> eligible2 = directory.getEligibleNodes(writeHeavy);
        assert eligible2.size() == 0 : "Write-heavy test should be rejected (exceeds capacity)";

        System.out.println("  ✓ Read/write headroom checks work independently");
        System.out.println();
    }

    private static void testSafetyHeadroomEnforced() {
        System.out.println("Test 2: Safety headroom enforced for I/O");

        // Create config with 15% safety headroom
        Properties props = new Properties();
        props.setProperty("io_safety_headroom_ratio", "0.15");
        BuilderConfig config = createConfigFromProperties(props);

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60, config);

        // Node with 100 MB/s read/write capacity, 50 MB/s used
        // Free: 50 MB/s, but 15% safety = 15 MB/s kept free
        // Available: 50 - 15 = 35 MB/s
        NodeSnapshot node = NodeSnapshot.builder()
            .nodeId("node-1")
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(800.0)
            .memMb(32768.0)
            .ioReadMbPerSec(100.0)
            .ioWriteMbPerSec(100.0)
            .ioMbPerSec(200.0)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(100.0)
            .usedMemMb(8192.0)
            .usedIoReadMbPerSec(50.0)  // 50 MB/s read used
            .usedIoWriteMbPerSec(50.0) // 50 MB/s write used
            .usedIoMbPerSec(100.0)
            .usedIops(2000.0)
            .usedNetMbPerSec(20.0)
            .build();

        directory.updateSnapshot(node.getNodeId(), node);

        // Test requiring 40 MB/s read (should fail - exceeds available 35 MB/s)
        TestInstance test = TestInstance.builder()
            .testKey("shell/sql/test.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(40.0)  // 40 MB/s needed (exceeds 35 MB/s available)
            .predictedIoWriteMbPerSec(5.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.8)
            .build();

        List<NodeSnapshot> eligible = directory.getEligibleNodes(test);
        assert eligible.size() == 0 : "Test should be rejected (exceeds safety headroom)";

        // Test requiring 30 MB/s read (should pass - within 35 MB/s available)
        TestInstance test2 = TestInstance.builder()
            .testKey("shell/sql/test2.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(30.0)  // 30 MB/s needed (within 35 MB/s available)
            .predictedIoWriteMbPerSec(5.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.8)
            .build();

        List<NodeSnapshot> eligible2 = directory.getEligibleNodes(test2);
        assert eligible2.size() == 1 : "Test should be eligible (within safety headroom)";

        System.out.println("  ✓ Safety headroom enforced correctly");
        System.out.println();
    }

    private static void testConfigurableMargins() {
        System.out.println("Test 3: Configurable margins affect admission");

        // Config with high IO margins (50% base)
        Properties props = new Properties();
        props.setProperty("scheduling_margin_io_read_base", "0.50");
        props.setProperty("scheduling_margin_io_write_base", "0.50");
        props.setProperty("scheduling_margin_confidence_factor", "0.50");
        BuilderConfig config = createConfigFromProperties(props);

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60, config);

        NodeSnapshot node = NodeSnapshot.builder()
            .nodeId("node-1")
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(800.0)
            .memMb(32768.0)
            .ioReadMbPerSec(100.0)
            .ioWriteMbPerSec(100.0)
            .ioMbPerSec(200.0)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(100.0)
            .usedMemMb(8192.0)
            .usedIoReadMbPerSec(0.0)
            .usedIoWriteMbPerSec(0.0)
            .usedIoMbPerSec(0.0)
            .usedIops(0.0)
            .usedNetMbPerSec(0.0)
            .build();

        directory.updateSnapshot(node.getNodeId(), node);

        // Test with 30 MB/s read, confidence 0.8
        // Margin = 0.50 + 0.50 * (1 - 0.8) = 0.50 + 0.10 = 0.60
        // Required = 30 * 1.60 = 48 MB/s
        TestInstance test = TestInstance.builder()
            .testKey("shell/sql/test.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(30.0)
            .predictedIoWriteMbPerSec(10.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.8)
            .build();

        List<NodeSnapshot> eligible = directory.getEligibleNodes(test);
        // With 100 MB/s capacity, 48 MB/s required should pass
        assert eligible.size() == 1 : "Test should be eligible with high margins";

        // Test with low confidence (0.2) - should require even more margin
        // Margin = 0.50 + 0.50 * (1 - 0.2) = 0.50 + 0.40 = 0.90
        // Required = 30 * 1.90 = 57 MB/s
        TestInstance lowConf = TestInstance.builder()
            .testKey("shell/sql/test_low_conf.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(30.0)
            .predictedIoWriteMbPerSec(10.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.2)  // Low confidence
            .build();

        List<NodeSnapshot> eligible2 = directory.getEligibleNodes(lowConf);
        // 57 MB/s should still pass with 100 MB/s capacity
        assert eligible2.size() == 1 : "Low confidence test should still be eligible";

        System.out.println("  ✓ Configurable margins affect admission decisions");
        System.out.println();
    }

    private static void testReadWriteAsymmetricDemands() {
        System.out.println("Test 4: Asymmetric read/write demands handled correctly");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60);

        // Node with asymmetric capacity: 200 MB/s read, 100 MB/s write
        NodeSnapshot node = NodeSnapshot.builder()
            .nodeId("node-1")
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(800.0)
            .memMb(32768.0)
            .ioReadMbPerSec(200.0)  // High read capacity
            .ioWriteMbPerSec(100.0) // Lower write capacity
            .ioMbPerSec(300.0)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(100.0)
            .usedMemMb(8192.0)
            .usedIoReadMbPerSec(50.0)
            .usedIoWriteMbPerSec(50.0)
            .usedIoMbPerSec(100.0)
            .usedIops(2000.0)
            .usedNetMbPerSec(20.0)
            .build();

        directory.updateSnapshot(node.getNodeId(), node);

        // Read-heavy test (should pass - plenty of read capacity)
        TestInstance readHeavy = TestInstance.builder()
            .testKey("shell/sql/read_heavy.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(100.0)  // High read demand
            .predictedIoWriteMbPerSec(10.0)  // Low write demand
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.8)
            .build();

        List<NodeSnapshot> eligible1 = directory.getEligibleNodes(readHeavy);
        assert eligible1.size() == 1 : "Read-heavy test should pass";

        // Write-heavy test (should fail - exceeds write capacity)
        TestInstance writeHeavy = TestInstance.builder()
            .testKey("shell/sql/write_heavy.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(10.0)
            .predictedIoWriteMbPerSec(60.0)  // High write demand (exceeds free 50 MB/s)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.8)
            .build();

        List<NodeSnapshot> eligible2 = directory.getEligibleNodes(writeHeavy);
        assert eligible2.size() == 0 : "Write-heavy test should fail (exceeds write capacity)";

        System.out.println("  ✓ Asymmetric read/write demands handled correctly");
        System.out.println();
    }

    private static void testZeroIoDemandBypassesCheck() {
        System.out.println("Test 5: Zero I/O demand bypasses headroom check");

        NodeDirectory directory = new NodeDirectory(Collections.emptyList(), 5, 60);

        // Node with no I/O capacity available
        NodeSnapshot node = NodeSnapshot.builder()
            .nodeId("node-1")
            .timestamp(Instant.now())
            .status("healthy")
            .maxConcurrentTests(4)
            .runningTests(0)
            .queuedTests(0)
            .cpuPct(800.0)
            .memMb(32768.0)
            .ioReadMbPerSec(100.0)
            .ioWriteMbPerSec(100.0)
            .ioMbPerSec(200.0)
            .iops(20000.0)
            .netMbPerSec(125.0)
            .usedCpuPct(100.0)
            .usedMemMb(8192.0)
            .usedIoReadMbPerSec(100.0)  // All read capacity used
            .usedIoWriteMbPerSec(100.0) // All write capacity used
            .usedIoMbPerSec(200.0)
            .usedIops(2000.0)
            .usedNetMbPerSec(20.0)
            .build();

        directory.updateSnapshot(node.getNodeId(), node);

        // Test with zero I/O demand (should pass - I/O check bypassed)
        TestInstance noIo = TestInstance.builder()
            .testKey("shell/sql/no_io.sh")
            .commit("commit-1")
            .baseline("baseline-1")
            .predictedCpuPct(50.0)
            .predictedMemMb(1024.0)
            .predictedIoReadMbPerSec(0.0)  // Zero I/O
            .predictedIoWriteMbPerSec(0.0)
            .predictedIops(200.0)
            .predictedNetMbPerSec(5.0)
            .confidence(0.8)
            .build();

        List<NodeSnapshot> eligible = directory.getEligibleNodes(noIo);
        assert eligible.size() == 1 : "Zero I/O test should pass (I/O check bypassed)";

        System.out.println("  ✓ Zero I/O demand bypasses headroom check");
        System.out.println();
    }

    /**
     * Helper method to create BuilderConfig from Properties by writing to a temporary config file.
     */
    private static BuilderConfig createConfigFromProperties(Properties props) {
        try {
            // Create temporary directories for required config properties
            File tempDir = Files.createTempDirectory("builder_config_test_").toFile();
            File cubridSrcDir = new File(tempDir, "cubrid_src");
            File shellTcDir = new File(tempDir, "shell_tc");
            cubridSrcDir.mkdirs();
            shellTcDir.mkdirs();

            // Add required properties if not present
            if (!props.containsKey("cubrid_src_dir")) {
                props.setProperty("cubrid_src_dir", cubridSrcDir.getAbsolutePath());
            }
            if (!props.containsKey("shell_tc_dir")) {
                props.setProperty("shell_tc_dir", shellTcDir.getAbsolutePath());
            }

            // Write properties to temporary config file
            File configFile = File.createTempFile("builder_config_", ".conf");
            configFile.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                props.store(fos, "Test configuration");
            }

            // Create BuilderConfig from the config file
            return new BuilderConfig(configFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create BuilderConfig from Properties", e);
        }
    }
}

