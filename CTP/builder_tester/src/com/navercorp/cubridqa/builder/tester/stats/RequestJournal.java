package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.StandardOpenOption.*;

/**
 * Per-request journal that records all observations for a single tester run.
 *
 * <p>Creates human-readable JSON files in profiles/requests/ directory with format:
 * req_YYYYMMDD_HHMMSS_xxxx.json
 *
 * <p>Design principles:
 * - Minimal code surface (no new config keys)
 * - Thread-safe buffered writes
 * - Atomic flush on shutdown
 * - Human-readable format for debugging and analysis
 */
public final class RequestJournal {
    private static final Logger logger = Logger.getLogger(RequestJournal.class.getName());

    private final Path requestsDir;
    private final String requestId; // e.g., req_20251112_002330_82cf
    private final List<JSONObject> buffer = new ArrayList<>(256);
    private volatile boolean closed = false;

    /**
     * Creates a new request journal.
     *
     * @param profilesDir Base profiles directory
     * @param requestId Unique request identifier
     * @throws IOException if directory creation fails
     */
    public RequestJournal(Path profilesDir, String requestId) throws IOException {
        this.requestsDir = profilesDir.resolve("requests");
        this.requestId = Objects.requireNonNull(requestId);
        Files.createDirectories(this.requestsDir);
    }

    /**
     * Appends a test observation to the buffer.
     *
     * <p>This is a minimal, human-focused subset of the observation.
     * Add or remove fields as needed for your use case.
     *
     * @param o The observation to record
     */
    public void append(TestObservation o) {
        if (closed) {
            return;
        }

        // Minimal, human-focused subset; add/remove fields as you like
        JSONObject j = new JSONObject()
            .put("testKey", o.getTestKey())
            .put("timestamp", o.getTsIso())
            .put("status", o.getStatus())
            .put("duration_ms", o.getDurationMs())
            .put("cpu_pct_mean", o.getCpuPctMean())
            .put("cpu_pct_peak", o.getCpuPctPeak())
            .put("mem_mb_mean", o.getMemMbMean())
            .put("mem_mb_peak", o.getMemMbPeak())
            .put("io_mb_s_mean", o.getIoMbPerSecMean())
            .put("iops_mean", o.getIopsMean())
            .put("net_mb_s_mean", o.getNetMbPerSecMean())
            .put("docker_image_cached", o.isDockerImageCached());

        synchronized (buffer) {
            buffer.add(j);
        }
    }

    /**
     * Flushes buffered observations to disk and closes the journal.
     *
     * <p>This method is idempotent and safe to call multiple times.
     *
     * @param node Node identifier (optional, can be null)
     * @param commit Commit hash (optional, can be null)
     */
    public void flushAndClose(String node, String commit) {
        if (closed) {
            return;
        }
        closed = true;

        try {
            JSONObject root = new JSONObject();
            List<JSONObject> copy;
            synchronized (buffer) {
                copy = new ArrayList<>(buffer);
            }

            root.put("request", requestId)
                .put("generated_at", Instant.now().toString())
                .put("node", node == null ? JSONObject.NULL : node)
                .put("commit", commit == null ? JSONObject.NULL : commit)
                .put("count", copy.size())
                .put("tests", copy);

            byte[] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path tmp = requestsDir.resolve(requestId + ".json.tmp");

            try (FileChannel ch = FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE)) {
                ch.write(ByteBuffer.wrap(bytes));
                ch.force(true);
            }

            Path dst = requestsDir.resolve(requestId + ".json");
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            WALUtils.fsyncDirectory(requestsDir);

        } catch (Exception e) {
            // Log and proceed; WAL still preserves data
            logger.log(Level.WARNING, "[RequestJournal] flush failed for " + requestId, e);
        }
    }
}
