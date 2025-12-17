package com.navercorp.cubridqa.builder.exec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.navercorp.cubridqa.builder.tester.stats.TestExecutionMetrics;

final class DockerStatsCollector {
    private static final String FORMAT = "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}";
    private static final long DEFAULT_INTERVAL_MS = 600L;
    private static final double BYTES_PER_MB = 1024d * 1024d;
    private static final long AVERAGE_BLOCK_SIZE_BYTES = 4096L; // 4KB average block size for IOPS estimation
    private static final Logger MUX_LOGGER = Logger.getLogger(DockerStatsCollector.class.getName());
    private static final DockerStatsMux MUX = new DockerStatsMux();

    private final String containerName;
    private final Logger logger;
    private final long intervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final StatsAccumulator accumulator = new StatsAccumulator();

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
            MUX.register(containerName, this, intervalMs);
        }
    }

    public StatsSummary stopAndSummarize() {
        if (running.compareAndSet(true, false)) {
            MUX.unregister(containerName, this);
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

    private static final class ParsedSample {
        final String containerName;
        final Sample sample;

        ParsedSample(String containerName, Sample sample) {
            this.containerName = containerName;
            this.sample = sample;
        }
    }

    private ParsedSample parseMuxSample(String line) {
        try {
            String[] parts = line.split("\\|");
            if (parts.length < 5) {
                return null;
            }

            String name = parts[0].trim();
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            if (name.isEmpty()) {
                return null;
            }

            double cpuPercent = parseCpu(parts[1]);
            double memMb = parseMemUsage(parts[2]);
            Pair net = parseIoPair(parts[3]);
            Pair block = parseIoPair(parts[4]);

            long timestamp = System.nanoTime();
            Sample sample = new Sample(cpuPercent, memMb, net.firstBytes, net.secondBytes,
                block.firstBytes, block.secondBytes, timestamp);
            return new ParsedSample(name, sample);
        } catch (Exception e) {
            MUX_LOGGER.log(Level.FINE, "Failed to parse docker stats output ''{0}'': {1}",
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

            // Calculate IOPS: estimate based on total block I/O bytes per second divided by average block size
            long totalBlockBytes = blockReadDeltaBytes + blockWriteDeltaBytes;
            double blockBytesPerSecond = totalBlockBytes / Math.max(durationSeconds, 0.001d);
            double iopsMean = blockBytesPerSecond / AVERAGE_BLOCK_SIZE_BYTES;

            metricsBuilder.cpuPctMean(cpuMean)
                .cpuPctPeak(cpuMax)
                .memMbMean(memMean)
                .memMbPeak(memMaxMb)
                .netMbPerSecMean(netThroughputMb / Math.max(durationSeconds, 0.001d))
                .ioMbPerSecMean(ioMb / Math.max(durationSeconds, 0.001d))
                .iopsMean(iopsMean)
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

    private static final class DockerStatsMux implements Runnable {
        private final Object lock = new Object();
        private final Map<String, CollectorRegistration> collectors = new HashMap<>();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private Thread worker;

        void register(String containerName, DockerStatsCollector collector, long intervalMs) {
            if (containerName == null || containerName.trim().isEmpty() || collector == null) {
                return;
            }
            synchronized (lock) {
                collectors.put(containerName, new CollectorRegistration(collector, intervalMs));
                ensureStartedLocked();
                lock.notifyAll();
                if (worker != null) {
                    worker.interrupt();
                }
            }
        }

        void unregister(String containerName, DockerStatsCollector collector) {
            if (containerName == null || containerName.trim().isEmpty() || collector == null) {
                return;
            }
            synchronized (lock) {
                CollectorRegistration current = collectors.get(containerName);
                if (current != null && current.collector == collector) {
                    collectors.remove(containerName);
                    if (worker != null) {
                        worker.interrupt();
                    }
                }
            }
        }

        private void ensureStartedLocked() {
            if (started.compareAndSet(false, true)) {
                worker = new Thread(this, "docker-stats-mux");
                worker.setDaemon(true);
                worker.start();
            }
        }

        @Override
        public void run() {
            while (true) {
                Snapshot snapshot = snapshotCollectors();
                if (snapshot == null || snapshot.collectors.isEmpty()) {
                    waitForCollectors();
                    continue;
                }
                collectOnce(snapshot.collectors);
                try {
                    Thread.sleep(snapshot.intervalMs);
                } catch (InterruptedException ignored) {
                    // Re-check active collectors and interval promptly.
                }
            }
        }

        private void waitForCollectors() {
            synchronized (lock) {
                while (collectors.isEmpty()) {
                    try {
                        lock.wait(10_000L);
                    } catch (InterruptedException ignored) {
                        // Keep waiting; shutdown isn't supported (daemon thread).
                    }
                }
            }
        }

        private Snapshot snapshotCollectors() {
            synchronized (lock) {
                if (collectors.isEmpty()) {
                    return null;
                }
                Map<String, CollectorRegistration> copy = new HashMap<>(collectors);
                long minInterval = DEFAULT_INTERVAL_MS;
                for (CollectorRegistration reg : copy.values()) {
                    if (reg.intervalMs > 0L) {
                        minInterval = Math.min(minInterval, reg.intervalMs);
                    }
                }
                return new Snapshot(copy, Math.max(200L, minInterval));
            }
        }

        private void collectOnce(Map<String, CollectorRegistration> snapshot) {
            if (snapshot == null || snapshot.isEmpty()) {
                return;
            }

            List<String> cmd = new ArrayList<>(5 + snapshot.size());
            cmd.add("docker");
            cmd.add("stats");
            cmd.add("--no-stream");
            cmd.add("--format");
            cmd.add(FORMAT);
            cmd.addAll(snapshot.keySet());

            Process process = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) {
                            continue;
                        }

                        // Any collector instance can parse lines; parsing is stateless.
                        ParsedSample parsed = snapshot.values().iterator().next().collector.parseMuxSample(line);
                        if (parsed == null || parsed.sample == null || parsed.containerName == null) {
                            continue;
                        }
                        CollectorRegistration reg = snapshot.get(parsed.containerName);
                        if (reg == null) {
                            continue;
                        }
                        reg.collector.acceptSample(parsed.sample);
                    }
                }

                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception e) {
                MUX_LOGGER.log(Level.FINE, "docker stats mux sampling failed: {0}", e.getMessage());
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                }
            }
        }

        private static final class Snapshot {
            final Map<String, CollectorRegistration> collectors;
            final long intervalMs;

            Snapshot(Map<String, CollectorRegistration> collectors, long intervalMs) {
                this.collectors = collectors;
                this.intervalMs = intervalMs;
            }
        }

        private static final class CollectorRegistration {
            final DockerStatsCollector collector;
            final long intervalMs;

            CollectorRegistration(DockerStatsCollector collector, long intervalMs) {
                this.collector = collector;
                this.intervalMs = Math.max(200L, intervalMs);
            }
        }
    }

    private void acceptSample(Sample sample) {
        if (!running.get() || sample == null) {
            return;
        }
        accumulator.add(sample);
        if (accumulator.samples == 1) {
            logger.log(Level.INFO,
                "Started collecting stats for container {0} (CPU: {1}%, Mem: {2} MB, I/O Read: {3} MB, Write: {4} MB)",
                new Object[]{
                    containerName,
                    String.format("%.1f", sample.cpuPercent),
                    String.format("%.1f", sample.memUsageMb),
                    String.format("%.1f", sample.blockReadBytes / (1024.0 * 1024.0)),
                    String.format("%.1f", sample.blockWriteBytes / (1024.0 * 1024.0))
                });
        }
    }
}
