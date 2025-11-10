package com.navercorp.cubridqa.builder.exec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;

final class DockerStatsCollector {
    private static final String FORMAT = "{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}";
    private static final long DEFAULT_INTERVAL_MS = 600L;
    private static final double BYTES_PER_MB = 1024d * 1024d;

    private final String containerName;
    private final Logger logger;
    private final long intervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final StatsAccumulator accumulator = new StatsAccumulator();
    private Thread workerThread;

    DockerStatsCollector(String containerName, Logger logger) {
        this(containerName, logger, DEFAULT_INTERVAL_MS);
    }

    DockerStatsCollector(String containerName, Logger logger, long intervalMs) {
        this.containerName = containerName;
        this.logger = logger;
        this.intervalMs = Math.max(200L, intervalMs);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this::runLoop, "docker-stats-" + containerName);
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    public StatsSummary stopAndSummarize() {
        running.set(false);
        if (workerThread != null) {
            try {
                workerThread.join(1500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        StatsSummary summary = accumulator.snapshot();
        if (summary.hasSamples()) {
            logger.log(Level.INFO, "Collected {0} stat samples for container {1}",
                new Object[]{summary.getSampleCount(), containerName});
        } else {
            logger.log(Level.WARNING, "No stat samples collected for container {0}", containerName);
        }
        return summary;
    }

    private void runLoop() {
        int consecutiveFailures = 0;
        int maxConsecutiveFailures = 5;  // Allow 5 failures before giving up (handles startup race)

        while (running.get()) {
            boolean collected = collectSample();
            if (!collected) {
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    logger.log(Level.FINE, "Stopping stats collection for {0} after {1} consecutive failures",
                        new Object[]{containerName, consecutiveFailures});
                    break;
                }
            } else {
                consecutiveFailures = 0;  // Reset on success
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running.set(false);
    }

    private boolean collectSample() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "docker", "stats", "--no-stream", "--format", FORMAT, containerName);
            pb.redirectErrorStream(true);
            process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                int exitCode = process.waitFor();

                if (line == null || line.trim().isEmpty()) {
                    return exitCode == 0;
                }

                Sample sample = parseSample(line.trim());
                if (sample != null) {
                    accumulator.add(sample);
                    // Log first sample to confirm collection is working
                    if (accumulator.samples == 1) {
                        logger.log(Level.INFO, "Started collecting stats for container {0} (CPU: {1}%, Mem: {2} MB)",
                            new Object[]{containerName, String.format("%.1f", sample.cpuPercent),
                                        String.format("%.1f", sample.memUsageMb)});
                    }
                }
                return exitCode == 0;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.log(Level.FINE, "docker stats sampling failed for {0}: {1}",
                new Object[]{containerName, e.getMessage()});
            return false;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private Sample parseSample(String line) {
        try {
            String[] parts = line.split("\\|");
            if (parts.length < 4) {
                return null;
            }

            double cpuPercent = parseCpu(parts[0]);
            double memMb = parseMemUsage(parts[1]);
            Pair net = parseIoPair(parts[2]);
            Pair block = parseIoPair(parts[3]);

            long timestamp = System.nanoTime();
            return new Sample(cpuPercent, memMb, net.firstBytes, net.secondBytes,
                block.firstBytes, block.secondBytes, timestamp);
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

    private double parseMemUsage(String raw) {
        String[] parts = raw.split("/");
        if (parts.length == 0) {
            return 0.0d;
        }
        return bytesToMb(parseSizeToBytes(parts[0]));
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

    private double bytesToMb(long bytes) {
        return bytes / BYTES_PER_MB;
    }

    static final class StatsSummary {
        private final int samples;
        private final double cpuSum;
        private final double cpuMax;
        private final double memSumMb;
        private final double memMaxMb;
        private final long firstSampleNs;
        private final long lastSampleNs;
        private final long firstNetRxBytes;
        private final long firstNetTxBytes;
        private final long lastNetRxBytes;
        private final long lastNetTxBytes;
        private final long firstBlockReadBytes;
        private final long firstBlockWriteBytes;
        private final long lastBlockReadBytes;
        private final long lastBlockWriteBytes;

        StatsSummary(StatsAccumulator acc) {
            this.samples = acc.samples;
            this.cpuSum = acc.cpuSum;
            this.cpuMax = acc.cpuMax;
            this.memSumMb = acc.memSumMb;
            this.memMaxMb = acc.memMaxMb;
            this.firstSampleNs = acc.firstSampleNs;
            this.lastSampleNs = acc.lastSampleNs;
            this.firstNetRxBytes = acc.firstNetRxBytes;
            this.firstNetTxBytes = acc.firstNetTxBytes;
            this.lastNetRxBytes = acc.lastNetRxBytes;
            this.lastNetTxBytes = acc.lastNetTxBytes;
            this.firstBlockReadBytes = acc.firstBlockReadBytes;
            this.firstBlockWriteBytes = acc.firstBlockWriteBytes;
            this.lastBlockReadBytes = acc.lastBlockReadBytes;
            this.lastBlockWriteBytes = acc.lastBlockWriteBytes;
        }

        boolean hasSamples() {
            return samples > 0;
        }

        int getSampleCount() {
            return samples;
        }

        void applyTo(TestExecutionMetrics.Builder metricsBuilder, long durationMs) {
            if (!hasSamples()) {
                return;
            }

            double durationSeconds = computeDurationSeconds(durationMs);
            if (durationSeconds <= 0d) {
                durationSeconds = Math.max(durationMs / 1000d, 0.001d);
            }

            double cpuMean = cpuSum / samples;
            double memMean = memSumMb / samples;

            long netDeltaBytes = Math.max(0L, (lastNetRxBytes - firstNetRxBytes)) +
                Math.max(0L, (lastNetTxBytes - firstNetTxBytes));
            long blockReadDeltaBytes = Math.max(0L, lastBlockReadBytes - firstBlockReadBytes);
            long blockWriteDeltaBytes = Math.max(0L, lastBlockWriteBytes - firstBlockWriteBytes);

            double netThroughputMb = netDeltaBytes / BYTES_PER_MB;
            double ioMb = (blockReadDeltaBytes + blockWriteDeltaBytes) / BYTES_PER_MB;

            long readMb = Math.round(blockReadDeltaBytes / BYTES_PER_MB);
            long writeMb = Math.round(blockWriteDeltaBytes / BYTES_PER_MB);

            metricsBuilder.cpuPctMean(cpuMean)
                .cpuPctPeak(cpuMax)
                .memMbMean(memMean)
                .memMbPeak(memMaxMb)
                .netMbPerSecMean(netThroughputMb / Math.max(durationSeconds, 0.001d))
                .ioMbPerSecMean(ioMb / Math.max(durationSeconds, 0.001d))
                .bytesReadMb(readMb)
                .bytesWriteMb(writeMb)
                .metricsComplete(true);
        }

        private double computeDurationSeconds(long durationMsFallback) {
            if (samples <= 1 || lastSampleNs <= firstSampleNs) {
                return durationMsFallback / 1000d;
            }
            long nanos = lastSampleNs - firstSampleNs;
            return nanos / 1_000_000_000d;
        }
    }

    private static final class StatsAccumulator {
        private int samples = 0;
        private double cpuSum = 0d;
        private double cpuMax = 0d;
        private double memSumMb = 0d;
        private double memMaxMb = 0d;
        private long firstSampleNs = -1L;
        private long lastSampleNs = -1L;
        private long firstNetRxBytes = -1L;
        private long firstNetTxBytes = -1L;
        private long lastNetRxBytes = 0L;
        private long lastNetTxBytes = 0L;
        private long firstBlockReadBytes = -1L;
        private long firstBlockWriteBytes = -1L;
        private long lastBlockReadBytes = 0L;
        private long lastBlockWriteBytes = 0L;

        synchronized void add(Sample sample) {
            if (sample == null) {
                return;
            }
            if (samples == 0) {
                firstSampleNs = sample.timestampNs;
                firstNetRxBytes = sample.netRxBytes;
                firstNetTxBytes = sample.netTxBytes;
                firstBlockReadBytes = sample.blockReadBytes;
                firstBlockWriteBytes = sample.blockWriteBytes;
            }

            samples++;
            cpuSum += sample.cpuPercent;
            cpuMax = Math.max(cpuMax, sample.cpuPercent);
            memSumMb += sample.memUsageMb;
            memMaxMb = Math.max(memMaxMb, sample.memUsageMb);

            lastSampleNs = sample.timestampNs;
            lastNetRxBytes = sample.netRxBytes;
            lastNetTxBytes = sample.netTxBytes;
            lastBlockReadBytes = sample.blockReadBytes;
            lastBlockWriteBytes = sample.blockWriteBytes;
        }

        synchronized StatsSummary snapshot() {
            return new StatsSummary(this);
        }
    }

    private static final class Sample {
        final double cpuPercent;
        final double memUsageMb;
        final long netRxBytes;
        final long netTxBytes;
        final long blockReadBytes;
        final long blockWriteBytes;
        final long timestampNs;

        Sample(double cpuPercent, double memUsageMb, long netRxBytes, long netTxBytes,
               long blockReadBytes, long blockWriteBytes, long timestampNs) {
            this.cpuPercent = Math.max(0d, cpuPercent);
            this.memUsageMb = Math.max(0d, memUsageMb);
            this.netRxBytes = Math.max(0L, netRxBytes);
            this.netTxBytes = Math.max(0L, netTxBytes);
            this.blockReadBytes = Math.max(0L, blockReadBytes);
            this.blockWriteBytes = Math.max(0L, blockWriteBytes);
            this.timestampNs = timestampNs;
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

