package com.navercorp.cubridqa.builder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
