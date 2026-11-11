package com.navercorp.cubridqa.builder;

import org.json.JSONArray;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Official CUBRID builds, as the build store publishes them: a directory per version
 * (11.5.0.2856-1b26459, 10.1.3.7698-553a7231d) holding drop/CUBRID-&lt;version&gt;-Linux.x86_64.tar.gz
 * and its -debug twin. A request naming officialBuilds is tested on those packages instead of
 * building commits: testers download them as they download a Builder package.
 *
 * <p>The version ends in the commit it was built from, which identifies the build in results,
 * reports and per-commit Docker images as a built commit does.
 */
public final class OfficialBuilds {
    public static final String DEFAULT_STORE_URL = "http://192.168.1.91:8080/REPO_ROOT/store_01/";
    /** Request field: the versions to test. */
    public static final String REQUEST_FIELD = "officialBuilds";
    /**
     * Baseline token of an official build (part of its per-commit image name): a Builder build of
     * the same commit is another binary, and must not share an image with it.
     */
    public static final String BASELINE_KEY = "store";
    /** At most this many builds per request, as a guard against a runaway selection. */
    public static final int MAX_BUILDS = 20;

    private static final Pattern VERSION = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,4})\\.(\\d{1,6})-([0-9a-f]{7,40})$");

    private OfficialBuilds() {}

    public static boolean isValidVersion(String version) {
        return version != null && VERSION.matcher(version).matches();
    }

    /** The commit a version was built from (its hash suffix). */
    public static String commitOf(String version) {
        return matcher(version).group(5);
    }

    /** The release line of a version: "11.5" for 11.5.0.2856-1b26459. */
    public static String seriesOf(String version) {
        Matcher m = matcher(version);
        return m.group(1) + "." + m.group(2);
    }

    /** Only release and debug packages are published. */
    public static boolean isSupportedBuildType(String buildType) {
        String t = buildType == null ? "release" : buildType.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || "release".equals(t) || "debug".equals(t);
    }

    /** The Linux package of version for buildType (release or debug) under storeUrl (ending in '/'). */
    public static String packageUrl(String storeUrl, String version, String buildType) {
        matcher(version);
        boolean debug = buildType != null && "debug".equalsIgnoreCase(buildType.trim());
        return storeUrl + version + "/drop/CUBRID-" + version + "-Linux.x86_64" + (debug ? "-debug" : "") + ".tar.gz";
    }

    /** Oldest first, by the numeric parts of the version (11.4.7.1995 before 11.5.0.2813). */
    public static final Comparator<String> OLDEST_FIRST = (a, b) -> {
        Matcher ma = matcher(a);
        Matcher mb = matcher(b);
        for (int g = 1; g <= 4; g++) {
            int c = Long.compare(Long.parseLong(ma.group(g)), Long.parseLong(mb.group(g)));
            if (c != 0) {
                return c;
            }
        }
        return a.compareTo(b);
    };

    /**
     * The versions a request names, validated and without duplicates, in the order given.
     * Throws IllegalArgumentException with the reason when the field cannot be used.
     */
    public static List<String> parse(Object raw) {
        if (!(raw instanceof JSONArray)) {
            throw new IllegalArgumentException("'" + REQUEST_FIELD + "' must be an array of versions such as 11.5.0.2856-1b26459");
        }
        JSONArray array = (JSONArray) raw;
        Set<String> versions = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String v = String.valueOf(array.get(i)).trim();
            if (isValidVersion(v)) {
                versions.add(v);
            } else {
                invalid.add(v.isEmpty() ? "\"\" (empty)" : v);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Invalid official build version(s): " + String.join(", ", invalid)
                + ". Expected versions as the build store names them, e.g. 11.5.0.2856-1b26459");
        }
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("'" + REQUEST_FIELD + "' must name at least one version");
        }
        if (versions.size() > MAX_BUILDS) {
            throw new IllegalArgumentException("At most " + MAX_BUILDS + " official builds per request (got " + versions.size() + ")");
        }
        // Results, reports and images identify a build by its commit
        Map<String, String> byCommit = new HashMap<>();
        for (String v : versions) {
            String other = byCommit.put(commitOf(v), v);
            if (other != null) {
                throw new IllegalArgumentException("Official builds " + other + " and " + v + " are of the same commit; choose one");
            }
        }
        return new ArrayList<>(versions);
    }

    /** Null when url answers a HEAD request with 200, otherwise why it cannot be used. */
    public static String checkAvailable(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            return code == 200 ? null : "HTTP " + code;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Matcher matcher(String version) {
        Matcher m = VERSION.matcher(version == null ? "" : version);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not an official build version: " + version);
        }
        return m;
    }
}
