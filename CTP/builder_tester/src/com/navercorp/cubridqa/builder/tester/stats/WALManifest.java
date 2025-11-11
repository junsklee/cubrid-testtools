package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MANIFEST file manager for crash-safe WAL coordination.
 *
 * <p>The MANIFEST is the single source of truth that tracks:
 * - Which snapshot file is current
 * - Which WAL segments have been incorporated into the snapshot
 * - Which WAL segments need to be replayed on startup
 *
 * <p>Thread-safe for concurrent reads, synchronized for writes.
 */
public class WALManifest {

    private static final Logger logger = Logger.getLogger(WALManifest.class.getName());
    private static final String MANIFEST_FILE = "MANIFEST.json";
    private static final int CURRENT_VERSION = 1;

    private final Path profilesDir;
    private final Path manifestPath;

    // Manifest state (synchronized access)
    private volatile ManifestData data;

    public WALManifest(Path profilesDir) {
        this.profilesDir = profilesDir;
        this.manifestPath = profilesDir.resolve(MANIFEST_FILE);
        this.data = new ManifestData();
    }

    /**
     * Loads the manifest from disk with fallback to backup.
     *
     * <p>Load order:
     * 1. Try primary MANIFEST.json
     * 2. If corrupt, try MANIFEST.json.bak
     * 3. If both fail, start fresh
     */
    public synchronized void load() {
        Path backupPath = manifestPath.resolveSibling(MANIFEST_FILE + ".bak");

        try {
            // Try primary with fallback to backup
            String content = WALUtils.readWithFallback(manifestPath, backupPath);
            JSONObject json = new JSONObject(content);
            data = ManifestData.fromJSON(json);

            // Verify snapshot checksum if available
            if (!data.snapshotChecksum.isEmpty() && !data.snapshotPath.isEmpty()) {
                Path snapshotPath = profilesDir.resolve(data.snapshotPath);
                if (Files.exists(snapshotPath)) {
                    try {
                        String actualChecksum = WALUtils.computeSHA256(snapshotPath);
                        if (!actualChecksum.equals(data.snapshotChecksum)) {
                            logger.severe("Snapshot checksum mismatch! Expected=" +
                                    data.snapshotChecksum + ", actual=" + actualChecksum);
                            // Could fall back to older snapshot or start fresh
                            // For now, log and continue (data may still be usable)
                        }
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Could not verify snapshot checksum", e);
                    }
                }
            }

            logger.info("Loaded MANIFEST: snapshot=" + data.snapshotPath +
                    ", open_wal=" + data.openWalSegment +
                    ", retained=" + data.retainedSegments.size());

        } catch (IOException e) {
            logger.log(Level.WARNING, "Both primary and backup MANIFEST corrupt, starting fresh", e);
            data = new ManifestData();
        }
    }

    /**
     * Atomically updates the manifest with new snapshot information.
     *
     * <p>Write order (crash-safe):
     * 1. Create backup of current MANIFEST
     * 2. Compute checksum of snapshot file
     * 3. Write new manifest to temp file
     * 4. Fsync temp file
     * 5. Atomic rename to MANIFEST.json
     * 6. Fsync parent directory (ensures rename is durable)
     *
     * @param snapshotPath Path to the new snapshot file
     * @param includesUpToWal Last WAL segment incorporated in snapshot
     * @param newOpenWal New open WAL segment for future observations
     */
    public synchronized void updateAfterSnapshot(
            String snapshotPath,
            String includesUpToWal,
            String newOpenWal) throws IOException {

        // 1. Create backup of current MANIFEST
        Path backupPath = manifestPath.resolveSibling(MANIFEST_FILE + ".bak");
        WALUtils.createBackup(manifestPath, backupPath);

        // 2. Compute checksum of snapshot
        Path snapshotFullPath = profilesDir.resolve(snapshotPath);
        String snapshotChecksum = "";
        if (Files.exists(snapshotFullPath)) {
            try {
                snapshotChecksum = WALUtils.computeSHA256(snapshotFullPath);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to compute snapshot checksum", e);
            }
        }

        // 3. Create new manifest data
        ManifestData newData = new ManifestData();
        newData.version = CURRENT_VERSION;
        newData.snapshotPath = snapshotPath;
        newData.snapshotChecksum = snapshotChecksum;
        newData.snapshotCreatedAt = Instant.now();
        newData.includesUpToWal = includesUpToWal;
        newData.openWalSegment = newOpenWal;

        // Retain only the new open segment (previous segments now in snapshot)
        newData.retainedSegments.clear();
        newData.retainedSegments.add(newOpenWal);

        // 4. Write with atomic guarantees (fsync + directory fsync)
        String jsonContent = newData.toJSON().toString(2);
        WALUtils.atomicWrite(manifestPath, jsonContent);

        // 5. Update in-memory state
        this.data = newData;

        logger.info("Updated MANIFEST: snapshot=" + snapshotPath +
                ", checksum=" + snapshotChecksum.substring(0, Math.min(16, snapshotChecksum.length())) +
                ", includes_up_to=" + includesUpToWal +
                ", new_open=" + newOpenWal);
    }

    /**
     * Adds a WAL segment to the retained list (called when rolling WAL).
     */
    public synchronized void addRetainedSegment(String segmentName) throws IOException {
        if (!data.retainedSegments.contains(segmentName)) {
            data.retainedSegments.add(segmentName);
            persist();
        }
    }

