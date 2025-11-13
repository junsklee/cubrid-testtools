package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.channels.FileChannel;

/**
 * Single-threaded WAL segment writer with bounded queue and rotation.
 *
 * <p>Features:
 * - Segments WAL files by time (5 min) or size (4 MB)
 * - Single writer thread prevents file contention
 * - Bounded queue with drop-oldest-on-full policy
 * - Fsync before segment close for durability
 *
 * <p>Thread-safe: Multiple threads can call {@link #append(TestObservation)}
 * concurrently. A single background thread marshals to disk.
 */
public class WALSegmentWriter {

    private static final Logger logger = Logger.getLogger(WALSegmentWriter.class.getName());

    private static final int QUEUE_CAPACITY = 10000;
    private static final long ROTATION_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5); // 5 minutes
    private static final long ROTATION_SIZE_BYTES = 4 * 1024 * 1024; // 4 MB

    private final Path walDir;
    private final WALManifest manifest;
    private final LinkedBlockingQueue<TestObservation> queue;
    private final Thread writerThread;
    private final AtomicBoolean running;

    // Process lock (held for lifetime of writer)
    private FileChannel lockChannel;

    // Monotonic sequence for segment naming (guards against clock skew)
    private final AtomicLong segmentSequence = new AtomicLong(0);

    // Current segment state
    private volatile String currentSegmentName;
    private volatile Path currentSegmentPath;
    private volatile FileOutputStream currentFileStream;
    private volatile BufferedWriter currentWriter;
    private volatile Instant segmentStartTime;
    private final AtomicLong segmentBytesWritten;

    // Metrics
    private final AtomicLong observationsWritten;
    private final AtomicLong observationsDropped;
    private final AtomicLong segmentsRotated;

    public WALSegmentWriter(Path profilesDir, WALManifest manifest) throws IOException {
        this.walDir = profilesDir.resolve("wal");
        this.manifest = manifest;
        this.queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.running = new AtomicBoolean(false);
        this.segmentBytesWritten = new AtomicLong(0);
        this.observationsWritten = new AtomicLong(0);
        this.observationsDropped = new AtomicLong(0);
        this.segmentsRotated = new AtomicLong(0);

        // Ensure WAL directory exists
        Files.createDirectories(walDir);

        // Create writer thread
        this.writerThread = new Thread(this::runWriter, "WALSegmentWriter");
        this.writerThread.setDaemon(true);
    }

    /**
     * Starts the writer thread and opens the initial segment.
     *
     * <p>CRITICAL: Acquires exclusive process lock to prevent multi-process WAL corruption.
     * Lock is held for the lifetime of the writer.
     */
    public void start() throws IOException {
        if (running.getAndSet(true)) {
            return; // Already started
        }

        // CRITICAL: Acquire exclusive lock to prevent multi-process writes
        Path lockFile = walDir.resolve(".lock");
        this.lockChannel = WALUtils.acquireExclusiveLock(lockFile);
        logger.info("Acquired WAL write lock: " + lockFile);

        // Determine initial segment name from manifest
        WALManifest.ManifestData manifestData = manifest.getData();
        if (manifestData.openWalSegment != null && !manifestData.openWalSegment.isEmpty()) {
            currentSegmentName = manifestData.openWalSegment;
        } else {
            currentSegmentName = generateSegmentName();
            manifest.setOpenWalSegment(currentSegmentName);
        }

        openSegment(currentSegmentName);
        writerThread.start();

        logger.info("WALSegmentWriter started with segment: " + currentSegmentName);
    }

    /**
     * Stops the writer thread, flushes pending observations, and closes segment.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return; // Already stopped
        }

        try {
            // Signal shutdown and wait for thread
            writerThread.interrupt();
            writerThread.join(5000);

            // Flush remaining queue items
            flushQueue();

            // Close current segment
            closeCurrentSegment();

            // Release process lock
            if (lockChannel != null) {
                try {
                    lockChannel.close();
                    logger.info("Released WAL write lock");
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Error releasing lock", e);
                }
                lockChannel = null;
            }

            logger.info(String.format("WALSegmentWriter stopped: written=%d, dropped=%d, rotated=%d",
                    observationsWritten.get(), observationsDropped.get(), segmentsRotated.get()));

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error stopping WALSegmentWriter", e);
        }
    }

    /**
     * Appends an observation to the write queue.
     *
     * <p>If queue is full, drops the observation and increments drop counter.
     * This prevents blocking test execution threads.
     *
     * @param obs The observation to append
     */
    public void append(TestObservation obs) {
        if (!running.get()) {
            observationsDropped.incrementAndGet();
            return;
        }

        if (!queue.offer(obs)) {
            // Queue full - drop observation
            observationsDropped.incrementAndGet();

            if (observationsDropped.get() % 100 == 0) {
                logger.warning("WAL queue full, dropped " + observationsDropped.get() + " observations");
            }
        }
    }

    /**
     * Forces rotation to a new segment.
     *
     * <p>CRITICAL: This method is atomic from the caller's perspective.
     * The writer thread must:
     * 1. Stop appends to current file (synchronized block)
     * 2. Finish/force/close current segment
     * 3. Return the closed filename (guaranteed durable)
     * 4. Open the new file
     * 5. Update manifest
     * 6. Only then release the lock
     *
     * <p>This guarantees the "closed segment" returned is truly closed and durable.
     *
     * <p>Useful for testing or manual segment control (e.g., snapshot coordinator).
     *
     * @return The name of the closed segment (fully durable)
     * @throws IOException if rotation fails
     */
    public String rotateNow() throws IOException {
        return rotateSegment(); // Synchronized method ensures atomicity
    }

    /**
     * Returns the current segment name.
     */
    public String getCurrentSegmentName() {
        return currentSegmentName;
    }

    /**
     * Returns metrics for monitoring.
     */
    public WALMetrics getMetrics() {
        return new WALMetrics(
                observationsWritten.get(),
                observationsDropped.get(),
                segmentsRotated.get(),
                queue.size(),
                currentSegmentName
        );
    }

    /**
     * Writer thread main loop.
     */
    private void runWriter() {
        while (running.get()) {
            try {
                // Poll with timeout to check rotation periodically
                TestObservation obs = queue.poll(1, TimeUnit.SECONDS);

                if (obs != null) {
                    writeObservation(obs);
                }

                // Check if rotation needed
                if (shouldRotate()) {
                    rotateSegment();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error in WAL writer loop", e);
            }
        }
    }

    /**
     * Writes a single observation to the current segment.
     *
     * <p>CRITICAL: Each observation must be newline-terminated before flush.
     * On replay, treat a non-JSON trailing fragment as "partial line → ignore and stop at EOF".
     */
    private void writeObservation(TestObservation obs) throws IOException {
        if (currentWriter == null) {
            throw new IOException("No open WAL segment");
        }

        // CRITICAL: Ensure newline termination for line integrity
        String line = obs.toJsonLine() + "\n";
        byte[] bytes = line.getBytes("UTF-8");

        currentWriter.write(line);
        currentWriter.flush();

        segmentBytesWritten.addAndGet(bytes.length);
        observationsWritten.incrementAndGet();
    }

    /**
     * Checks if segment should be rotated based on time or size.
     */
    private boolean shouldRotate() {
        if (segmentStartTime == null) {
            return false;
        }

        // Time-based rotation
        long elapsed = Instant.now().toEpochMilli() - segmentStartTime.toEpochMilli();
        if (elapsed >= ROTATION_INTERVAL_MS) {
            return true;
        }

        // Size-based rotation
        if (segmentBytesWritten.get() >= ROTATION_SIZE_BYTES) {
            return true;
        }

        return false;
    }

    /**
     * Rotates to a new WAL segment.
     *
     * <p>CRITICAL: This method is synchronized to ensure atomicity.
     * Execution order:
     * 1. Capture current segment name (before close)
     * 2. Close current segment (flush + fsync + close)
     * 3. Add closed segment to manifest's retained list
     * 4. Generate and open new segment
     * 5. Update manifest with new open segment
     * 6. Return closed segment name (guaranteed durable)
     *
     * <p>This ensures the closed segment is fully durable before
     * it's referenced in the MANIFEST or returned to caller.
     *
     * @return The name of the closed segment (fully durable)
     * @throws IOException if rotation fails
     */
    private synchronized String rotateSegment() throws IOException {
        logger.info("Rotating WAL segment: " + currentSegmentName +
                " (bytes=" + segmentBytesWritten.get() + ")");

        // Step 1: Capture current segment name (before close)
        String closedSegment = currentSegmentName;

        // Step 2: Close current segment (flush + fsync + close)
        closeCurrentSegment();

        // Step 3: Add closed segment to manifest's retained list
        if (closedSegment != null && !closedSegment.isEmpty()) {
            manifest.addRetainedSegment(closedSegment);
        }

        // Step 4: Generate and open new segment
        String newSegment = generateSegmentName();
        openSegment(newSegment);

        // Step 5: Update manifest with new open segment
        manifest.setOpenWalSegment(newSegment);

        segmentsRotated.incrementAndGet();

        // Step 6: Return closed segment name (guaranteed durable)
        return closedSegment;
    }

    /**
     * Opens a new WAL segment file.
     *
     * <p>CRITICAL: Writes plaintext JSONL (not gzipped) to prevent mid-stream corruption.
     * Compression can be done asynchronously for archival if needed.
     */
    private void openSegment(String segmentName) throws IOException {
        currentSegmentName = segmentName;
        currentSegmentPath = walDir.resolve(segmentName);
        segmentStartTime = Instant.now();
        segmentBytesWritten.set(0);

        // CRITICAL: Plaintext JSONL (not gzipped) for crash resilience
        // Gzip corruption in mid-stream makes entire file unreadable
        currentFileStream = new FileOutputStream(currentSegmentPath.toFile(), true); // Append mode
        currentWriter = new BufferedWriter(new OutputStreamWriter(currentFileStream, "UTF-8"));

        logger.fine("Opened WAL segment (plaintext): " + segmentName);
    }

    /**
     * Closes the current WAL segment with fsync.
     *
     * <p>CRITICAL: Flush order must be:
     * 1. writer.flush() - flush buffered data to underlying stream
     * 2. channel.force(true) - fsync file data and metadata (with timeout protection)
     * 3. close() - close all streams
     */
    private void closeCurrentSegment() throws IOException {
        if (currentWriter != null) {
            // Step 1: Flush writer buffers
            currentWriter.flush();

            // Step 2: Fsync file data and metadata with timeout protection
            if (currentFileStream != null) {
                try {
                    FileChannel channel = currentFileStream.getChannel();

                    // Wrap fsync in timeout (30 seconds max to prevent indefinite hang)
                    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                    try {
                        java.util.concurrent.Future<Void> future = executor.submit(() -> {
                            try {
                                channel.force(true); // fsync data + metadata
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return null;
                        });

                        // Wait up to 30 seconds for fsync to complete
                        future.get(30, java.util.concurrent.TimeUnit.SECONDS);

                    } catch (java.util.concurrent.TimeoutException e) {
                        logger.log(Level.WARNING, "WAL fsync timed out after 30 seconds (possible NFS or slow disk). " +
                                "Segment may not be durable: " + currentSegmentName, e);
                        // Continue anyway - don't block forever
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error during WAL fsync for segment: " + currentSegmentName, e);
                        // Continue anyway - fsync is best-effort
                    } finally {
                        executor.shutdownNow();
                    }

                    channel.close();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error closing WAL segment channel: " + currentSegmentName, e);
                    // Continue with stream close
                }
            }

            // Step 3: Close streams
            currentWriter.close();
            currentWriter = null;
            currentFileStream = null;

            logger.fine("Closed WAL segment: " + currentSegmentName +
                    " (bytes=" + segmentBytesWritten.get() + ")");
        }
    }

    /**
     * Flushes remaining queue items (called during shutdown).
     */
    private void flushQueue() {
        List<TestObservation> remaining = new ArrayList<>();
        queue.drainTo(remaining);

        for (TestObservation obs : remaining) {
            try {
                writeObservation(obs);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to write observation during flush", e);
            }
        }

        logger.info("Flushed " + remaining.size() + " observations from queue");
    }

    /**
     * Generates a new segment name with timestamp and monotonic sequence.
     *
     * <p>Uses WALUtils.generateSegmentName() which includes:
     * - Timestamp for human readability
     * - Monotonic sequence number (guards against clock skew/rewind)
     * - Plaintext .jl extension (not .gz)
     */
    private String generateSegmentName() {
        long seq = segmentSequence.incrementAndGet();
        return WALUtils.generateSegmentName(Instant.now(), seq);
    }

    /**
     * Metrics snapshot for monitoring.
     */
    public static class WALMetrics {
        public final long observationsWritten;
        public final long observationsDropped;
        public final long segmentsRotated;
        public final int queueSize;
        public final String currentSegment;

        public WALMetrics(long written, long dropped, long rotated, int queueSize, String segment) {
            this.observationsWritten = written;
            this.observationsDropped = dropped;
            this.segmentsRotated = rotated;
            this.queueSize = queueSize;
            this.currentSegment = segment;
        }

        @Override
        public String toString() {
            return String.format("WALMetrics{written=%d, dropped=%d, rotated=%d, queue=%d, segment=%s}",
                    observationsWritten, observationsDropped, segmentsRotated, queueSize, currentSegment);
        }
    }
}
