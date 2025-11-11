package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Migration tool to convert old WAL format to new segmented WAL format.
 *
 * <p>This tool:
 * 1. Reads old snapshot.json.gz (aggregated TestStats)
 * 2. Reads old test_stats.jl.gz (JSONL observations)
 * 3. Extracts all observations and deduplicates
 * 4. Re-aggregates using TestStats logic
 * 5. Writes new format: snapshot.json.gz, WAL segments, MANIFEST.json
 */
public class WALFormatMigrator {

    private static final String OLD_SNAPSHOT_FILE = "test_stats.snapshot.json.gz";
    private static final String OLD_WAL_FILE = "test_stats.jl.gz";
    private static final String NEW_SNAPSHOT_FILE = "test_stats.snapshot.json.gz";
    private static final String MANIFEST_FILE = "MANIFEST.json";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: WALFormatMigrator <old_profiles_dir> <new_profiles_dir>");
            System.exit(1);
        }

        Path oldDir = Paths.get(args[0]);
        Path newDir = Paths.get(args[1]);

        System.out.println("WAL Format Migration Tool");
        System.out.println("=========================");
        System.out.println("Old directory: " + oldDir);
        System.out.println("New directory: " + newDir);
        System.out.println();

        WALFormatMigrator migrator = new WALFormatMigrator();
        migrator.migrate(oldDir, newDir);
    }

    /**
     * Performs the migration from old format to new format.
     */
    public void migrate(Path oldDir, Path newDir) throws Exception {
        // Ensure new directory exists
        Files.createDirectories(newDir);
        Path walDir = newDir.resolve("wal");
        Files.createDirectories(walDir);

        // Step 1: Collect all observations from old files
        System.out.println("Step 1: Reading old files...");
        List<TestObservation> allObservations = new ArrayList<>();

        // Read old snapshot (contains aggregated stats, but we'll reconstruct from WAL)
        Path oldSnapshotPath = oldDir.resolve(OLD_SNAPSHOT_FILE);
        Instant snapshotTimestamp = Instant.EPOCH;
        if (Files.exists(oldSnapshotPath)) {
            System.out.println("  Reading old snapshot: " + oldSnapshotPath);
            JSONObject oldSnapshot = readGzippedJson(oldSnapshotPath);
            if (oldSnapshot.has("timestamp")) {
                snapshotTimestamp = Instant.parse(oldSnapshot.getString("timestamp"));
            }
        }

        // Read old WAL file (contains individual observations)
        Path oldWalPath = oldDir.resolve(OLD_WAL_FILE);
        if (Files.exists(oldWalPath)) {
            System.out.println("  Reading old WAL: " + oldWalPath);
            int count = readOldWal(oldWalPath, allObservations);
            System.out.println("  Read " + count + " observations from WAL");
        } else {
            System.out.println("  No old WAL file found: " + oldWalPath);
        }

        // If no observations found, we can still migrate the snapshot
        // The snapshot already contains aggregated data, so we'll use that
        if (allObservations.isEmpty()) {
            System.out.println("  No observations in WAL file.");
            System.out.println("  Will migrate snapshot data only (no WAL segment needed).");
        }

        // Step 2: Process observations or use snapshot data
        Map<String, TestStats> statsMap = new ConcurrentHashMap<>();
        
        if (!allObservations.isEmpty()) {
            // Step 2a: Deduplicate observations
            System.out.println();
            System.out.println("Step 2: Deduplicating observations...");
            List<TestObservation> uniqueObservations = deduplicate(allObservations);
            System.out.println("  Total observations: " + allObservations.size());
            System.out.println("  Unique observations: " + uniqueObservations.size());
            System.out.println("  Duplicates removed: " + (allObservations.size() - uniqueObservations.size()));

            // Step 3: Re-aggregate using TestStats
            System.out.println();
            System.out.println("Step 3: Re-aggregating statistics...");
            for (TestObservation obs : uniqueObservations) {
                statsMap.computeIfAbsent(obs.getTestKey(), k -> new TestStats(obs.getTestKey()))
                        .addObservation(obs);
            }
            System.out.println("  Aggregated " + statsMap.size() + " unique test keys");
        } else {
            // No observations - use snapshot data directly
            System.out.println();
            System.out.println("Step 2: Using snapshot data (no WAL observations to process)...");
            if (Files.exists(oldSnapshotPath)) {
                JSONObject oldSnapshot = readGzippedJson(oldSnapshotPath);
                if (oldSnapshot.has("tests")) {
                    JSONObject tests = oldSnapshot.getJSONObject("tests");
                    // Convert old snapshot TestStats format to new format
                    // The structure is already compatible, so we can use it directly
                    System.out.println("  Found " + tests.length() + " tests in snapshot");
                    // We'll write the snapshot as-is since it's already in the correct format
                }
            }
        }

        // Step 4: Write new snapshot
        System.out.println();
        System.out.println("Step 4: Writing new snapshot...");
        Path newSnapshotPath = newDir.resolve(NEW_SNAPSHOT_FILE);
        
        if (!statsMap.isEmpty()) {
            // Write from re-aggregated observations
            writeNewSnapshot(newSnapshotPath, statsMap, snapshotTimestamp);
        } else {
            // No observations - copy/convert snapshot directly
            if (Files.exists(oldSnapshotPath)) {
                JSONObject oldSnapshot = readGzippedJson(oldSnapshotPath);
                writeSnapshotFromJson(newSnapshotPath, oldSnapshot);
            } else {
                // Create empty snapshot
                writeNewSnapshot(newSnapshotPath, statsMap, snapshotTimestamp);
            }
        }
        System.out.println("  Written: " + newSnapshotPath);

        // Step 5: Write WAL segments (plaintext JSONL) - only if we have observations
        String segmentName = null;
        if (!allObservations.isEmpty()) {
            System.out.println();
            System.out.println("Step 5: Writing WAL segments...");
            List<TestObservation> uniqueObservations = deduplicate(allObservations);
            segmentName = generateSegmentName(Instant.now(), 1);
            Path segmentPath = walDir.resolve(segmentName);
            int written = writeWalSegment(segmentPath, uniqueObservations);
            System.out.println("  Written " + written + " observations to: " + segmentPath);
        } else {
            System.out.println();
            System.out.println("Step 5: Skipping WAL segment (no observations to write)");
        }

        // Step 6: Create MANIFEST
        System.out.println();
        System.out.println("Step 6: Creating MANIFEST...");
        Path manifestPath = newDir.resolve(MANIFEST_FILE);
        createManifest(manifestPath, newSnapshotPath, segmentName);
        System.out.println("  Written: " + manifestPath);

        System.out.println();
        System.out.println("Migration completed successfully!");
    }

    /**
     * Reads old snapshot file (gzipped JSON).
     */
    private JSONObject readGzippedJson(Path path) throws Exception {
        try (InputStream fis = Files.newInputStream(path);
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gis, StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        }
    }

    /**
     * Reads old WAL file (gzipped JSONL) and extracts observations.
     */
    private int readOldWal(Path walPath, List<TestObservation> observations) throws Exception {
        int count = 0;
        try (InputStream fis = Files.newInputStream(walPath);
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gis, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    JSONObject json = new JSONObject(line);
                    TestObservation obs = TestObservation.fromJSON(json);
                    observations.add(obs);
                    count++;
                } catch (Exception e) {
                    System.err.println("  Warning: Failed to parse observation: " + e.getMessage());
                    // Continue processing
                }
            }
        }
        return count;
    }

    /**
     * Deduplicates observations based on testKey only, keeping the most recent execution with metrics_complete=true.
     * 
     * <p>For observations with the same testKey:
     * 1. If any have metrics_complete=true, keep the most recent one with metrics_complete=true
     * 2. If none have metrics_complete=true, keep the most recent one overall
     * This ensures only one execution per test is preserved, preferring complete metrics.
     */
    private List<TestObservation> deduplicate(List<TestObservation> observations) {
        // First, group observations by testKey
        Map<String, List<TestObservation>> byTestKey = new LinkedHashMap<>();
        for (TestObservation obs : observations) {
            String testKey = obs.getTestKey() != null ? obs.getTestKey() : "";
            byTestKey.computeIfAbsent(testKey, k -> new ArrayList<>()).add(obs);
        }

        // For each testKey, select the best observation
        Map<String, TestObservation> best = new LinkedHashMap<>();
        for (Map.Entry<String, List<TestObservation>> entry : byTestKey.entrySet()) {
            String testKey = entry.getKey();
            List<TestObservation> obsList = entry.getValue();

            // Separate observations with and without complete metrics
            List<TestObservation> withComplete = new ArrayList<>();
            List<TestObservation> withoutComplete = new ArrayList<>();
            
            for (TestObservation obs : obsList) {
                if (obs.isMetricsComplete()) {
                    withComplete.add(obs);
                } else {
                    withoutComplete.add(obs);
                }
            }

            // Choose the best observation
            TestObservation selected;
            if (!withComplete.isEmpty()) {
                // Prefer the most recent one with metrics_complete=true
                selected = withComplete.stream()
                    .max((o1, o2) -> {
                        if (o1.getTimestamp() == null) return -1;
                        if (o2.getTimestamp() == null) return 1;
                        return o1.getTimestamp().compareTo(o2.getTimestamp());
                    })
                    .orElse(null);
            } else {
                // No complete metrics - use the most recent one overall
                selected = withoutComplete.stream()
                    .max((o1, o2) -> {
                        if (o1.getTimestamp() == null) return -1;
                        if (o2.getTimestamp() == null) return 1;
                        return o1.getTimestamp().compareTo(o2.getTimestamp());
                    })
                    .orElse(null);
            }

            if (selected != null) {
                best.put(testKey, selected);
            }
        }

        return new ArrayList<>(best.values());
    }

    /**
     * Writes new snapshot in the new format.
     */
    private void writeNewSnapshot(Path snapshotPath, Map<String, TestStats> statsMap, Instant snapshotTimestamp) throws Exception {
        JSONObject root = new JSONObject();
        root.put("timestamp", (snapshotTimestamp != Instant.EPOCH) ? 
                snapshotTimestamp.toString() : Instant.now().toString());

        JSONObject tests = new JSONObject();
        for (Map.Entry<String, TestStats> entry : statsMap.entrySet()) {
            TestStats stats = entry.getValue();
            synchronized (stats) {
                tests.put(entry.getKey(), stats.toJSON());
            }
        }
        root.put("tests", tests);

        // Write compressed snapshot
        byte[] jsonBytes = root.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(snapshotPath.toFile());
             GZIPOutputStream gos = new GZIPOutputStream(fos)) {
            gos.write(jsonBytes);
        }
    }

    /**
     * Writes snapshot from existing JSON (for cases where we only have snapshot, no WAL).
     */
    private void writeSnapshotFromJson(Path snapshotPath, JSONObject snapshotJson) throws Exception {
        // Ensure timestamp is set
        if (!snapshotJson.has("timestamp")) {
            snapshotJson.put("timestamp", Instant.now().toString());
        }
        
        // Write compressed snapshot
        byte[] jsonBytes = snapshotJson.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(snapshotPath.toFile());
             GZIPOutputStream gos = new GZIPOutputStream(fos)) {
            gos.write(jsonBytes);
        }
    }

    /**
     * Writes a WAL segment in plaintext JSONL format.
     */
    private int writeWalSegment(Path segmentPath, List<TestObservation> observations) throws Exception {
        int count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(segmentPath, StandardCharsets.UTF_8)) {
            for (TestObservation obs : observations) {
                writer.write(obs.toJsonLine());
                writer.newLine();
                count++;
            }
        }
        return count;
    }

    /**
     * Creates MANIFEST.json in the new format.
     */
    private void createManifest(Path manifestPath, Path snapshotPath, String segmentName) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("v", 1);

        // Snapshot info
        JSONObject snapshot = new JSONObject();
        snapshot.put("path", snapshotPath.getFileName().toString());
        snapshot.put("created_at", Instant.now().toString());
        if (segmentName != null) {
            snapshot.put("includes_up_to_wal", segmentName);
        } else {
            // No WAL segment - snapshot includes all data
            snapshot.put("includes_up_to_wal", "");
        }
        manifest.put("snapshot", snapshot);

        // Open WAL segment (empty since we're creating initial state)
        manifest.put("open_wal", "");

        // Retained segments (empty since snapshot includes everything)
        manifest.put("retained", new org.json.JSONArray());

        // Write manifest
        try (BufferedWriter writer = Files.newBufferedWriter(manifestPath, StandardCharsets.UTF_8)) {
            writer.write(manifest.toString(2));
        }
    }

    /**
     * Generates a segment name in the new format.
     */
    private String generateSegmentName(Instant timestamp, long sequence) {
        String dateTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(timestamp);
        return String.format("test_stats-%s-%06d.jl", dateTime, sequence % 1000000);
    }
}

