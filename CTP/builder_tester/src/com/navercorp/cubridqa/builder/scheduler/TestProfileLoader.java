package com.navercorp.cubridqa.builder.scheduler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Loads test profiles from JSON files for the Builder's scheduling decisions.
 *
 * <p>Supports two loading modes:
 * <ol>
 *   <li><b>Pre-computed profiles:</b> Load from {@code test_profiles.json} containing
 *       pre-classified TestProfile objects</li>
 *   <li><b>Compute from stats:</b> If profiles file is missing, fall back to loading
 *       {@code latest.json} and computing profiles via {@link HeavyProfiler}</li>
 * </ol>
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

    private final Path profilesPath;
    private final Path fallbackStatsPath;
    private final HeavyProfiler profiler;

    /**
     * Creates a loader with default paths.
     *
     * @param baseDir base directory for profile files (e.g., conf/)
     */
    public TestProfileLoader(String baseDir) {
        this(Paths.get(baseDir, "test_profiles.json"),
             Paths.get(baseDir, "latest.json.gz"));
    }

    /**
     * Creates a loader with explicit paths.
     *
     * @param profilesPath path to pre-computed profiles file
     * @param fallbackStatsPath path to raw stats file for fallback computation
     */
    public TestProfileLoader(Path profilesPath, Path fallbackStatsPath) {
        this.profilesPath = profilesPath;
        this.fallbackStatsPath = fallbackStatsPath;
        this.profiler = new HeavyProfiler();
    }

    /**
     * Loads test profiles from the configured source.
     *
     * <p>Loading order:
     * <ol>
     *   <li>Try loading pre-computed profiles from profilesPath</li>
     *   <li>If not found or failed, try computing from fallbackStatsPath</li>
     *   <li>If all else fails, return empty map (all tests will use NORMAL_DEFAULT)</li>
     * </ol>
     * </p>
     *
     * @return map of testKey to TestProfile (never null, may be empty)
     */
    public Map<String, TestProfile> load() {
        // Try pre-computed profiles first
        if (profilesPath != null && Files.exists(profilesPath)) {
            try {
                Map<String, TestProfile> profiles = loadProfiles(profilesPath);
                logger.info("Loaded " + profiles.size() + " pre-computed profiles from " + profilesPath);
                logger.info(HeavyProfiler.summarize(profiles));
                return profiles;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load profiles from " + profilesPath + ", trying fallback", e);
            }
        }

        // Try computing from stats
        if (fallbackStatsPath != null && Files.exists(fallbackStatsPath)) {
            try {
                Map<String, TestProfile> profiles = computeFromStats(fallbackStatsPath);
                logger.info("Computed " + profiles.size() + " profiles from stats at " + fallbackStatsPath);
                logger.info(HeavyProfiler.summarize(profiles));
                return profiles;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to compute profiles from " + fallbackStatsPath, e);
            }
        }

        // Return empty map - all tests will use NORMAL_DEFAULT
        logger.warning("No profile sources available - all tests will be classified as NORMAL");
        return Collections.emptyMap();
    }

    /**
     * Loads pre-computed profiles from a JSON file.
     *
     * <p>Expected format:
     * <pre>
     * {
     *   "version": 1,
     *   "generated": "2025-12-03T10:00:00Z",
     *   "globalStats": { "meanCpuPct": 69.64, ... },
     *   "profiles": [
     *     { "testKey": "...", "heavyClass": "NORMAL", ... },
     *     ...
     *   ]
     * }
     * </pre>
     * </p>
     */
    private Map<String, TestProfile> loadProfiles(Path path) throws IOException {
        String content = readFile(path);
        JSONObject root = new JSONObject(content);

        Map<String, TestProfile> profiles = new HashMap<>();

        if (root.has("profiles")) {
            JSONArray arr = root.getJSONArray("profiles");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                TestProfile profile = profiler.fromJson(obj);
                profiles.put(profile.getTestKey(), profile);
            }
        }

        return profiles;
    }

    /**
     * Computes profiles from raw test statistics (latest.json format).
     *
     * <p>Expected format:
     * <pre>
     * {
     *   "tests": {
     *     "shell/path/to/test.sh": {
     *       "observation_count": 50,
     *       "duration_ms": { "ewma": 45000 },
     *       "cpu_pct": { "avg": 75.5 },
     *       ...
     *     },
     *     ...
     *   }
     * }
     * </pre>
     * Or as an array:
     * <pre>
     * [
     *   { "testKey": "...", "observation_count": 50, ... },
     *   ...
     * ]
     * </pre>
     * </p>
     */
    private Map<String, TestProfile> computeFromStats(Path path) throws IOException {
        String content = readFile(path);
        List<HeavyProfiler.RawTestStats> allStats = new ArrayList<>();

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
                    }
                }
            } else {
                // Format: { "testKey": {...}, ... } (direct mapping)
                for (String testKey : root.keySet()) {
                    Object val = root.get(testKey);
                    if (val instanceof JSONObject) {
                        HeavyProfiler.RawTestStats stats = profiler.parseFromJson(testKey, (JSONObject) val);
                        if (stats != null) {
                            allStats.add(stats);
                        }
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
                }
            }
        }

        logger.info("Parsed " + allStats.size() + " test stats for profiling");
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
     * Saves computed profiles to a JSON file for future use.
     *
     * @param profiles map of profiles to save
     * @param outputPath destination path
     */
    public void saveProfiles(Map<String, TestProfile> profiles, Path outputPath) throws IOException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("generated", java.time.Instant.now().toString());
        root.put("testCount", profiles.size());

        // Compute and save summary
        int normal = 0, heavy = 0, extreme = 0;
        for (TestProfile p : profiles.values()) {
            switch (p.getHeavyClass()) {
                case NORMAL: normal++; break;
                case HEAVY: heavy++; break;
                case EXTREME: extreme++; break;
            }
        }
        JSONObject summary = new JSONObject();
        summary.put("normal", normal);
        summary.put("heavy", heavy);
        summary.put("extreme", extreme);
        root.put("summary", summary);

        // Save all profiles
        JSONArray arr = new JSONArray();
        for (TestProfile profile : profiles.values()) {
            arr.put(profiler.toJson(profile));
        }
        root.put("profiles", arr);

        // Write to file
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(outputPath), StandardCharsets.UTF_8))) {
            writer.write(root.toString(2));
        }

        logger.info("Saved " + profiles.size() + " profiles to " + outputPath);
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



