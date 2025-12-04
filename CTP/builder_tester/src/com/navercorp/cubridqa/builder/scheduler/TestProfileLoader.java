package com.navercorp.cubridqa.builder.scheduler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Loads test profiles dynamically from the Tester's WAL system statistics.
 *
 * <p>This loader reads {@code latest.json.gz} exported by the Tester's WAL system
 * and computes NORMAL/HEAVY/EXTREME classifications using {@link HeavyProfiler}.</p>
 *
 * <p>Benefits of WAL-based loading:
 * <ul>
 *   <li><b>Auto-updating:</b> WAL exports every 5 minutes, no manual regeneration</li>
 *   <li><b>Hardware-portable:</b> Ratios computed from local tester's actual metrics</li>
 *   <li><b>New tests handled:</b> Automatically included after first test run</li>
 * </ul>
 * </p>
 *
 * <p>File formats supported:
 * <ul>
 *   <li>{@code .json} - Plain JSON</li>
 *   <li>{@code .json.gz} - Gzip-compressed JSON</li>
 * </ul>
 * </p>
 */
public class TestProfileLoader {

    private static final Logger logger = Logger.getLogger(TestProfileLoader.class.getName());

    private final Path walStatsPath;
    private final HeavyProfiler profiler;

    /**
     * Creates a loader for the WAL stats file.
     *
     * @param walStatsPath path to WAL stats file (e.g., ~/tmp/tester_work/profiles/latest.json.gz)
     */
    public TestProfileLoader(Path walStatsPath) {
        this.walStatsPath = walStatsPath;
        this.profiler = new HeavyProfiler();
    }

    /**
     * Loads test profiles by computing them from WAL statistics.
     *
     * <p>If the WAL stats file is not found or cannot be parsed, returns an empty map
     * and all tests will use {@link TestProfile#NORMAL_DEFAULT}.</p>
     *
     * @return map of testKey to TestProfile (never null, may be empty)
     */
    public Map<String, TestProfile> load() {
        logger.info("TestProfileLoader: walStatsPath=" + walStatsPath + 
                " (exists=" + (walStatsPath != null && Files.exists(walStatsPath)) + ")");

        if (walStatsPath == null || !Files.exists(walStatsPath)) {
            logger.warning("WAL stats not found at " + walStatsPath + 
                    " - all tests will be classified as NORMAL");
            return Collections.emptyMap();
        }

        try {
            logger.info("Computing profiles from WAL stats: " + walStatsPath);
            Map<String, TestProfile> profiles = computeFromStats(walStatsPath);
            logger.info("Computed " + profiles.size() + " profiles from WAL stats");
            logger.info(HeavyProfiler.summarize(profiles));
            return profiles;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to compute profiles from " + walStatsPath, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Keys in latest.json that are metadata, not test entries.
     * These should be skipped when parsing the flat object format.
     */
    private static final Set<String> METADATA_KEYS = new HashSet<>(Arrays.asList(
        "node_hardware", "generated_at", "version", "v"
    ));

    /**
     * Computes profiles from raw test statistics (latest.json format).
     *
     * <p>Expected formats:
     * <ol>
     *   <li>With "tests" wrapper: {@code { "tests": { "testKey": {...}, ... } }}</li>
     *   <li>Flat object (Tester's WAL export): {@code { "shell/test.sh": {...}, "node_hardware": {...}, ... }}</li>
     *   <li>Array: {@code [ { "testKey": "...", ... }, ... ]}</li>
     * </ol>
     * </p>
     *
     * <p>For the flat object format, metadata keys (node_hardware, generated_at, version)
     * are automatically skipped. Test entries are identified by having a "duration_ms" field.</p>
     */
    private Map<String, TestProfile> computeFromStats(Path path) throws IOException {
        String content = readFile(path);
        List<HeavyProfiler.RawTestStats> allStats = new ArrayList<>();
        int skippedMetadata = 0;
        int skippedInvalid = 0;

        // Try to parse as object with "tests" field first
        if (content.trim().startsWith("{")) {
            JSONObject root = new JSONObject(content);

            if (root.has("tests")) {
                // Format: { "tests": { "testKey": {...}, ... } }
                JSONObject tests = root.getJSONObject("tests");
                for (String testKey : tests.keySet()) {
                    JSONObject testObj = tests.getJSONObject(testKey);
                    HeavyProfiler.RawTestStats stats = profiler.parseFromJson(testKey, testObj);
                    if (stats != null) {
                        allStats.add(stats);
                    } else {
                        skippedInvalid++;
                    }
                }
            } else {
                // Format: { "testKey": {...}, ... } (direct mapping from Tester's WAL export)
                // Skip metadata keys like "node_hardware", "generated_at", "version"
                for (String key : root.keySet()) {
                    // Skip known metadata keys
                    if (METADATA_KEYS.contains(key)) {
                        skippedMetadata++;
                        continue;
                    }
                    
                    Object val = root.get(key);
                    if (!(val instanceof JSONObject)) {
                        skippedMetadata++;
                        continue;
                    }
                    
                    JSONObject testObj = (JSONObject) val;
                    
                    // Verify this looks like a test entry (has duration_ms)
                    if (!testObj.has("duration_ms")) {
                        skippedMetadata++;
                        continue;
                    }
                    
                    HeavyProfiler.RawTestStats stats = profiler.parseFromJson(key, testObj);
                    if (stats != null) {
                        allStats.add(stats);
                    } else {
                        skippedInvalid++;
                    }
                }
            }
        } else if (content.trim().startsWith("[")) {
            // Format: [ { "testKey": "...", ... }, ... ]
            JSONArray arr = new JSONArray(content);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String testKey = obj.optString("testKey", obj.optString("test_key", "unknown_" + i));
                HeavyProfiler.RawTestStats stats = profiler.parseFromJson(testKey, obj);
                if (stats != null) {
                    allStats.add(stats);
                } else {
                    skippedInvalid++;
                }
            }
        }

        logger.info(String.format("Parsed %d test stats for profiling (skipped %d metadata, %d invalid)",
                allStats.size(), skippedMetadata, skippedInvalid));
        return profiler.profileAll(allStats);
    }

    /**
     * Reads file content, handling gzip compression if needed.
     */
    private String readFile(Path path) throws IOException {
        String fileName = path.getFileName().toString().toLowerCase();

        try (InputStream in = Files.newInputStream(path);
             InputStream wrapped = fileName.endsWith(".gz") ? new GZIPInputStream(in) : in;
             BufferedReader reader = new BufferedReader(new InputStreamReader(wrapped, StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /**
     * Utility method to get a profile with fallback to NORMAL_DEFAULT.
     *
     * @param profiles the profiles map
     * @param testKey the test key to look up
     * @return the profile, or NORMAL_DEFAULT if not found
     */
    public static TestProfile getOrDefault(Map<String, TestProfile> profiles, String testKey) {
        if (profiles == null) {
            return TestProfile.NORMAL_DEFAULT;
        }
        return profiles.getOrDefault(testKey, TestProfile.NORMAL_DEFAULT);
    }
}
