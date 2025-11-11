package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.tester.stats.TestObservation;
import com.navercorp.cubridqa.builder.tester.stats.TestObservationWriter;
import com.navercorp.cubridqa.builder.tester.stats.TestStats;
import com.navercorp.cubridqa.builder.tester.stats.TestStatsStore;
import com.navercorp.cubridqa.builder.tester.stats.WALManifest;
import com.navercorp.cubridqa.builder.tester.stats.WALSegmentWriter;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Manual test harness for {@link TestStatsStore}.
 *
 * <p>Focuses on verifying WAL replay and snapshot persistence so we have confidence
 * in the durability guarantees that the smart scheduling stack expects.</p>
 */
public class TestStatsStoreTest {

    private static final Logger LOGGER = Logger.getLogger(TestStatsStoreTest.class.getName());

    public static void main(String[] args) throws Exception {
        System.out.println("=== TestStatsStore Test Suite ===\n");

        testInMemoryAggregation();
        testSnapshotRoundTrip();
        testWalReplayWithoutSnapshot();
        testWalReplaySkipsOldEntries();

        System.out.println("\n=== All TestStatsStore tests passed! ===");
    }

    private static void testInMemoryAggregation() throws Exception {
        System.out.println("Test 1: In-memory aggregation");
        String testKey = "shell/sql/basic/test_insert.sh";

        try (TempStoreDir tempDir = createTempStoreDir("aggregation")) {
            Path walDir = tempDir.profilesDir().resolve("wal");
            WALManifest manifest = new WALManifest(tempDir.profilesDir());
            WALSegmentWriter walWriter = new WALSegmentWriter(tempDir.profilesDir(), manifest);
            TestStatsStore store = new TestStatsStore(tempDir.profilesDir(), 60, walWriter, manifest, walDir);
            walWriter.start();
            store.start();
            try {
                for (int i = 0; i < 3; i++) {
                    store.recordObservation(createObservation(
                        testKey,
                        10_000 + i * 1_000L,
                        Instant.now().plusMillis(i * 100)
                    ));
                }

                TestStats stats = store.getStats(testKey);
                assert stats != null : "Stats should exist for recorded test";
                assert stats.getObservationCount() == 3 : "Observation count mismatch";
                assert store.getTestCount() == 1 : "Unexpected number of tracked tests";
                assert store.getTotalObservations() == 3 : "Total observations mismatch";
                assert stats.getDurationEwmaMs() > 0.0 : "EWMA should be populated";

                System.out.println("  ✓ Aggregated 3 observations (EWMA=" + stats.getDurationEwmaMs() + " ms)");
            } finally {
                store.stop();
            }
        }

        System.out.println();
    }

    private static void testSnapshotRoundTrip() throws Exception {
        System.out.println("Test 2: Snapshot persistence and reload");
        String testKey = "shell/sql/ha/test_snapshot.sh";

        try (TempStoreDir tempDir = createTempStoreDir("snapshot")) {
            Path snapshotPath = tempDir.snapshotPath();
            Path walDir = tempDir.profilesDir().resolve("wal");
            WALManifest manifest = new WALManifest(tempDir.profilesDir());
            WALSegmentWriter walWriter = new WALSegmentWriter(tempDir.profilesDir(), manifest);
            TestStatsStore writerStore = new TestStatsStore(tempDir.profilesDir(), 1, walWriter, manifest, walDir);
            walWriter.start();
            writerStore.start();
            try {
                writerStore.recordObservation(createObservation(testKey, 17_500, Instant.now()));
            } finally {
                writerStore.stop(); // writes final snapshot
                walWriter.stop();
            }

            assert Files.exists(snapshotPath) : "Snapshot file should exist after stop()";
            JSONObject snapshotJson = readSnapshot(snapshotPath);
            assert snapshotJson.has("timestamp") : "Snapshot missing timestamp";
            assert snapshotJson.getJSONObject("tests").has(testKey) : "Snapshot missing test entry";

            WALManifest manifest2 = new WALManifest(tempDir.profilesDir());
            WALSegmentWriter walWriter2 = new WALSegmentWriter(tempDir.profilesDir(), manifest2);
            TestStatsStore readerStore = new TestStatsStore(tempDir.profilesDir(), 60, walWriter2, manifest2, walDir);
            walWriter2.start();
            readerStore.start();
            try {
                TestStats restored = readerStore.getStats(testKey);
                assert restored != null : "Snapshot data not restored";
                assert restored.getObservationCount() == 1 : "Restored observation count mismatch";
            } finally {
                readerStore.stop();
                walWriter2.stop();
            }

            System.out.println("  ✓ Snapshot round-trip succeeded (" + snapshotPath + ")");
        }

        System.out.println();
    }