    /**
     * Updates the open WAL segment (called when rolling to new segment).
     */
    public synchronized void setOpenWalSegment(String segmentName) throws IOException {
        data.openWalSegment = segmentName;
        persist();
    }

    /**
     * Returns the current manifest data (immutable copy).
     */
    public synchronized ManifestData getData() {
        return data.copy();
    }

    /**
     * Returns the list of WAL segments that need to be replayed.
     *
     * <p>These are segments created AFTER the snapshot's includesUpToWal.
     */
    public synchronized List<String> getSegmentsToReplay() {
        if (data.includesUpToWal == null || data.includesUpToWal.isEmpty()) {
            // No snapshot yet, replay all retained segments
            return new ArrayList<>(data.retainedSegments);
        }

        // Return segments newer than includesUpToWal
        List<String> toReplay = new ArrayList<>();
        boolean foundCutoff = false;

        for (String segment : data.retainedSegments) {
            if (segment.equals(data.includesUpToWal)) {
                foundCutoff = true;
                continue; // Skip the cutoff segment itself (already in snapshot)
            }
            if (foundCutoff || segment.compareTo(data.includesUpToWal) > 0) {
                toReplay.add(segment);
            }
        }

        return toReplay;
    }

    /**
     * Returns WAL segments that can be safely deleted (older than snapshot).
     */
    public synchronized List<String> getSegmentsToCleanup() {
        if (data.includesUpToWal == null || data.includesUpToWal.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> toCleanup = new ArrayList<>();
        for (String segment : data.retainedSegments) {
            if (segment.compareTo(data.includesUpToWal) < 0) {
                toCleanup.add(segment);
            }
        }

        return toCleanup;
    }

    /**
     * Removes cleaned-up segments from the retained list.
     */
    public synchronized void removeSegments(List<String> segments) throws IOException {
        if (data.retainedSegments.removeAll(segments)) {
            persist();
        }
    }

    /**
     * Persists current manifest state to disk (crash-safe).
     *
     * <p>Write order:
     * 1. Write to temp file
     * 2. Fsync temp file
     * 3. Atomic rename to MANIFEST.json
     * 4. Fsync parent directory (ensures rename is durable)
     */
    private void persist() throws IOException {
        Path tempPath = manifestPath.resolveSibling(MANIFEST_FILE + ".tmp");
        String jsonContent = data.toJSON().toString(2);
        byte[] bytes = jsonContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Write to temp file with fsync using FileChannel
        try (FileChannel channel = FileChannel.open(
                tempPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE)) {

            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true); // fsync file data + metadata
        }

        // Atomic rename
        Files.move(tempPath, manifestPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

        // CRITICAL: Fsync directory (ensures rename is durable)
        WALUtils.fsyncDirectory(manifestPath.getParent());
    }

    /**
     * Immutable manifest data structure.
     */
    public static class ManifestData {
        public int version = CURRENT_VERSION;
        public String snapshotPath = "";
        public String snapshotChecksum = "";  // SHA-256 hex
        public Instant snapshotCreatedAt = Instant.EPOCH;
        public String includesUpToWal = "";
        public String openWalSegment = "";
        public List<String> retainedSegments = new ArrayList<>();

        public JSONObject toJSON() {
            JSONObject json = new JSONObject();
            json.put("v", version);

            JSONObject snapshot = new JSONObject();
            snapshot.put("path", snapshotPath);
            snapshot.put("checksum", snapshotChecksum);
            snapshot.put("created_at", snapshotCreatedAt.toString());
            snapshot.put("includes_up_to_wal", includesUpToWal);
            json.put("snapshot", snapshot);

            json.put("open_wal", openWalSegment);
            json.put("retained", new JSONArray(retainedSegments));

            return json;
        }

        public static ManifestData fromJSON(JSONObject json) {
            ManifestData data = new ManifestData();
            data.version = json.optInt("v", 1);

            if (json.has("snapshot")) {
                JSONObject snapshot = json.getJSONObject("snapshot");
                data.snapshotPath = snapshot.optString("path", "");
                data.snapshotChecksum = snapshot.optString("checksum", "");
                String createdAt = snapshot.optString("created_at", "");
                if (!createdAt.isEmpty()) {
                    try {
                        data.snapshotCreatedAt = Instant.parse(createdAt);
                    } catch (Exception e) {
                        // Use EPOCH if parse fails
                    }
                }
                data.includesUpToWal = snapshot.optString("includes_up_to_wal", "");
            }

            data.openWalSegment = json.optString("open_wal", "");

            if (json.has("retained")) {
                JSONArray retained = json.getJSONArray("retained");
                for (int i = 0; i < retained.length(); i++) {
                    data.retainedSegments.add(retained.getString(i));
                }
            }

            return data;
        }

        public ManifestData copy() {
            ManifestData copy = new ManifestData();
            copy.version = this.version;
            copy.snapshotPath = this.snapshotPath;
            copy.snapshotChecksum = this.snapshotChecksum;
            copy.snapshotCreatedAt = this.snapshotCreatedAt;
            copy.includesUpToWal = this.includesUpToWal;
            copy.openWalSegment = this.openWalSegment;
            copy.retainedSegments = new ArrayList<>(this.retainedSegments);
            return copy;
        }
    }
}
