package com.navercorp.cubridqa.builder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The full SQL suite of a request (sqlFullSuite: true): every case the SQL testcases repository has
 * under sql/ at the commit the request pinned (sql/**&#47;cases/&lt;name&gt;.sql, about 17,700 on develop),
 * as a regression SQL run takes the whole scenario directory. Listed from git, not a checkout, so
 * the list is the pinned commit's whatever the working tree holds.
 */
public final class SqlFullSuite {
    /** Request field: true runs every SQL case of the SQL testcases branch; 'tests' is then not needed. */
    public static final String REQUEST_FIELD = "sqlFullSuite";
    /** Optional request field: the top-level sql/ directories the suite is limited to. */
    public static final String DIRS_FIELD = "sqlSuiteDirs";
    /** The test path BuilderTask gives a shard ("sqlbatch:03"): not a case, never in results. */
    public static final String BATCH_TEST_PREFIX = "sqlbatch:";

    /** A top-level directory of sql/, as a request may name one in sqlSuiteDirs. */
    public static boolean isTopLevelDir(String dir) {
        return dir != null && dir.matches("sql/_[A-Za-z0-9_.-]+") && !dir.contains("..");
    }

    /** The top-level sql/ directory a case is in: sql/_01_object for sql/_01_object/.../cases/x.sql. */
    public static String topLevelDir(String casePath) {
        int second = casePath.indexOf('/', 4);
        return second < 0 ? casePath : casePath.substring(0, second);
    }

    /** One shard: whole top-level directories, run as one CTP SQL run. */
    public static final class Shard {
        public final int index;          // 1-based
        public final int of;
        public final List<String> dirs;  // sorted
        public final List<String> cases; // sorted

        Shard(int index, int of, List<String> dirs, List<String> cases) {
            this.index = index;
            this.of = of;
            this.dirs = Collections.unmodifiableList(dirs);
            this.cases = Collections.unmodifiableList(cases);
        }

        /** "03of10": names its test, logs and container. */
        public String label() {
            int width = String.valueOf(of).length();
            return String.format("%0" + width + "dof%d", index, of);
        }
    }

    /**
     * cases split into at most shards shards of whole top-level directories, the largest directory
     * first into the shard with the fewest cases (gha-ci splits by directory as well, in name order;
     * by size the longest shard is shorter). Empty shards are dropped.
     */
    public static List<Shard> planShards(Collection<String> cases, int shards) {
        Map<String, List<String>> byDir = new TreeMap<>();
        for (String c : cases) {
            byDir.computeIfAbsent(topLevelDir(c), k -> new ArrayList<>()).add(c);
        }
        List<Map.Entry<String, List<String>>> units = new ArrayList<>(byDir.entrySet());
        units.sort(Comparator.<Map.Entry<String, List<String>>>comparingInt(e -> e.getValue().size()).reversed()
            .thenComparing(Map.Entry::getKey));
        int n = Math.max(1, Math.min(shards, units.size()));
        List<List<String>> dirs = new ArrayList<>();
        List<List<String>> members = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dirs.add(new ArrayList<>());
            members.add(new ArrayList<>());
        }
        for (Map.Entry<String, List<String>> unit : units) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (members.get(i).size() < members.get(best).size()) {
                    best = i;
                }
            }
            dirs.get(best).add(unit.getKey());
            members.get(best).addAll(unit.getValue());
        }
        List<Shard> plan = new ArrayList<>();
        int count = 0;
        for (List<String> m : members) {
            if (!m.isEmpty()) {
                count++;
            }
        }
        int index = 0;
        for (int i = 0; i < n; i++) {
            if (members.get(i).isEmpty()) {
                continue;
            }
            Collections.sort(dirs.get(i));
            Collections.sort(members.get(i));
            plan.add(new Shard(++index, count, dirs.get(i), members.get(i)));
        }
        return plan;
    }

    /** cases limited to the top-level directories dirs (all of them when dirs is empty). */
    public static List<String> limitTo(List<String> cases, Collection<String> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return cases;
        }
        List<String> kept = new ArrayList<>();
        for (String c : cases) {
            if (dirs.contains(topLevelDir(c))) {
                kept.add(c);
            }
        }
        return kept;
    }

    private SqlFullSuite() {}

    /** The SQL cases of repo at commit, sorted. Throws when git cannot list them or there are none. */
    public static List<String> listCases(File repo, String commit) throws IOException, InterruptedException {
        if (commit == null || !commit.matches("[0-9a-fA-F]{7,40}")) {
            throw new IllegalArgumentException("Not a commit: " + commit);
        }
        // To a file, not a pipe: reading the pipe to its end would wait for git however long it
        // hangs (a lock, a slow disk), and the request holds the Builder's only slot meanwhile
        File listing = File.createTempFile("sql_full_suite_", ".lst");
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "ls-tree", "-r", "-z", "--name-only", commit, "--", "sql/");
            pb.directory(repo);
            pb.redirectOutput(listing);
            pb.redirectError(ProcessBuilder.Redirect.appendTo(new File("/dev/null")));
            Process p = pb.start();
            if (!p.waitFor(2, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("git ls-tree timed out in " + repo);
            }
            if (p.exitValue() != 0) {
                throw new IOException("git ls-tree " + commit + " failed in " + repo + " (exit " + p.exitValue() + ")");
            }
            List<String> cases = casesOf(new String(Files.readAllBytes(listing.toPath()), StandardCharsets.UTF_8));
            if (cases.isEmpty()) {
                throw new IOException("No SQL cases (sql/**/cases/*.sql) at " + commit + " in " + repo);
            }
            return cases;
        } finally {
            Files.deleteIfExists(listing.toPath());
        }
    }

    /** The case paths among NUL-separated repository paths, sorted. */
    static List<String> casesOf(String nulSeparatedPaths) {
        List<String> cases = new ArrayList<>();
        for (String path : nulSeparatedPaths.split("\0")) {
            if (!path.isEmpty() && Builder.isAllowedSqlTestPath(path) && path.indexOf('\n') < 0) {
                cases.add(path);
            }
        }
        Collections.sort(cases);
        return cases;
    }
}