    private static void testWalReplayWithoutSnapshot() throws Exception {
        System.out.println("Test 3: WAL replay without snapshot baseline");

        try (TempStoreDir tempDir = createTempStoreDir("wal_bootstrap")) {
            Files.deleteIfExists(tempDir.walPath());

            writeWal(
                tempDir.walPath(),
                createObservation("shell/sql/perf/test_01.sh", 22_000, Instant.now()),
                createObservation("shell/sql/perf/test_02.sh", 18_500, Instant.now().plusSeconds(1))
            );

            Path walDir = tempDir.profilesDir().resolve("wal");
            WALManifest manifest = new WALManifest(tempDir.profilesDir());
            WALSegmentWriter walWriter = new WALSegmentWriter(tempDir.profilesDir(), manifest);
            TestStatsStore store = new TestStatsStore(tempDir.profilesDir(), 60, walWriter, manifest, walDir);
            walWriter.start();
            store.start();
            try {
                assert store.getTestCount() == 2 : "Should load two distinct tests from WAL";
                assert store.getTotalObservations() == 2 : "Observation count mismatch from WAL replay";
                assert store.getStats("shell/sql/perf/test_01.sh") != null : "Missing WAL test stats";
                assert store.getStats("shell/sql/perf/test_02.sh") != null : "Missing WAL test stats";
            } finally {
                store.stop();
                walWriter.stop();
            }

            System.out.println("  ✓ Replayed WAL entries without snapshot");
        }

        System.out.println();
    }

    private static void testWalReplaySkipsOldEntries() throws Exception {
        System.out.println("Test 4: WAL replay skips entries older than snapshot");
        String testKey = "shell/sql/basic/test_wal.sh";

        try (TempStoreDir tempDir = createTempStoreDir("wal_snapshot")) {
            // Seed snapshot with one observation
            Path walDir = tempDir.profilesDir().resolve("wal");
            WALManifest manifest1 = new WALManifest(tempDir.profilesDir());
            WALSegmentWriter walWriter1 = new WALSegmentWriter(tempDir.profilesDir(), manifest1);
            TestStatsStore initialStore = new TestStatsStore(tempDir.profilesDir(), 60, walWriter1, manifest1, walDir);
            walWriter1.start();
            initialStore.start();
            try {
                initialStore.recordObservation(createObservation(testKey, 12_000, Instant.now()));
            } finally {
                initialStore.stop(); // writes snapshot
                walWriter1.stop();
            }

            Files.deleteIfExists(tempDir.walPath());
            Instant oldTimestamp = Instant.now().minusSeconds(3_600);
            Instant newTimestamp = Instant.now().plusSeconds(5);

            writeWal(
                tempDir.walPath(),
                createObservation(testKey, 9_500, oldTimestamp),   // should be skipped
                createObservation(testKey, 15_000, newTimestamp)   // should be replayed
            );

            WALManifest manifest2 = new WALManifest(tempDir.profilesDir());
            WALSegmentWriter walWriter2 = new WALSegmentWriter(tempDir.profilesDir(), manifest2);
            TestStatsStore replayStore = new TestStatsStore(tempDir.profilesDir(), 60, walWriter2, manifest2, walDir);
            walWriter2.start();
            replayStore.start();
            try {
                TestStats stats = replayStore.getStats(testKey);
                assert stats != null : "Stats should exist after replay";
                assert stats.getObservationCount() == 2 :
                    "Only snapshot + new WAL entry should be counted (expected 2, got " + stats.getObservationCount() + ")";
            } finally {
                replayStore.stop();
                walWriter2.stop();
            }

            System.out.println("  ✓ Replayed WAL and skipped stale entries (total observations=2)");
        }

        System.out.println();
    }

    private static TestObservation createObservation(String testKey, long durationMs, Instant timestamp) {
        return TestObservation.builder()
            .testKey(testKey)
            .commit("commit-" + durationMs)
            .baseline("baseline-main")
            .executor("optimized-docker")
            .status("pass")
            .attempts(1)
            .durationMs(durationMs)
            .cpuPctMean(50.0)
            .cpuPctPeak(110.0)
            .memMbMean(512.0)
            .memMbPeak(768.0)
            .ioMbPerSecMean(12.0)
            .iopsMean(220.0)
            .netMbPerSecMean(5.0)
            .bytesReadMb(64)
            .bytesWriteMb(32)
            .dockerImageCached(true)
            .packageCached(true)
            .logSizeKb(128)
            .metricsComplete(true)
            .timestamp(timestamp)
            .build();
    }

    private static JSONObject readSnapshot(Path snapshotPath) throws Exception {
        try (InputStream fis = Files.newInputStream(snapshotPath);
             GZIPInputStream gis = new GZIPInputStream(fis);
             Reader reader = new InputStreamReader(gis, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            return new JSONObject(sb.toString());
        }
    }

    private static void writeWal(Path walPath, TestObservation... observations) {
        TestObservationWriter writer = new TestObservationWriter(walPath, LOGGER);
        for (TestObservation observation : observations) {
            writer.recordObservation(observation);
        }
    }

    private static TempStoreDir createTempStoreDir(String suffix) throws Exception {
        Path root = Files.createTempDirectory("test_stats_store_" + suffix + "_");
        return new TempStoreDir(root);
    }

    private static final class TempStoreDir implements AutoCloseable {
        private final Path rootDir;
        private final Path profilesDir;

        private TempStoreDir(Path rootDir) throws Exception {
            this.rootDir = rootDir;
            this.profilesDir = rootDir.resolve("profiles");
            Files.createDirectories(this.profilesDir);
        }

        Path profilesDir() {
            return profilesDir;
        }

        Path walPath() {
            return profilesDir.resolve("test_stats.jl.gz");
        }

        Path snapshotPath() {
            return profilesDir.resolve("test_stats.snapshot.json.gz");
        }

        @Override
        public void close() {
            deleteRecursively(rootDir);
        }
    }

    private static void deleteRecursively(Path path) {
        if (path != null) {
            deleteRecursively(path.toFile());
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursively(f);
                }
            }
        }
        file.delete();
    }
}
