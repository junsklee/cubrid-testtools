package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Tests for {@link BuilderConfig} new I/O-related configuration getters.
 */
public class BuilderConfigIoTest {

    public static void main(String[] args) {
        System.out.println("=== BuilderConfigIoTest Suite ===\n");

        testDefaultIoWeights();
        testCustomIoWeights();
        testDefaultIoMargins();
        testCustomIoMargins();
        testDefaultSafetyHeadroom();
        testCustomSafetyHeadroom();

        System.out.println("\n=== All BuilderConfigIoTest tests passed! ===");
    }

    private static void testDefaultIoWeights() {
        System.out.println("Test 1: Default I/O weights");

        BuilderConfig config = createConfigFromProperties(new Properties());

        double ioWeight = config.getSchedulingWeightIo();
        double cpuWeight = config.getSchedulingWeightCpu();
        double memWeight = config.getSchedulingWeightMem();
        double netWeight = config.getSchedulingWeightNet();

        assert Math.abs(ioWeight - 2.50) < 0.01 :
            "Default IO weight should be 2.50, got " + ioWeight;
        assert Math.abs(cpuWeight - 1.00) < 0.01 :
            "Default CPU weight should be 1.00, got " + cpuWeight;
        assert Math.abs(memWeight - 1.10) < 0.01 :
            "Default MEM weight should be 1.10, got " + memWeight;
        assert Math.abs(netWeight - 0.80) < 0.01 :
            "Default NET weight should be 0.80, got " + netWeight;

        System.out.println("  ✓ Default weights: IO=" + ioWeight + ", CPU=" + cpuWeight +
            ", MEM=" + memWeight + ", NET=" + netWeight);
        System.out.println();
    }

    private static void testCustomIoWeights() {
        System.out.println("Test 2: Custom I/O weights");

        Properties props = new Properties();
        props.setProperty("scheduling_weight_io", "5.00");
        props.setProperty("scheduling_weight_cpu", "2.00");
        props.setProperty("scheduling_weight_mem", "1.50");
        props.setProperty("scheduling_weight_net", "1.20");

        BuilderConfig config = createConfigFromProperties(props);

        double ioWeight = config.getSchedulingWeightIo();
        double cpuWeight = config.getSchedulingWeightCpu();
        double memWeight = config.getSchedulingWeightMem();
        double netWeight = config.getSchedulingWeightNet();

        assert Math.abs(ioWeight - 5.00) < 0.01 :
            "Custom IO weight should be 5.00, got " + ioWeight;
        assert Math.abs(cpuWeight - 2.00) < 0.01 :
            "Custom CPU weight should be 2.00, got " + cpuWeight;
        assert Math.abs(memWeight - 1.50) < 0.01 :
            "Custom MEM weight should be 1.50, got " + memWeight;
        assert Math.abs(netWeight - 1.20) < 0.01 :
            "Custom NET weight should be 1.20, got " + netWeight;

        System.out.println("  ✓ Custom weights: IO=" + ioWeight + ", CPU=" + cpuWeight +
            ", MEM=" + memWeight + ", NET=" + netWeight);
        System.out.println();
    }

    private static void testDefaultIoMargins() {
        System.out.println("Test 3: Default I/O margins");

        BuilderConfig config = createConfigFromProperties(new Properties());

        double ioReadMargin = config.getSchedulingMarginIoReadBase();
        double ioWriteMargin = config.getSchedulingMarginIoWriteBase();

        assert Math.abs(ioReadMargin - 0.35) < 0.01 :
            "Default IO read margin should be 0.35, got " + ioReadMargin;
        assert Math.abs(ioWriteMargin - 0.35) < 0.01 :
            "Default IO write margin should be 0.35, got " + ioWriteMargin;

        System.out.println("  ✓ Default margins: IO Read=" + ioReadMargin +
            ", IO Write=" + ioWriteMargin);
        System.out.println();
    }

    private static void testCustomIoMargins() {
        System.out.println("Test 4: Custom I/O margins");

        Properties props = new Properties();
        props.setProperty("scheduling_margin_io_read_base", "0.50");
        props.setProperty("scheduling_margin_io_write_base", "0.45");

        BuilderConfig config = createConfigFromProperties(props);

        double ioReadMargin = config.getSchedulingMarginIoReadBase();
        double ioWriteMargin = config.getSchedulingMarginIoWriteBase();

        assert Math.abs(ioReadMargin - 0.50) < 0.01 :
            "Custom IO read margin should be 0.50, got " + ioReadMargin;
        assert Math.abs(ioWriteMargin - 0.45) < 0.01 :
            "Custom IO write margin should be 0.45, got " + ioWriteMargin;

        System.out.println("  ✓ Custom margins: IO Read=" + ioReadMargin +
            ", IO Write=" + ioWriteMargin);
        System.out.println();
    }

    private static void testDefaultSafetyHeadroom() {
        System.out.println("Test 5: Default safety headroom");

        BuilderConfig config = createConfigFromProperties(new Properties());

        double headroom = config.getIoSafetyHeadroomRatio();

        assert Math.abs(headroom - 0.15) < 0.01 :
            "Default safety headroom should be 0.15, got " + headroom;

        System.out.println("  ✓ Default safety headroom: " + headroom);
        System.out.println();
    }

    private static void testCustomSafetyHeadroom() {
        System.out.println("Test 6: Custom safety headroom");

        Properties props = new Properties();
        props.setProperty("io_safety_headroom_ratio", "0.25");

        BuilderConfig config = createConfigFromProperties(props);

        double headroom = config.getIoSafetyHeadroomRatio();

        assert Math.abs(headroom - 0.25) < 0.01 :
            "Custom safety headroom should be 0.25, got " + headroom;

        System.out.println("  ✓ Custom safety headroom: " + headroom);
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

