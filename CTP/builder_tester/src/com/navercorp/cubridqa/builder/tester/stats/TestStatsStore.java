package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONObject;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
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

    private static final Logger logger = Logger.getLogger(TestStatsStore.class.getName());
    private static final String SNAPSHOT_FILE = "test_stats.snapshot.json.gz";
    private static final String WAL_FILE = "test_stats.jl.gz";
    private static final long DEFAULT_SNAPSHOT_INTERVAL_SECONDS = 300; // 5 minutes

    private final Path profilesDir;
    private final Path snapshotPath;
    private final Path walPath;
    private final long snapshotIntervalSeconds;
    private final Predictor predictor;

    // WAL components (injected)
    private final WALSegmentWriter walWriter;
    private final WALManifest manifest;
    private final Path walDir;

    // Request journal (optional, may be null)
    private volatile RequestJournal requestJournal;

    // Node hardware for latest.json.gz export (optional, may be null)
    private volatile JSONObject nodeHardwareJson;

    private final ConcurrentHashMap<String, TestStats> statsMap;
    private final ScheduledExecutorService snapshotCoordinator;

    private volatile Instant lastSnapshotTime;

    /**
     * Constructs a new TestStatsStore with the given configuration.
     *
     * @param profilesDir             Directory containing snapshot and WAL files
     * @param snapshotIntervalSeconds Interval (in seconds) between snapshots
     * @param walWriter               WAL segment writer (must be started separately)
     * @param manifest                WAL manifest manager
     * @param walDir                  Directory containing WAL segments
     */
    public TestStatsStore(Path profilesDir, long snapshotIntervalSeconds,
                         WALSegmentWriter walWriter, WALManifest manifest, Path walDir) {
        this.profilesDir = profilesDir;
        this.snapshotPath = profilesDir.resolve(SNAPSHOT_FILE);
        this.walPath = profilesDir.resolve(WAL_FILE); // Legacy path, kept for backward compatibility
        this.snapshotIntervalSeconds = snapshotIntervalSeconds;
        this.predictor = new Predictor();
        this.walWriter = walWriter;
        this.manifest = manifest;
        this.walDir = walDir;
        this.statsMap = new ConcurrentHashMap<>();
        this.lastSnapshotTime = Instant.EPOCH;
        this.snapshotCoordinator = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-coordinator");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Constructs a TestStatsStore with default snapshot interval.
     * 
     * @param profilesDir Directory containing snapshot and WAL files
     * @param walWriter   WAL segment writer (must be started separately)
     * @param manifest    WAL manifest manager
     * @param walDir      Directory containing WAL segments
     */
    public TestStatsStore(Path profilesDir, WALSegmentWriter walWriter,
                         WALManifest manifest, Path walDir) {
        this(profilesDir, DEFAULT_SNAPSHOT_INTERVAL_SECONDS, walWriter, manifest, walDir);
    }

    /**
     * Initializes the store by loading snapshot and replaying WAL, then starts
     * periodic snapshot coordinator.
     */
    public void start() {
        // Load MANIFEST first
        manifest.load();

        // Load snapshot
        loadSnapshot();

        // Import from latest.json.gz if statsMap is empty (new node bootstrap)
        if (statsMap.isEmpty()) {
            importFromLatest();
        }

        // Replay WAL segments from MANIFEST
        replayWAL();

        // Start coordinator (single-threaded, enforces order)
        snapshotCoordinator.scheduleAtFixedRate(
                this::coordinatedSnapshot,
                snapshotIntervalSeconds,
                snapshotIntervalSeconds,
                TimeUnit.SECONDS
        );
        logger.info("[TestStatsStore] Snapshot coordinator started, interval: " + snapshotIntervalSeconds + "s");
    }

    /**
     * Stops the snapshot coordinator and writes a final snapshot.
     *
     * <p>CRITICAL: Ensures coordinator finishes before returning, so writer can be safely stopped.
     */
    public void stop() {
        // Shutdown coordinator first
        snapshotCoordinator.shutdown();
        
        try {
            // Optional: force a last snapshot before shutdown
            coordinatedSnapshot();
            
            // Wait for coordinator to finish (with timeout)
            if (!snapshotCoordinator.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("[TestStatsStore] Coordinator did not terminate within timeout, forcing shutdown");
                snapshotCoordinator.shutdownNow();
                // Wait a bit more for forced shutdown
                snapshotCoordinator.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            snapshotCoordinator.shutdownNow();
            Thread.currentThread().interrupt();
            logger.warning("[TestStatsStore] Coordinator shutdown interrupted");
        } catch (Exception e) {
            logger.log(Level.WARNING, "[TestStatsStore] Error during final snapshot on shutdown", e);
        }
        
        // Coordinator is now stopped - safe to stop writer
    }

    /**
     * Sets the request journal for per-request observation tracking.
     *
     * @param requestJournal The request journal to use
     */
    public void setRequestJournal(RequestJournal requestJournal) {
        this.requestJournal = requestJournal;
    }

    /**
     * Sets the node hardware information for latest.json.gz export.
     *
     * @param nodeHardwareJson JSON representation of node hardware
     */
    public void setNodeHardwareJson(JSONObject nodeHardwareJson) {
        this.nodeHardwareJson = nodeHardwareJson;
    }

    /**
     * Records a new observation from live test execution.
     * Writes to WAL, request journal, and updates in-memory statistics.
     *
     * @param obs The observation to record
     */
    public void recordObservation(TestObservation obs) {
        // Write to WAL first (async, non-blocking)
        walWriter.append(obs);

        // Append to request journal if available
        if (requestJournal != null) {
            requestJournal.append(obs);
        }

        // Update in-memory statistics
        applyInMemory(obs);
    }

    /**
     * Applies an observation to in-memory statistics only.
     * Used by replay and import paths (does NOT write to WAL or request journal).
     *
     * @param obs The observation to apply
     */
    private void applyInMemory(TestObservation obs) {
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
     * Imports test statistics from latest.json.gz if present.
     *
     * <p>This allows a new node to bootstrap test statistics without replaying
     * the entire WAL history. The import only happens if statsMap is empty.
     */
    private void importFromLatest() {
        Path latest = profilesDir.resolve("latest.json.gz");
        if (!Files.exists(latest)) {
            logger.info("[Import] No latest.json.gz found, skipping import");
            return;
        }

        try (InputStream in = new GZIPInputStream(Files.newInputStream(latest));
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String json = sb.toString();
            JSONObject root = new JSONObject(json);
            JSONObject tests = root.getJSONObject("tests");

            int imported = 0;
            for (String key : tests.keySet()) {
                JSONObject tj = tests.getJSONObject(key);
                TestStats s = TestStats.fromLatestJSON(key, tj);
                statsMap.put(key, s);
                imported++;
            }

            // Align replay boundary to latest.json "generated_at" to avoid reprocessing
            String genAt = root.optString("generated_at", null);
            if (genAt != null) {
                lastSnapshotTime = Instant.parse(genAt);
            }

            logger.info("[Import] Bootstrapped from latest.json.gz (" + imported + " tests, generated_at=" +
                    root.optString("generated_at", "unknown") + ")");

        } catch (Exception e) {
            logger.log(Level.WARNING, "[Import] latest.json.gz import failed", e);
            // Non-fatal: continue with empty statsMap or try snapshot
        }
    }

    /**
     * Loads the snapshot file if present, populating the in-memory map.
     */
    private void loadSnapshot() {
        if (!Files.exists(snapshotPath)) {
            logger.info("[TestStatsStore] No snapshot found at " + snapshotPath + ", starting fresh");
            lastSnapshotTime = Instant.EPOCH; // Ensure it's set even if no snapshot
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
            logger.info("[TestStatsStore] Loaded snapshot: " + statsMap.size() + " tests, snapshot time: " + snapshotTime);

        } catch (Exception e) {
            logger.log(Level.WARNING, "[TestStatsStore] Failed to load snapshot: " + e.getMessage(), e);
            lastSnapshotTime = Instant.EPOCH; // Fallback: start from beginning
        }
    }

    /**
     * Replays WAL entries from MANIFEST's retained segments.
     *
     * <p>CRITICAL: Iterates segments from MANIFEST (newer than includes_up_to_wal),
     * not a single file. Implements "best-effort tail" for plaintext JSONL.
     */
    private void replayWAL() {
        List<String> segments = manifest.getSegmentsToReplay();
        
        if (segments == null || segments.isEmpty()) {
            // Check for legacy single WAL file (backward compatibility)
            if (Files.exists(walPath)) {
                logger.warning("[TestStatsStore] Found legacy WAL file, replaying once: " + walPath);
                ReplayResult result = replaySegment(walPath);
                logger.info("[TestStatsStore] Legacy WAL replay: " + result.replayed + " replayed, " + 
                        result.skipped + " skipped, " + result.partial + " partial");
                
                // Optionally rename legacy file so it won't be replayed again
                try {
                    Path legacyBackup = walPath.resolveSibling(walPath.getFileName().toString() + ".legacy");
                    Files.move(walPath, legacyBackup, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("[TestStatsStore] Renamed legacy WAL to: " + legacyBackup.getFileName());
                } catch (IOException e) {
                    logger.log(Level.WARNING, "[TestStatsStore] Failed to rename legacy WAL file", e);
                    // Non-fatal: will replay again on next boot, but that's acceptable
                }
            } else {
                logger.info("[TestStatsStore] No WAL segments to replay");
            }
            return;
        }

        int totalReplayed = 0;
        int totalSkipped = 0;
        int totalPartial = 0;

        for (String segmentName : segments) {
            Path segmentPath = walDir.resolve(segmentName);
            if (!Files.exists(segmentPath)) {
                logger.warning("[TestStatsStore] Segment missing: " + segmentName);
                continue;
            }

            ReplayResult result = replaySegment(segmentPath);
            totalReplayed += result.replayed;
            totalSkipped += result.skipped;
            totalPartial += result.partial;
        }

        logger.info(String.format("[TestStatsStore] WAL replay complete: %d replayed, %d skipped, %d partial",
                totalReplayed, totalSkipped, totalPartial));
    }

    /**
     * Replays a single WAL segment with partial-line-safe parsing.
     *
     * <p>CRITICAL: Implements "best-effort tail" for plaintext JSONL:
     * - Reads lines with buffered reader
     * - For each line: try JSON parse
     * - On failure only for the final line: ignore and stop (assume truncated line)
     * - This prevents one torn write from poisoning the whole file
     *
     * <p>Supports both plaintext (.jl) and gzipped (.jl.gz) files for legacy compatibility.
     *
     * @param segmentPath Path to the segment file
     * @return ReplayResult with counts
     */
    private ReplayResult replaySegment(Path segmentPath) {
        int replayed = 0;
        int skipped = 0;
        int partial = 0;

        // Detect if file is gzipped (legacy format)
        boolean isGzipped = segmentPath.getFileName().toString().endsWith(".gz");

        try (InputStream in = Files.newInputStream(segmentPath);
             InputStream wrapped = isGzipped ? new java.util.zip.GZIPInputStream(in) : in;
             BufferedReader reader = new BufferedReader(new InputStreamReader(wrapped, java.nio.charset.StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }

            for (int i = 0; i < lines.size(); i++) {
                line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }

                boolean isLastLine = (i == lines.size() - 1);

                try {
                    JSONObject obj = new JSONObject(line);
                    TestObservation obs = TestObservation.fromJSON(obj);

                    // Skip observations older than last snapshot
                    if (obs.getTimestamp().isBefore(lastSnapshotTime)) {
                        skipped++;
                        continue;
                    }

                    applyInMemory(obs); // Replay: in-memory only, no WAL/journal write
                    replayed++;

                } catch (Exception e) {
                    // CRITICAL: Only ignore parse failures on the final line (assume truncated)
                    if (isLastLine) {
                        partial++;
                        logger.fine("[TestStatsStore] Ignoring partial final line (likely truncated): " +
                                (line.length() > 100 ? line.substring(0, 100) + "..." : line));
                        break; // Stop at partial line
                    } else {
                        // For non-final lines, log but continue (shouldn't happen with valid data)
                        logger.log(Level.WARNING, "[Replay] Non-final parse error in " + segmentPath.getFileName(), e);
                        // Continue processing remaining entries
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[Replay] Failed to replay segment: " + segmentPath, e);
        }

        return new ReplayResult(replayed, skipped, partial);
    }

    /**
     * Result of replaying a single segment.
     */
    private static class ReplayResult {
        final int replayed;
        final int skipped;
        final int partial;

        ReplayResult(int replayed, int skipped, int partial) {
            this.replayed = replayed;
            this.skipped = skipped;
            this.partial = partial;
        }
    }

    /**
     * Single-threaded coordinator that enforces the required order:
     * 1. Write snapshot (fsync file → rename → fsync dir)
     * 2. Rotate WAL (get closed segment name)
     * 3. Update MANIFEST (fsync file → rename → fsync dir)
     * 4. Cleanup old segments
     *
     * <p>CRITICAL: All steps must execute in this exact order in a single thread.
     * If any step fails, do not cleanup; leave retained list as-is. Next cycle will retry.
     */
    private void coordinatedSnapshot() {
        try {
            // 1) Write snapshot (fsync file → rename → fsync dir)
            Path snapshotPath = writeSnapshotInternal();
            if (snapshotPath == null) {
                logger.warning("[Coordinator] Snapshot write failed, skipping rotation and manifest update");
                return;
            }

            // 2) Rotate WAL; get closed segment (durable)
            String closedSegment = walWriter.rotateNow();
            String newOpenWal = walWriter.getCurrentSegmentName();

            // 3) Update MANIFEST (fsync file → rename → fsync dir)
            manifest.updateAfterSnapshot(
                    snapshotPath.getFileName().toString(),
                    closedSegment,
                    newOpenWal
            );

            // 4) Cleanup old segments (post-manifest)
            List<String> toCleanup = manifest.getSegmentsToCleanup();
            int deletedCount = 0;
            int failedCount = 0;

            for (String segmentName : toCleanup) {
                try {
                    Path segmentPath = walDir.resolve(segmentName);
                    if (Files.deleteIfExists(segmentPath)) {
                        deletedCount++;
                    }
                } catch (IOException e) {
                    failedCount++;
                    logger.log(Level.WARNING, "[Coordinator] Failed to delete segment: " + segmentName, e);
                    // Continue with other segments
                }
            }

            // 5) Write latest.json.gz (after MANIFEST update, before cleanup)
            writeLatestFromStatsMap(nodeHardwareJson);

            // 6) Cleanup old segments (after latest.json.gz written)
            if (!toCleanup.isEmpty()) {
                manifest.removeSegments(toCleanup);
                logger.info(String.format("[Coordinator] Cleanup: %d deleted, %d failed", deletedCount, failedCount));
            }

            logger.info(String.format("[Coordinator] Snapshot complete: %d tests, closed=%s, new=%s",
                    statsMap.size(), closedSegment, newOpenWal));

        } catch (Throwable t) {
            logger.log(Level.SEVERE, "[Coordinator] Snapshot coordination failed", t);
            // Keep going; we'll try again next tick
        }
    }

    /**
     * Writes latest.json.gz file from current in-memory statistics.
     *
     * <p>This file provides a human-readable and importable snapshot that
     * can be copied to a new node to bootstrap test statistics without
     * replaying the entire WAL history.
     *
     * @param nodeHardwareJson JSON representation of node hardware (optional)
     */
    private void writeLatestFromStatsMap(JSONObject nodeHardwareJson) {
        Path latestPath = profilesDir.resolve("latest.json.gz");

        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("generated_at", Instant.now().toString());

            if (nodeHardwareJson != null) {
                root.put("node_hardware", nodeHardwareJson);
            }

            JSONObject tests = new JSONObject();
            for (Map.Entry<String, TestStats> entry : statsMap.entrySet()) {
                String key = entry.getKey();
                TestStats s = entry.getValue();
                JSONObject tj;
                synchronized (s) {
                    tj = s.toLatestJSON();
                }
                tests.put(key, tj);
            }
            root.put("tests", tests);

            byte[] jsonBytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                gos.write(jsonBytes);
            }
            byte[] gzBytes = baos.toByteArray();

            Path tmp = latestPath.resolveSibling("latest.json.gz.tmp");
            try (FileChannel ch = FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.write(java.nio.ByteBuffer.wrap(gzBytes));
                ch.force(true);
            }

            Files.move(tmp, latestPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            WALUtils.fsyncDirectory(latestPath.getParent());

            logger.info("[Latest] Written: " + statsMap.size() + " tests to latest.json.gz");

        } catch (Exception e) {
            logger.log(Level.WARNING, "[Latest] Failed to write latest.json.gz", e);
            // Non-fatal: WAL still preserves data
        }
    }

    /**
     * Writes a snapshot to disk atomically.
     *
     * @return The final snapshot path on success, null on failure
     */
    private Path writeSnapshotInternal() {
        try {
            // Ensure directory exists
            Files.createDirectories(profilesDir);

            // Write to temp file first
            Path tempPath = snapshotPath.resolveSibling(SNAPSHOT_FILE + ".tmp");

            // Write JSON content to memory first, then compress and write via FileChannel
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

            // Compress JSON content
            byte[] jsonBytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                gos.write(jsonBytes);
            }
            byte[] compressedBytes = baos.toByteArray();

            // Write compressed content via FileChannel with fsync
            try (FileChannel channel = FileChannel.open(
                    tempPath,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {

                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(compressedBytes);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                channel.force(true); // fsync file data + metadata
            }

            // Atomic rename
            Files.move(tempPath, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            // CRITICAL: Fsync snapshot's parent directory (ensures rename is durable)
            WALUtils.fsyncDirectory(snapshotPath.getParent());

            lastSnapshotTime = Instant.now();

            logger.info("[Snapshot] Written: " + statsMap.size() + " tests at " + lastSnapshotTime);
            return snapshotPath;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "[Snapshot] Failed to write snapshot", e);
            return null;
        }
    }
}
