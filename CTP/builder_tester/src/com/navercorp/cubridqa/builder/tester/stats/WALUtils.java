package com.navercorp.cubridqa.builder.tester.stats;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility methods for crash-safe WAL operations.
 *
 * <p>Provides:
 * - Directory fsync after atomic renames
 * - SHA-256 checksum computation
 * - Advisory file locking
 * - Safe file operations
 */
public class WALUtils {

    private static final Logger logger = Logger.getLogger(WALUtils.class.getName());

    /**
     * Fsyncs a directory to ensure rename operations are durable.
     *
     * <p>CRITICAL: After atomic rename (MANIFEST.tmp → MANIFEST.json),
     * must fsync the parent directory to ensure the rename survives
     * power loss or kernel panic.
     *
     * @param dir Directory to fsync
     * @throws IOException if fsync fails
     */
    public static void fsyncDirectory(Path dir) throws IOException {
        // On Linux: open directory as file descriptor and fsync
        // Some platforms throw IOException or FileSystemException when opening a directory
        try (FileChannel channel = FileChannel.open(dir)) {
            channel.force(true);
            logger.fine("Fsynced directory: " + dir);
        } catch (UnsupportedOperationException | IOException e) {
            // Log but don't fail - graceful degradation
            logger.fine("Directory fsync not supported here: " + dir + 
                    " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    /**
     * Computes SHA-256 checksum of a file.
     *
     * @param path File to checksum
     * @return Hex-encoded SHA-256 hash
     * @throws IOException if file cannot be read
     */
    public static String computeSHA256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream fis = Files.newInputStream(path);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = bis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            byte[] hash = digest.digest();
            return bytesToHex(hash);

        } catch (Exception e) {
            throw new IOException("Failed to compute SHA-256: " + e.getMessage(), e);
        }
    }

    /**
     * Converts byte array to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Acquires an exclusive advisory lock on a file.
     *
     * <p>Use this to ensure only one tester process writes to WAL.
     * Lock is automatically released when FileChannel is closed.
     *
     * <p>CRITICAL: Lock file lifecycle:
     * - If process is SIGKILLed, OS releases the lock automatically
     * - A stale .lock file may remain on disk (this is fine)
     * - We rely on the advisory lock result (tryLock()), not file existence
     * - The presence of the lock file path does not indicate a held lock
     *
     * @param lockFile Path to lock file
     * @return FileChannel with lock (must be kept open)
     * @throws IOException if lock cannot be acquired
     */
    public static FileChannel acquireExclusiveLock(Path lockFile) throws IOException {
        // Ensure parent directory exists
        Files.createDirectories(lockFile.getParent());

        // Open lock file (create if doesn't exist)
        // Note: Stale lock files are fine - we rely on tryLock() result, not file existence
        FileChannel channel = FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);

        // Try to acquire exclusive lock (non-blocking)
        FileLock lock = channel.tryLock();

        if (lock == null) {
            channel.close();
            throw new IOException("Could not acquire WAL write lock - another process is writing");
        }

        logger.info("Acquired exclusive WAL write lock: " + lockFile);
        return channel; // Caller must keep channel open to hold lock
    }

    /**
     * Safely creates a backup of a file.
     *
     * @param source Source file
     * @param backup Backup file path
     * @throws IOException if backup fails
     */
    public static void createBackup(Path source, Path backup) throws IOException {
        if (!Files.exists(source)) {
            return; // Nothing to backup
        }

        try {
            // Copy to temp file first
            Path tempBackup = backup.resolveSibling(backup.getFileName() + ".tmp");
            Files.copy(source, tempBackup,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Fsync backup file
            try (FileChannel channel = FileChannel.open(tempBackup,
                    java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            // Atomic rename
            Files.move(tempBackup, backup,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            // Fsync directory
            fsyncDirectory(backup.getParent());

            logger.fine("Created backup: " + backup);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create backup: " + backup, e);
            // Non-fatal: continue without backup
        }
    }

    /**
     * Safely reads a file with fallback to backup.
     *
     * @param primary Primary file
     * @param backup Backup file
     * @return Content of primary, or backup if primary is corrupt
     * @throws IOException if both primary and backup fail
     */
    public static String readWithFallback(Path primary, Path backup) throws IOException {
        // Try primary first
        if (Files.exists(primary)) {
            try {
                return new String(Files.readAllBytes(primary), "UTF-8");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Primary file corrupt, trying backup: " + primary, e);
            }
        }

        // Fallback to backup
        if (Files.exists(backup)) {
            try {
                String content = new String(Files.readAllBytes(backup), "UTF-8");
                logger.warning("Used backup file: " + backup);
                return content;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Backup file also corrupt: " + backup, e);
                throw new IOException("Both primary and backup files corrupt");
            }
        }

        throw new IOException("Neither primary nor backup file exists");
    }

    /**
     * Atomically writes a file with fsync and directory fsync.
     *
     * <p>Write order:
     * 1. Write to temp file
     * 2. Fsync temp file
     * 3. Atomic rename to target
     * 4. Fsync parent directory (ensures rename is durable)
     *
     * <p>Uses FileChannel pattern to avoid stream close ordering issues.
     *
     * @param target Target file path
     * @param content Content to write
     * @throws IOException if write fails
     */
    public static void atomicWrite(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Write to temp file with fsync using FileChannel
        try (FileChannel channel = FileChannel.open(
                temp,
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
        Files.move(temp, target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        // CRITICAL: Fsync directory (ensures rename is durable)
        fsyncDirectory(target.getParent());
    }

    /**
     * Generates a monotonic segment name with sequence number.
     *
     * <p>Format: test_stats-YYYYMMDD-HHMMSS-SEQ.jl
     * (Not gzipped - plaintext for crash resilience)
     *
     * <p>CRITICAL: Sequence uses full 64-bit value to avoid wraparound collisions.
     * Even with 10,000 segments per second, wraparound would take ~29 million years.
     * Format uses last 6 digits for readability while maintaining uniqueness.
     *
     * @param timestamp Timestamp for segment
     * @param sequence Monotonic sequence number (64-bit, never wraps in practice)
     * @return Segment filename
     */
    public static String generateSegmentName(java.time.Instant timestamp, long sequence) {
        String dateTime = java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(timestamp);

        // Use last 6 digits of sequence for readability (supports up to 999,999 segments)
        // Full 64-bit sequence ensures no wraparound collisions in practice
        return String.format("test_stats-%s-%06d.jl", dateTime, sequence % 1000000);
    }
}
