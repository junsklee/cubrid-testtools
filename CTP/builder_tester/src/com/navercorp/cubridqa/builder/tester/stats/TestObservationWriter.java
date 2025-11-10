package com.navercorp.cubridqa.builder.tester.stats;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * Thin append-only writer that stores {@link TestObservation} entries
 * as gzipped JSON lines. The gzip stream is opened per append which
 * produces concatenated gzip members – this is supported by the format
 * and keeps the implementation simple while still providing compression.
 */
public final class TestObservationWriter {

    private final Path walPath;
    private final Logger logger;
    private final Lock writeLock = new ReentrantLock();
    private final boolean enabled;

    public TestObservationWriter(Path walPath, Logger logger) {
        this(walPath, logger, true);
    }

    public TestObservationWriter(Path walPath, Logger logger, boolean enabled) {
        this.walPath = Objects.requireNonNull(walPath, "walPath");
        this.logger = logger != null ? logger : Logger.getLogger(TestObservationWriter.class.getName());
        this.enabled = enabled;
    }

    public void recordObservation(TestObservation observation) {
        if (!enabled || observation == null) {
            return;
        }

        writeLock.lock();
        try {
            ensureParentDirectory();
            try (FileOutputStream fos = new FileOutputStream(walPath.toFile(), true);
                 GZIPOutputStream gzos = new GZIPOutputStream(fos) {{
                     this.def.setLevel(java.util.zip.Deflater.BEST_SPEED);
                 }};
                 OutputStreamWriter osw = new OutputStreamWriter(gzos, StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw)) {
                writer.write(observation.toJsonLine());
                writer.newLine();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to record test observation: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    private void ensureParentDirectory() throws Exception {
        Path parent = walPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}

