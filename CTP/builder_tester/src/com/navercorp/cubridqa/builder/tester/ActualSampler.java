package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Samples actual resource utilization from running Docker containers.
 *
 * <p>This component takes one-time snapshots of current Docker container resource usage
 * and aggregates them to provide actual utilization metrics for the /health endpoint.</p>
 *
 * <p>Unlike DockerStatsCollector which monitors individual tests over time for historical
 * metrics, ActualSampler provides cluster-wide real-time snapshots for scheduling decisions.</p>
 */
public class ActualSampler {
    private static final Logger logger = Logger.getLogger(ActualSampler.class.getName());
    private static final String FORMAT = "{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}";
    private static final double BYTES_PER_MB = 1024d * 1024d;
    private static final long SAMPLE_TIMEOUT_MS = 2000; // 2 second timeout

    private final BuilderConfig config;
    private final boolean enabled;
    private final String containerPattern;
    private boolean dockerAvailable = true;
    private long lastWarningTime = 0;
    private static final long WARNING_THROTTLE_MS = 60000; // Warn once per minute

    public ActualSampler(BuilderConfig config) {
        this.config = config;
        // Use reflection to access config properties since BuilderConfig doesn't expose them
        this.enabled = Boolean.parseBoolean(getConfigProperty(config, "actual_sampling_enabled", "true"));
        this.containerPattern = getConfigProperty(config, "test_container_pattern", "cubrid-test-");
        logger.log(Level.INFO, "ActualSampler initialized: enabled={0}, containerPattern=''{1}''",
                new Object[]{enabled, containerPattern});
    }

    /**
     * Helper method to access config properties (BuilderConfig doesn't expose properties directly).
     * This is a workaround until BuilderConfig provides a general getProperty method.
     */
    private static String getConfigProperty(BuilderConfig config, String key, String defaultValue) {
        // Try to use reflection to access the properties field
        try {
            // Walk up the class hierarchy to find properties field
            Class<?> clazz = config.getClass();
            java.lang.reflect.Field propertiesField = null;

            while (clazz != null && propertiesField == null) {
                try {
                    propertiesField = clazz.getDeclaredField("properties");
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (propertiesField == null) {
                logger.log(Level.WARNING, "Could not find properties field in config class hierarchy");
                return defaultValue;
            }

            propertiesField.setAccessible(true);
            java.util.Properties props = (java.util.Properties) propertiesField.get(config);
            String value = props.getProperty(key, defaultValue);
            logger.log(Level.INFO, "Config property ''{0}'' = ''{1}'' (default: ''{2}'')",
                    new Object[]{key, value, defaultValue});
            return value;
        } catch (Exception e) {
            // Fallback to default if reflection fails
            logger.log(Level.WARNING, "Failed to read config property ''{0}'': {1}",
                    new Object[]{key, e.getMessage()});
            return defaultValue;
        }
    }

    /**
     * Samples current utilization from all running test containers.
     *
     * @return UtilizationSnapshot with actual metrics, or zeros if sampling fails
     */
    public UtilizationSnapshot sampleCurrentUtilization() {
        logger.log(Level.FINE, "sampleCurrentUtilization called: enabled={0}, dockerAvailable={1}",
                new Object[]{enabled, dockerAvailable});

        if (!enabled) {
            logger.log(Level.WARNING, "ActualSampler is disabled (actual_sampling_enabled=false)");
            return UtilizationSnapshot.empty();
        }

        if (!dockerAvailable) {
            logger.log(Level.FINE, "Docker marked as unavailable, returning empty snapshot");
            return UtilizationSnapshot.empty();
        }

        try {
            List<String> containers = listRunningTestContainers();
            logger.log(Level.FINE, "Found {0} test containers matching pattern ''{1}''",
                    new Object[]{containers.size(), containerPattern});

            if (containers.isEmpty()) {
                return UtilizationSnapshot.empty();
            }

            UtilizationSnapshot result = aggregateContainerMetrics(containers);
            logger.log(Level.FINE, "Sampled metrics: cpu={0}mCPU, mem={1}B",
                    new Object[]{result.getTotalCpuMillicores(), result.getTotalMemBytes()});
            return result;
        } catch (Exception e) {
            logThrottledWarning("Failed to sample actual utilization: " + e.getMessage());
            logger.log(Level.FINE, "Exception details", e);
            return UtilizationSnapshot.empty();
        }
    }

    /**
     * Lists all running test containers matching the configured pattern.
     */
    private List<String> listRunningTestContainers() throws IOException, InterruptedException {
        List<String> containers = new ArrayList<>();

        ProcessBuilder pb = new ProcessBuilder(
            "docker", "ps", "--filter", "name=" + containerPattern,
            "--format", "{{.Names}}"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    containers.add(line);
                }
            }
        }

        boolean completed = process.waitFor(SAMPLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("docker ps command timed out");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            dockerAvailable = false;
            throw new IOException("docker ps failed with exit code: " + exitCode);
        }

        return containers;
    }

