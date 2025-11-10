package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * In-memory store of aggregated test statistics with persistent snapshots.
 *
 * <p>This class maintains a {@code ConcurrentHashMap<String, TestStats>} keyed by
 * testKey. On startup, it loads the most recent snapshot (if present) and replays
 * WAL entries since the snapshot timestamp. Periodically (default 5 minutes), it
 * writes a compact snapshot to disk.</p>
 *
 * <p>The store provides a {@link #predict(String, BuildContext, NodeHardware)} API
 * used by the /score endpoint and scheduler to estimate test resource demands.</p>
 *
 * <p>Thread-safe for concurrent reads and updates.</p>
 */
public class TestStatsStore {

    private static final String SNAPSHOT_FILE = "test_stats.snapshot.json.gz";
    private static final String WAL_FILE = "test_stats.jl.gz";
    private static final long DEFAULT_SNAPSHOT_INTERVAL_SECONDS = 300; // 5 minutes

    private final Path profilesDir;
    private final Path snapshotPath;
    private final Path walPath;
    private final long snapshotIntervalSeconds;
    private final Predictor predictor;

    private final ConcurrentHashMap<String, TestStats> statsMap;
    private final ScheduledExecutorService snapshotScheduler;

    private volatile Instant lastSnapshotTime;

    /**
     * Constructs a new TestStatsStore with the given configuration.
     *
     * @param profilesDir             Directory containing snapshot and WAL files
     * @param snapshotIntervalSeconds Interval (in seconds) between snapshots
     */
    public TestStatsStore(Path profilesDir, long snapshotIntervalSeconds) {
        this.profilesDir = profilesDir;
        this.snapshotPath = profilesDir.resolve(SNAPSHOT_FILE);
        this.walPath = profilesDir.resolve(WAL_FILE);
        this.snapshotIntervalSeconds = snapshotIntervalSeconds;
        this.predictor = new Predictor();
        this.statsMap = new ConcurrentHashMap<>();
        this.lastSnapshotTime = Instant.EPOCH;
        this.snapshotScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TestStatsStore-SnapshotWriter");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Constructs a TestStatsStore with default snapshot interval.
     */
    public TestStatsStore(Path profilesDir) {
        this(profilesDir, DEFAULT_SNAPSHOT_INTERVAL_SECONDS);
    }

    /**
     * Initializes the store by loading snapshot and replaying WAL, then starts
     * periodic snapshot writer.
     */
    public void start() {
        loadSnapshot();
        replayWAL();
        scheduleSnapshots();
    }

    /**
     * Stops the snapshot scheduler and writes a final snapshot.
     */
    public void stop() {
        snapshotScheduler.shutdown();
        try {
            if (!snapshotScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                snapshotScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            snapshotScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        writeSnapshot(); // final snapshot on shutdown
    }

    /**
     * Records a new observation, updating the in-memory statistics.
     *
     * @param obs The observation to record
     */
    public void recordObservation(TestObservation obs) {
        String testKey = obs.getTestKey();
        TestStats stats = statsMap.computeIfAbsent(testKey, TestStats::new);
        synchronized (stats) {
            stats.addObservation(obs);
        }
    }

    /**
     * Predicts resource demand for a test.
     *
     * @param testKey Test identifier
     * @param context Build context (commit, baseline)
     * @param hw      Target node hardware
     * @return Predicted demand with confidence
     */
    public PredictedDemand predict(String testKey, BuildContext context, NodeHardware hw) {
        TestStats stats = statsMap.get(testKey);
        return predictor.predict(testKey, stats, context, hw);
    }

    /**
     * Returns the current statistics for a test, or null if not present.
     */
    public TestStats getStats(String testKey) {
        return statsMap.get(testKey);
    }

    /**
     * Returns the total number of tracked tests.
     */
    public int getTestCount() {
        return statsMap.size();
    }

    /**
     * Returns the total number of observations across all tests.
     */
    public long getTotalObservations() {
        return statsMap.values().stream()
                .mapToLong(TestStats::getObservationCount)
                .sum();
    }

    /**
     * Loads the snapshot file if present, populating the in-memory map.
     */
    private void loadSnapshot() {
        if (!Files.exists(snapshotPath)) {
            System.out.println("[TestStatsStore] No snapshot found at " + snapshotPath + ", starting fresh");
            return;
        }

        try (InputStream fis = Files.newInputStream(snapshotPath);
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gis, "UTF-8"))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONObject root = new JSONObject(sb.toString());
            Instant snapshotTime = Instant.parse(root.getString("timestamp"));
            JSONObject tests = root.getJSONObject("tests");

            for (String testKey : tests.keySet()) {
                JSONObject testObj = tests.getJSONObject(testKey);
                TestStats stats = TestStats.fromJSON(testObj);
                statsMap.put(testKey, stats);
            }

            lastSnapshotTime = snapshotTime;
            System.out.println("[TestStatsStore] Loaded snapshot: " + statsMap.size() + " tests, snapshot time: " + snapshotTime);

        } catch (Exception e) {
            System.err.println("[TestStatsStore] Failed to load snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Replays WAL entries since the last snapshot timestamp.
     */
    private void replayWAL() {
        if (!Files.exists(walPath)) {
            System.out.println("[TestStatsStore] No WAL found at " + walPath + ", nothing to replay");
            return;
        }

        int replayedCount = 0;
        int skippedCount = 0;

        try (InputStream fis = Files.newInputStream(walPath);
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gis, "UTF-8"))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    JSONObject obj = new JSONObject(line);
                    TestObservation obs = TestObservation.fromJSON(obj);

                    // Skip observations older than last snapshot
                    if (obs.getTimestamp().isBefore(lastSnapshotTime)) {
                        skippedCount++;
                        continue;
                    }

                    recordObservation(obs);
                    replayedCount++;

                } catch (Exception e) {
                    System.err.println("[TestStatsStore] Failed to parse WAL entry: " + e.getMessage());
                    // Continue processing remaining entries
                }
            }

            System.out.println("[TestStatsStore] WAL replay complete: " + replayedCount + " replayed, " + skippedCount + " skipped");

        } catch (Exception e) {
            System.err.println("[TestStatsStore] Failed to replay WAL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Writes a snapshot to disk atomically.
     */
    private void writeSnapshot() {
        try {
            // Ensure directory exists
            Files.createDirectories(profilesDir);

            // Write to temp file first
            Path tempPath = snapshotPath.resolveSibling(SNAPSHOT_FILE + ".tmp");

            try (OutputStream fos = Files.newOutputStream(tempPath);
                 GZIPOutputStream gos = new GZIPOutputStream(fos);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gos, "UTF-8"))) {

                JSONObject root = new JSONObject();
                root.put("timestamp", Instant.now().toString());

                JSONObject tests = new JSONObject();
                for (Map.Entry<String, TestStats> entry : statsMap.entrySet()) {
                    TestStats stats = entry.getValue();
                    synchronized (stats) {
                        tests.put(entry.getKey(), stats.toJSON());
                    }
                }
                root.put("tests", tests);

                writer.write(root.toString());
            }

            // Atomic rename
            Files.move(tempPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastSnapshotTime = Instant.now();

            System.out.println("[TestStatsStore] Snapshot written: " + statsMap.size() + " tests at " + lastSnapshotTime);

        } catch (Exception e) {
            System.err.println("[TestStatsStore] Failed to write snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Schedules periodic snapshot writing.
     */
    private void scheduleSnapshots() {
        snapshotScheduler.scheduleAtFixedRate(
                this::writeSnapshot,
                snapshotIntervalSeconds,
                snapshotIntervalSeconds,
                TimeUnit.SECONDS
        );
        System.out.println("[TestStatsStore] Snapshot scheduler started, interval: " + snapshotIntervalSeconds + "s");
    }
}
