package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final String FORMAT = "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}";
    private static final double BYTES_PER_MB = 1024d * 1024d;
    private static final long SAMPLE_TIMEOUT_MS = 2000; // 2 second timeout
    private final BuilderConfig config;
    private final boolean enabled;
    private final String containerPattern;
    private boolean dockerAvailable = true;
    private long lastWarningTime = 0;
    private static final long WARNING_THROTTLE_MS = 60000; // Warn once per minute

    // Delta tracking state for I/O rate calculation (thread-safe)
    private final ConcurrentHashMap<String, ContainerSample> previousSamples = new ConcurrentHashMap<>();
    private final long minIntervalMs;
    private final long cacheTtlMs;
    private static final int MAX_CACHED_CONTAINERS = 100;
    
    // Cached snapshot for high-frequency polling (reduce docker overhead)
    private volatile UtilizationSnapshot cachedSnapshot = UtilizationSnapshot.empty();
    private volatile long lastSnapshotTime = 0;
    private static final long SNAPSHOT_CACHE_TTL_MS = 2000; // Cache for 2 seconds

    // Background sampling configuration
    private final Thread backgroundSampler;
    private volatile boolean isRunning = true;

    public ActualSampler(BuilderConfig config) {
        this.config = config;
        // Use reflection to access config properties since BuilderConfig doesn't expose them
        this.enabled = Boolean.parseBoolean(getConfigProperty(config, "actual_sampling_enabled", "true"));
        this.containerPattern = getConfigProperty(config, "test_container_pattern", "cubrid-test-");

        // Delta tracking configuration
        this.minIntervalMs = Long.parseLong(getConfigProperty(config, "actual_sampling_min_interval_ms", "500"));
        long cacheTtlSeconds = Long.parseLong(getConfigProperty(config, "actual_sampling_cache_ttl_seconds", "60"));
        this.cacheTtlMs = cacheTtlSeconds * 1000L;

        logger.log(Level.INFO,
                "ActualSampler initialized: enabled={0}, containerPattern=''{1}'', minInterval={2}ms, cacheTTL={3}s",
                new Object[]{enabled, containerPattern, minIntervalMs, cacheTtlSeconds});
        
        // Initialize background sampler thread if enabled
        if (enabled) {
            this.backgroundSampler = new Thread(this::runBackgroundSampling, "ActualSampler-Background");
            this.backgroundSampler.setDaemon(true);
            this.backgroundSampler.start();
        } else {
            this.backgroundSampler = null;
        }
    }

    private void runBackgroundSampling() {
        while (isRunning) {
            try {
                performSampling();
                // Sleep for cache TTL (minus a small buffer) to keep cache fresh
                Thread.sleep(Math.max(100, SNAPSHOT_CACHE_TTL_MS - 100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Background sampling failed", e);
                try {
                    Thread.sleep(5000); // Backoff on error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void performSampling() {
        if (!dockerAvailable) return;

        try {
            List<String> containers = listRunningTestContainers();
            if (containers.isEmpty()) {
                cachedSnapshot = UtilizationSnapshot.empty();
            } else {
                cachedSnapshot = aggregateContainerMetrics(containers);
            }
            lastSnapshotTime = System.currentTimeMillis();
        } catch (Exception e) {
            logThrottledWarning("Failed to sample actual utilization: " + e.getMessage());
        }
    }

    public void stop() {
        isRunning = false;
        if (backgroundSampler != null) {
            backgroundSampler.interrupt();
        }
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
        return getLatestSnapshot();
    }

    /**
     * Alias for sampleCurrentUtilization for API consistency.
     * Returns the latest cached snapshot immediately.
     */
    public UtilizationSnapshot getLatestSnapshot() {
        // If disabled or unavailable, return empty immediately
        if (!enabled || !dockerAvailable) {
            return UtilizationSnapshot.empty();
        }

        // Return the cached snapshot updated by the background thread
        // If the background thread hasn't run yet (e.g. startup), this returns empty() which is safe
        return cachedSnapshot;
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
     * Uses delta tracking to calculate I/O and network rates.
     */
    private UtilizationSnapshot aggregateContainerMetrics(List<String> containers)
            throws IOException, InterruptedException {

        double totalCpuPercent = 0.0;
        long totalMemBytes = 0;
        long totalIoReadBytesPerSec = 0;
        long totalIoWriteBytesPerSec = 0;
        long totalNetBytesPerSec = 0;

        // Track active containers for cleanup
        Set<String> activeContainers = new HashSet<>(containers);

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

                // Parse line format: Name|CPU%|MemUsage|NetIO|BlockIO
                String[] parts = line.split("\\|", 2);
                if (parts.length < 2) {
                    continue;
                }

                String containerName = parts[0].trim();
                String statsLine = parts[1];

                Sample sample = parseSample(containerName, statsLine);
                if (sample != null) {
                    // Aggregate instant metrics
                    totalCpuPercent += sample.cpuPercent;
                    totalMemBytes += sample.memUsageBytes;

                    // Calculate I/O rates using delta tracking
                    IoRates rates = calculateIoRates(containerName, sample);
                    totalIoReadBytesPerSec += rates.ioReadBytesPerSec;
                    totalIoWriteBytesPerSec += rates.ioWriteBytesPerSec;
                    totalNetBytesPerSec += rates.netBytesPerSec;
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

        // Cleanup stale cache entries
        cleanupStaleEntries(activeContainers);

        // Convert aggregated values to canonical units
        // CPU: percentage to millicores (assuming percentage is per-core, e.g., 150% = 1.5 cores)
        int cpuMillicores = (int) (totalCpuPercent * 10.0);

        // IOPS: estimate based on I/O bytes and typical block size (4KB)
        // This is a rough approximation - more accurate IOPS would require kernel-level metrics
        long iops = (totalIoReadBytesPerSec + totalIoWriteBytesPerSec) / 4096L;

        logger.log(Level.FINE,
                "Sampled {0} containers: cpu={1}mCPU, mem={2}B, ioRead={3}B/s, ioWrite={4}B/s, net={5}B/s, iops={6}",
                new Object[]{containers.size(), cpuMillicores, totalMemBytes,
                        totalIoReadBytesPerSec, totalIoWriteBytesPerSec, totalNetBytesPerSec, iops});

        return UtilizationSnapshot.actual(
                cpuMillicores,
                totalMemBytes,
                totalIoReadBytesPerSec + totalIoWriteBytesPerSec, // Total I/O bytes per sec
                iops,
                totalNetBytesPerSec,
                totalIoReadBytesPerSec,
                totalIoWriteBytesPerSec
        );
    }

    /**
     * Calculates I/O rates for a container using delta tracking.
     *
     * @param containerName Name of the container
     * @param current Current sample from docker stats
     * @return IoRates with calculated bytes/sec, or ZERO if no previous sample exists
     */
    private IoRates calculateIoRates(String containerName, Sample current) {
        long now = System.currentTimeMillis();
        ContainerSample prev = previousSamples.get(containerName);

        // No baseline yet - store current sample and return zeros
        if (prev == null) {
            ContainerSample newSample = new ContainerSample(
                    containerName, now,
                    current.blockReadBytes, current.blockWriteBytes,
                    current.netRxBytes, current.netTxBytes);
            previousSamples.put(containerName, newSample);
            logger.log(Level.FINE, "No previous sample for container {0}, storing baseline", containerName);
            return IoRates.ZERO;
        }

        long timeDelta = now - prev.timestampMs;

        // Too soon after last sample - wait for more time to elapse
        if (timeDelta < minIntervalMs) {
            logger.log(Level.FINE,
                    "Time delta too small for container {0}: {1}ms < {2}ms",
                    new Object[]{containerName, timeDelta, minIntervalMs});
            return IoRates.ZERO;
        }

        // Detect container restart - counters decreased (Docker stats reset)
        if (current.blockReadBytes < prev.blockReadBytes ||
                current.blockWriteBytes < prev.blockWriteBytes ||
                current.netRxBytes < prev.netRxBytes ||
                current.netTxBytes < prev.netTxBytes) {
            logger.log(Level.INFO,
                    "Container {0} appears to have restarted (counters decreased), resetting baseline",
                    containerName);
            ContainerSample newSample = new ContainerSample(
                    containerName, now,
                    current.blockReadBytes, current.blockWriteBytes,
                    current.netRxBytes, current.netTxBytes);
            previousSamples.put(containerName, newSample);
            return IoRates.ZERO;
        }

        // Calculate deltas
        long readDelta = current.blockReadBytes - prev.blockReadBytes;
        long writeDelta = current.blockWriteBytes - prev.blockWriteBytes;
        long netRxDelta = current.netRxBytes - prev.netRxBytes;
        long netTxDelta = current.netTxBytes - prev.netTxBytes;

        // Calculate rates (bytes per second)
        double readBps = (readDelta * 1000.0) / timeDelta;
        double writeBps = (writeDelta * 1000.0) / timeDelta;
        double netBps = ((netRxDelta + netTxDelta) * 1000.0) / timeDelta;

        // Update baseline with current sample
        ContainerSample newSample = new ContainerSample(
                containerName, now,
                current.blockReadBytes, current.blockWriteBytes,
                current.netRxBytes, current.netTxBytes);
        previousSamples.put(containerName, newSample);

        logger.log(Level.FINE,
                "Container {0}: I/O rates - read={1}B/s, write={2}B/s, net={3}B/s (delta={4}ms)",
                new Object[]{containerName, Math.round(readBps), Math.round(writeBps),
                        Math.round(netBps), timeDelta});

        return new IoRates(readBps, writeBps, netBps);
    }

    /**
     * Cleans up stale entries from the sample cache.
     * Removes containers that no longer exist or entries older than TTL.
     *
     * @param activeContainers Set of currently running container names
     */
    private void cleanupStaleEntries(Set<String> activeContainers) {
        long now = System.currentTimeMillis();
        int sizeBefore = previousSamples.size();

        // Remove entries for containers that no longer exist or are too old
        previousSamples.entrySet().removeIf(entry -> {
            boolean containerGone = !activeContainers.contains(entry.getKey());
            boolean tooOld = (now - entry.getValue().timestampMs) > cacheTtlMs;
            return containerGone || tooOld;
        });

        // Safety: cap size to prevent unbounded growth
        if (previousSamples.size() > MAX_CACHED_CONTAINERS) {
            logger.log(Level.WARNING,
                    "Sample cache exceeded max size ({0}), removing oldest entries",
                    MAX_CACHED_CONTAINERS);

            // Remove oldest entries
            previousSamples.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getValue().timestampMs))
                    .limit(previousSamples.size() - MAX_CACHED_CONTAINERS)
                    .map(Map.Entry::getKey)
                    .forEach(previousSamples::remove);
        }

        int sizeAfter = previousSamples.size();
        if (sizeBefore > sizeAfter) {
            logger.log(Level.FINE, "Cleaned up {0} stale sample entries ({1} -> {2})",
                    new Object[]{sizeBefore - sizeAfter, sizeBefore, sizeAfter});
        }
    }

    /**
     * Parses a docker stats output line (without container name prefix).
     * Format: CPU%|MemUsage|NetIO|BlockIO
     * Note: Container name is passed separately and already extracted from docker stats output.
     *
     * @param containerName Name of the container for this sample
     * @param line Docker stats output line (CPU%|MemUsage|NetIO|BlockIO)
     * @return Parsed sample or null if parsing fails
     */
    private Sample parseSample(String containerName, String line) {
        try {
            String[] parts = line.split("\\|");
            if (parts.length < 4) {
                return null;
            }

            double cpuPercent = parseCpu(parts[0]);
            long memBytes = parseMemUsage(parts[1]);
            Pair net = parseIoPair(parts[2]);
            Pair block = parseIoPair(parts[3]);

            return new Sample(containerName, cpuPercent, memBytes, net.firstBytes, net.secondBytes,
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
        final String containerName;
        final double cpuPercent;
        final long memUsageBytes;
        final long netRxBytes;
        final long netTxBytes;
        final long blockReadBytes;
        final long blockWriteBytes;

        Sample(String containerName, double cpuPercent, long memUsageBytes, long netRxBytes, long netTxBytes,
               long blockReadBytes, long blockWriteBytes) {
            this.containerName = containerName;
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

    /**
     * Stores a previous container sample for delta calculation.
     */
    private static final class ContainerSample {
        final String containerName;
        final long timestampMs;
        final long blockReadBytes;
        final long blockWriteBytes;
        final long netRxBytes;
        final long netTxBytes;

        ContainerSample(String containerName, long timestampMs,
                       long blockReadBytes, long blockWriteBytes,
                       long netRxBytes, long netTxBytes) {
            this.containerName = containerName;
            this.timestampMs = timestampMs;
            this.blockReadBytes = blockReadBytes;
            this.blockWriteBytes = blockWriteBytes;
            this.netRxBytes = netRxBytes;
            this.netTxBytes = netTxBytes;
        }
    }

    /**
     * Stores calculated I/O rates for a container.
     */
    private static final class IoRates {
        static final IoRates ZERO = new IoRates(0, 0, 0);

        final long ioReadBytesPerSec;
        final long ioWriteBytesPerSec;
        final long netBytesPerSec;

        IoRates(double readBps, double writeBps, double netBps) {
            this.ioReadBytesPerSec = Math.max(0L, Math.round(readBps));
            this.ioWriteBytesPerSec = Math.max(0L, Math.round(writeBps));
            this.netBytesPerSec = Math.max(0L, Math.round(netBps));
        }
    }
}