    /**
     * Aggregates metrics from all running containers.
     */
    private UtilizationSnapshot aggregateContainerMetrics(List<String> containers)
            throws IOException, InterruptedException {

        double totalCpuPercent = 0.0;
        long totalMemBytes = 0;
        long totalNetRxBytes = 0;
        long totalNetTxBytes = 0;
        long totalBlockReadBytes = 0;
        long totalBlockWriteBytes = 0;

        // Sample all containers in a single docker stats call
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("stats");
        command.add("--no-stream");
        command.add("--format");
        command.add(FORMAT);
        command.addAll(containers);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                Sample sample = parseSample(line);
                if (sample != null) {
                    totalCpuPercent += sample.cpuPercent;
                    totalMemBytes += sample.memUsageBytes;
                    totalNetRxBytes += sample.netRxBytes;
                    totalNetTxBytes += sample.netTxBytes;
                    totalBlockReadBytes += sample.blockReadBytes;
                    totalBlockWriteBytes += sample.blockWriteBytes;
                }
            }
        }

        boolean completed = process.waitFor(SAMPLE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("docker stats command timed out");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("docker stats failed with exit code: " + exitCode);
        }

        // Convert aggregated values to canonical units
        // CPU: percentage to millicores (assuming percentage is per-core, e.g., 150% = 1.5 cores)
        int cpuMillicores = (int) (totalCpuPercent * 10.0);

        // Network and block I/O are cumulative totals since container start
        // For rate calculation, we'd need to track deltas over time
        // For now, return zeros for I/O rates as we don't have delta tracking
        // TODO: Implement delta tracking for I/O rates between samples
        long ioReadBytesPerSec = 0;
        long ioWriteBytesPerSec = 0;
        long netBytesPerSec = 0;
        long iops = 0;

        logger.log(Level.FINE, "Sampled {0} containers: cpu={1}mCPU, mem={2}B",
                new Object[]{containers.size(), cpuMillicores, totalMemBytes});

        return UtilizationSnapshot.actual(
                cpuMillicores,
                totalMemBytes,
                0, // Total I/O bytes per sec (not available without delta tracking)
                iops,
                netBytesPerSec,
                ioReadBytesPerSec,
                ioWriteBytesPerSec
        );
    }

    /**
     * Parses a docker stats output line.
     * Format: CPU%|MemUsage|NetIO|BlockIO
     */
    private Sample parseSample(String line) {
        try {
            String[] parts = line.split("\\|");
            if (parts.length < 4) {
                return null;
            }

            double cpuPercent = parseCpu(parts[0]);
            long memBytes = parseMemUsage(parts[1]);
            Pair net = parseIoPair(parts[2]);
            Pair block = parseIoPair(parts[3]);

            return new Sample(cpuPercent, memBytes, net.firstBytes, net.secondBytes,
                    block.firstBytes, block.secondBytes);
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to parse docker stats output ''{0}'': {1}",
                    new Object[]{line, e.getMessage()});
            return null;
        }
    }

    private double parseCpu(String raw) {
        String cleaned = raw.replace("%", "").trim();
        if (cleaned.isEmpty()) {
            return 0.0d;
        }
        return Double.parseDouble(cleaned);
    }

    private long parseMemUsage(String raw) {
        String[] parts = raw.split("/");
        if (parts.length == 0) {
            return 0L;
        }
        return parseSizeToBytes(parts[0]);
    }

    private Pair parseIoPair(String raw) {
        String[] parts = raw.split("/");
        if (parts.length < 2) {
            long value = parseSizeToBytes(raw);
            return new Pair(value, 0L);
        }
        long first = parseSizeToBytes(parts[0]);
        long second = parseSizeToBytes(parts[1]);
        return new Pair(first, second);
    }

    private long parseSizeToBytes(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || value.equals("--")) {
            return 0L;
        }
        value = value.replace(",", "");
        int split = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == '-')) {
                split = i;
                break;
            }
        }
        if (split == -1) {
            return Math.round(Double.parseDouble(value));
        }
        double number = Double.parseDouble(value.substring(0, split));
        String unit = value.substring(split).trim().toUpperCase(Locale.ENGLISH);
        double multiplier;
        switch (unit) {
            case "B":
                multiplier = 1d;
                break;
            case "KB":
            case "KIB":
            case "K":
                multiplier = 1024d;
                break;
            case "MB":
            case "MIB":
            case "M":
                multiplier = BYTES_PER_MB;
                break;
            case "GB":
            case "GIB":
            case "G":
                multiplier = BYTES_PER_MB * 1024d;
                break;
            case "TB":
            case "TIB":
            case "T":
                multiplier = BYTES_PER_MB * 1024d * 1024d;
                break;
            default:
                multiplier = BYTES_PER_MB;
                break;
        }
        return Math.round(number * multiplier);
    }

    private void logThrottledWarning(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarningTime > WARNING_THROTTLE_MS) {
            logger.log(Level.WARNING, message);
            lastWarningTime = now;
        } else {
            logger.log(Level.FINE, message);
        }
    }

    private static final class Sample {
        final double cpuPercent;
        final long memUsageBytes;
        final long netRxBytes;
        final long netTxBytes;
        final long blockReadBytes;
        final long blockWriteBytes;

        Sample(double cpuPercent, long memUsageBytes, long netRxBytes, long netTxBytes,
               long blockReadBytes, long blockWriteBytes) {
            this.cpuPercent = Math.max(0d, cpuPercent);
            this.memUsageBytes = Math.max(0L, memUsageBytes);
            this.netRxBytes = Math.max(0L, netRxBytes);
            this.netTxBytes = Math.max(0L, netTxBytes);
            this.blockReadBytes = Math.max(0L, blockReadBytes);
            this.blockWriteBytes = Math.max(0L, blockWriteBytes);
        }
    }

    private static final class Pair {
        final long firstBytes;
        final long secondBytes;

        Pair(long firstBytes, long secondBytes) {
            this.firstBytes = Math.max(0L, firstBytes);
            this.secondBytes = Math.max(0L, secondBytes);
        }
    }
}
