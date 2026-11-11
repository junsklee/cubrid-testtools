package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.tester.SafeIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One shard of a full SQL suite run as a normal CTP SQL run, as cubridci's gha-ci runs its shards:
 * the shard's tree is the testcases' sql/ with every other shard's top-level _* directory left out
 * (what `/entrypoint.sh keep dir` leaves), CTP runs the whole tree once, and its summary_info files
 * hold one verdict a case. Shared by the Docker and the VM shard executors.
 */
public final class SqlBatch {
    /** The verdicts a shard returns: one line a case, repository-relative path, ok|nok, milliseconds. */
    public static final String VERDICTS_SUFFIX = ".cases.tsv";

    // <absolute case path>:ok    674ms   (or :nok)
    private static final Pattern SUMMARY_LINE = Pattern.compile("^(/.+\\.sql):(ok|nok)\\s+(\\d+)ms\\s*$");

    private SqlBatch() {}

    /** A case's verdict in a CTP run. */
    public static final class Verdict {
        public final String path;
        public final boolean ok;
        public final long ms;

        public Verdict(String path, boolean ok, long ms) {
            this.path = path;
            this.ok = ok;
            this.ms = ms;
        }
    }

    /**
     * Copies requestRepo's sql/ into target/sql: every entry but the top-level _* directories the
     * shard does not own (config/ and the like stay, as `keep dir` leaves them). A copy, not links:
     * CTP writes each case's .result next to it. Returns the number of case files staged.
     */
    public static int stageShardTree(Path requestRepo, Collection<String> shardDirs, Path target) throws IOException {
        Path sqlRoot = requestRepo.resolve("sql");
        if (!Files.isDirectory(sqlRoot)) {
            throw new IOException("No sql/ in " + requestRepo);
        }
        Set<String> owned = new HashSet<>();
        for (String dir : shardDirs) {
            String name = dir.startsWith("sql/") ? dir.substring(4) : dir;
            if (name.isEmpty() || name.contains("/") || name.startsWith(".")) {
                throw new IOException("Not a top-level sql/ directory: " + dir);
            }
            if (!Files.isDirectory(sqlRoot.resolve(name))) {
                throw new IOException("No directory sql/" + name + " in " + requestRepo);
            }
            owned.add(name);
        }
        Path targetSql = target.resolve("sql");
        SafeIo.deleteDirectory(target.toFile());
        Files.createDirectories(targetSql);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sqlRoot)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && name.startsWith("_") && !owned.contains(name)) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    SafeIo.copyTestCaseDirectory(entry, targetSql.resolve(name));
                } else {
                    Files.copy(entry, targetSql.resolve(name));
                }
            }
        }
        return countCases(targetSql);
    }

    /** The number of case files (cases/NAME.sql) under sqlRoot. */
    static int countCases(Path sqlRoot) throws IOException {
        try (Stream<Path> files = Files.walk(sqlRoot)) {
            return (int) files.filter(p -> {
                String s = p.toString();
                return s.endsWith(".sql") && s.contains("/cases/") && Files.isRegularFile(p);
            }).count();
        }
    }

    /**
     * The verdicts in every summary_info under resultRoot written after sinceMillis, keyed by the
     * case's path relative to treeRoot (the directory holding sql/, as CTP saw it: a container path
     * in Docker). A case listed twice keeps its last verdict.
     */
    public static Map<String, Verdict> collect(Path resultRoot, long sinceMillis, String treeRoot) throws IOException {
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        if (!Files.isDirectory(resultRoot)) {
            return verdicts;
        }
        List<Path> summaries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(resultRoot)) {
            files.filter(p -> "summary_info".equals(p.getFileName().toString()) && Files.isRegularFile(p))
                .forEach(summaries::add);
        }
        for (Path summary : summaries) {
            if (Files.getLastModifiedTime(summary).toMillis() + 1000 < sinceMillis) {
                continue;
            }
            parse(new String(Files.readAllBytes(summary), StandardCharsets.UTF_8), treeRoot, verdicts);
        }
        return verdicts;
    }

    /** Adds the case lines of one summary_info to verdicts. */
    static void parse(String summaryInfo, String treeRoot, Map<String, Verdict> verdicts) {
        String prefix = treeRoot.endsWith("/") ? treeRoot : treeRoot + "/";
        for (String line : summaryInfo.split("\n")) {
            Matcher m = SUMMARY_LINE.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            String abs = m.group(1);
            String rel;
            if (abs.startsWith(prefix)) {
                rel = abs.substring(prefix.length());
            } else {
                int i = abs.indexOf("/sql/");
                if (i < 0) {
                    continue;
                }
                rel = abs.substring(i + 1);
            }
            verdicts.put(rel, new Verdict(rel, "ok".equals(m.group(2)), Long.parseLong(m.group(3))));
        }
    }

    /** The verdicts as the shard's artifact: rel-path TAB ok|nok TAB ms, one line a case. */
    public static String toTsv(Map<String, Verdict> verdicts) {
        StringBuilder out = new StringBuilder();
        for (Verdict v : verdicts.values()) {
            out.append(v.path).append('\t').append(v.ok ? "ok" : "nok").append('\t').append(v.ms).append('\n');
        }
        return out.toString();
    }

    /** The Builder's side of toTsv. Lines that are not a verdict are skipped. */
    public static Map<String, Verdict> fromTsv(String tsv) {
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        for (String line : tsv.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 3 || !f[0].startsWith("sql/") || !("ok".equals(f[1]) || "nok".equals(f[1]))) {
                continue;
            }
            long ms;
            try {
                ms = Long.parseLong(f[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            verdicts.put(f[0], new Verdict(f[0], "ok".equals(f[1]), ms));
        }
        return verdicts;
    }
}
