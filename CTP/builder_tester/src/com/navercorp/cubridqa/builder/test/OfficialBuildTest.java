package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.Builder;
import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.OfficialBuilds;
import com.navercorp.cubridqa.builder.SqlFullSuite;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Official builds (officialBuilds: tested from the build store instead of built) and the full SQL
 * suite (sqlFullSuite: every SQL case of the testcases branch).
 */
public class OfficialBuildTest {

    private static final String V115 = "11.5.0.2856-1b26459";
    private static final String V114 = "11.4.7.1995-f39ab8c";
    private static final String V101 = "10.1.3.7698-553a7231d";

    public static void main(String[] args) throws Exception {
        System.out.println("=== OfficialBuildTest Suite ===\n");
        testVersions();
        testParse();
        Path tmp = Files.createTempDirectory("official_build_test");
        try {
            testValidation(tmp);
            testFullSuiteListing(tmp);
            testOfficialArchiveLayout(tmp);
        } finally {
            try (Stream<Path> paths = Files.walk(tmp)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        System.out.println("\n=== All OfficialBuildTest tests passed! ===");
    }

    private static void testVersions() {
        System.out.println("Test: versions, commits, release lines and package URLs");
        assert OfficialBuilds.isValidVersion(V115);
        assert OfficialBuilds.isValidVersion(V101) : "a 9-digit commit, as 10.1 builds have";
        for (String bad : new String[] {"", "11.5", "$CUBRID_VERSION", "tmp", "11.5.0.2856", "11.5.0.2856-XYZ1234",
                "11.5.0.2856-1b26459/", "../11.5.0.2856-1b26459", "11.5.0.2856-1b26459\n"}) {
            assert !OfficialBuilds.isValidVersion(bad) : "not a version: " + bad;
        }
        assert "1b26459".equals(OfficialBuilds.commitOf(V115));
        assert "553a7231d".equals(OfficialBuilds.commitOf(V101));
        assert "11.4".equals(OfficialBuilds.seriesOf(V114));

        String store = "http://store.example/REPO_ROOT/store_01/";
        assert (store + V115 + "/drop/CUBRID-" + V115 + "-Linux.x86_64.tar.gz")
            .equals(OfficialBuilds.packageUrl(store, V115, "release"));
        assert (store + V115 + "/drop/CUBRID-" + V115 + "-Linux.x86_64-debug.tar.gz")
            .equals(OfficialBuilds.packageUrl(store, V115, "DEBUG"));
        assert OfficialBuilds.packageUrl(store, V115, null).endsWith("x86_64.tar.gz");

        assert OfficialBuilds.isSupportedBuildType("release") && OfficialBuilds.isSupportedBuildType("debug")
            && OfficialBuilds.isSupportedBuildType(null) && OfficialBuilds.isSupportedBuildType("");
        assert !OfficialBuilds.isSupportedBuildType("coverage") && !OfficialBuilds.isSupportedBuildType("profile");

        // Numeric, not lexical: 11.5.0.10000 is newer than 11.5.0.9999, and 10.x before 11.x
        List<String> versions = new ArrayList<>(Arrays.asList(V115, "11.5.0.10000-aaaaaaa", V101, V114, "11.5.0.9999-bbbbbbb"));
        versions.sort(OfficialBuilds.OLDEST_FIRST);
        assert versions.equals(Arrays.asList(V101, V114, V115, "11.5.0.9999-bbbbbbb", "11.5.0.10000-aaaaaaa")) : versions;
        System.out.println("  PASS");
    }

    private static void testParse() {
        System.out.println("Test: officialBuilds field parsing");
        assert OfficialBuilds.parse(new JSONArray().put(V115).put(" " + V114 + " ").put(V115))
            .equals(Arrays.asList(V115, V114)) : "trimmed, duplicates dropped, order kept";
        expectParseError("x", "must be an array");
        expectParseError(new JSONArray(), "at least one");
        expectParseError(new JSONArray().put(V115).put("11.5.0.2856"), "Invalid official build version(s): 11.5.0.2856");
        expectParseError(new JSONArray().put(V115).put("11.5.0.2857-1b26459"), "same commit");
        JSONArray many = new JSONArray();
        for (int i = 0; i <= OfficialBuilds.MAX_BUILDS; i++) {
            many.put(String.format("11.5.0.%d-%07x", 1000 + i, i));
        }
        expectParseError(many, "At most " + OfficialBuilds.MAX_BUILDS);
        System.out.println("  PASS");
    }

    private static void expectParseError(Object raw, String expected) {
        try {
            OfficialBuilds.parse(raw);
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains(expected) : "'" + e.getMessage() + "' should contain '" + expected + "'";
            return;
        }
        throw new AssertionError("parse(" + raw + ") should fail with " + expected);
    }

    /** The Builder's request validation, against a store that has the release package of V115 only. */
    private static void testValidation(Path tmp) throws Exception {
        System.out.println("Test: Builder validation of officialBuilds and sqlFullSuite");
        String releasePath = "/store/" + V115 + "/drop/CUBRID-" + V115 + "-Linux.x86_64.tar.gz";
        List<String> heads = Collections.synchronizedList(new ArrayList<>());
        HttpServer store = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        store.createContext("/store/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            heads.add(exchange.getRequestMethod() + " " + path);
            // A slow store: 1 s for each of the 11.5.0.510x builds
            boolean slow = path.contains("/11.5.0.510");
            if (slow) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            }
            boolean found = slow || releasePath.equals(path);
            exchange.sendResponseHeaders(found ? 200 : 404, -1);
            exchange.close();
        });
        store.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        store.start();
        try {
            String storeUrl = "http://127.0.0.1:" + store.getAddress().getPort() + "/store";
            assert OfficialBuilds.checkAvailable(storeUrl + releasePath.substring("/store".length()), 2000) == null;
            assert "HTTP 404".equals(OfficialBuilds.checkAvailable(storeUrl + "/nothing.tar.gz", 2000));
            assert OfficialBuilds.checkAvailable("http://127.0.0.1:1/x.tar.gz", 2000) != null : "refused connection";

            Builder builder = bareBuilder(tmp, storeUrl);

            // Official build + full SQL suite: no commits and no tests needed
            JSONObject ok = base().put("officialBuilds", new JSONArray().put(V115).put(V115))
                .put("testType", "sql").put("sqlFullSuite", true);
            validate(builder, ok);
            assert ok.getJSONArray("officialBuilds").length() == 1 : "deduplicated: " + ok;
            assert ok.getJSONArray("tests").length() == 0;
            assert heads.contains("HEAD " + releasePath) : "package checked with HEAD: " + heads;
            assert storeUrlOf(builder).equals(storeUrl + "/") : "store URL ends with /";

            // sqlFullSuite alone makes the request SQL
            JSONObject inferred = base().put("officialBuilds", new JSONArray().put(V115)).put("sqlFullSuite", true);
            validate(builder, inferred);
            assert "sql".equals(inferred.getString("testType"));

            // Official build with listed cases
            validate(builder, base().put("officialBuilds", new JSONArray().put(V115))
                .put("tests", new JSONArray().put("shell/_01/x/cases/x.sh")));

            expectInvalid(builder, base().put("officialBuilds", new JSONArray().put(V115)).put("buildType", "debug")
                .put("tests", new JSONArray().put("shell/_01/x/cases/x.sh")), "No debug package for official build(s): " + V115);
            expectInvalid(builder, base().put("officialBuilds", new JSONArray().put(V114))
                .put("tests", new JSONArray().put("shell/_01/x/cases/x.sh")), "No release package");
            expectInvalid(builder, base().put("officialBuilds", new JSONArray().put(V115)).put("buildType", "coverage")
                .put("tests", new JSONArray().put("shell/_01/x/cases/x.sh")), "release and debug packages only");
            expectInvalid(builder, base().put("officialBuilds", new JSONArray().put(V115)).put("commits", new JSONArray().put("abc1234"))
                .put("tests", new JSONArray().put("shell/_01/x/cases/x.sh")), "not both");
            expectInvalid(builder, base().put("officialBuilds", new JSONArray().put(V115)).put("prNumber", 6402)
                .put("tests", new JSONArray().put("shell/_01/x/cases/x.sh")), "not both");
            expectInvalid(builder, base().put("officialBuilds", new JSONArray().put(V115)).put("buildOnly", true),
                "buildOnly does not apply");
            expectInvalid(builder, base().put("officialBuilds", new JSONArray().put("11.5"))
                .put("tests", new JSONArray().put("shell/_01/x/cases/x.sh")), "Invalid official build version");

            expectInvalid(builder, base().put("commits", new JSONArray().put("abc1234")).put("testType", "shell")
                .put("sqlFullSuite", true), "requires testType=sql");
            expectInvalid(builder, base().put("commits", new JSONArray().put("abc1234")).put("testType", "sql")
                .put("sqlFullSuite", true).put("tests", new JSONArray().put("sql/_01/a/cases/1.sql")), "not both");
            expectInvalid(builder, base().put("commits", new JSONArray().put("abc1234")).put("testType", "sql")
                .put("sqlFullSuite", true).put("customSqlScript", "select 1;").put("customSqlAnswer", "1"), "custom script");
            expectInvalid(builder, base(), "'officialBuilds'");

            // Several builds are checked at once: the dashboard's gateway gives up on /build after 30 s
            JSONArray slow = new JSONArray();
            for (int i = 1; i <= 4; i++) {
                slow.put("11.5.0.510" + i + "-aaaaaa" + i);
            }
            long started = System.nanoTime();
            validate(builder, base().put("officialBuilds", slow).put("tests", new JSONArray().put("shell/_01/x/cases/x.sh")));
            long tookMs = (System.nanoTime() - started) / 1_000_000;
            assert tookMs < 3000 : "4 builds behind a 1 s store took " + tookMs + " ms";

            // Cheap checks come first: a request refused for its tests never waits for the store
            int headsBefore = heads.size();
            expectInvalid(builder, base().put("officialBuilds", new JSONArray().put(V115))
                .put("tests", new JSONArray().put("http://example/report")), "Invalid test path");
            assert heads.size() == headsBefore : "store checked for a request refused anyway";

            // A build-from-source SQL request is unchanged
            JSONObject commits = base().put("commits", new JSONArray().put("abc1234")).put("testType", "sql")
                .put("tests", new JSONArray().put("sql/_01/a/cases/1.sql"));
            validate(builder, commits);
            assert !commits.has("sqlFullSuite");
        } finally {
            store.stop(0);
        }
        System.out.println("  PASS");
    }

    private static String storeUrlOf(Builder builder) throws Exception {
        Field f = Builder.class.getDeclaredField("config");
        f.setAccessible(true);
        return ((BuilderConfig) f.get(builder)).getOfficialBuildStoreUrl();
    }

    private static JSONObject base() {
        return new JSONObject().put("callbackUrl", "http://127.0.0.1:1/callback")
            .put("workerIps", new JSONArray().put("localhost"));
    }

    private static void validate(Builder builder, JSONObject request) throws Exception {
        Method m = Builder.class.getDeclaredMethod("validateRequest", JSONObject.class);
        m.setAccessible(true);
        try {
            m.invoke(builder, request);
        } catch (InvocationTargetException e) {
            throw new AssertionError("valid request refused: " + request + ": " + e.getCause().getMessage(), e.getCause());
        }
    }

    private static void expectInvalid(Builder builder, JSONObject request, String expected) throws Exception {
        Method m = Builder.class.getDeclaredMethod("validateRequest", JSONObject.class);
        m.setAccessible(true);
        try {
            m.invoke(builder, request);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assert cause instanceof IllegalArgumentException : "client error expected, got " + cause;
            assert cause.getMessage().contains(expected) : "'" + cause.getMessage() + "' should contain '" + expected + "'";
            return;
        }
        throw new AssertionError("should be refused (" + expected + "): " + request);
    }

    /** The cases of the pinned commit: sql/**&#47;cases/*.sql only, sorted, nothing uncommitted. */
    private static void testFullSuiteListing(Path tmp) throws Exception {
        System.out.println("Test: full SQL suite listing");
        Path repo = Files.createDirectories(tmp.resolve("cubrid-testcases"));
        git(repo, "init", "-q");
        String[] files = {
            "sql/_02_user/b/cases/2.sql", "sql/_01_object/a/cases/1.sql", "sql/_01_object/a/answers/1.answer",
            "sql/config/daily_regression_test_exclude_list_ha_repl.conf", "sql/_01_object/a/cases/readme.txt",
            "sql/odd dir/cases/with space.sql", "shell/_01/x/cases/x.sql", "sql/top.sql", "sql/_03/cases/sub/deep.sql"
        };
        for (String f : files) {
            Path p = repo.resolve(f);
            Files.createDirectories(p.getParent());
            Files.write(p, "select 1;\n".getBytes(StandardCharsets.UTF_8));
        }
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "cases");
        String head = gitOut(repo, "rev-parse", "HEAD");
        // Not in the pinned commit
        Path later = repo.resolve("sql/_04/cases/later.sql");
        Files.createDirectories(later.getParent());
        Files.write(later, "select 2;\n".getBytes(StandardCharsets.UTF_8));

        List<String> cases = SqlFullSuite.listCases(repo.toFile(), head);
        assert cases.equals(Arrays.asList("sql/_01_object/a/cases/1.sql", "sql/_02_user/b/cases/2.sql",
            "sql/_03/cases/sub/deep.sql", "sql/odd dir/cases/with space.sql")) : cases;

        try {
            SqlFullSuite.listCases(repo.toFile(), "develop; rm -rf /");
            throw new AssertionError("a ref that is not a commit must be refused");
        } catch (IllegalArgumentException expected) {
            // refused before git runs
        }
        try {
            SqlFullSuite.listCases(repo.toFile(), "0123456789abcdef0123456789abcdef01234567");
            throw new AssertionError("an unknown commit must fail");
        } catch (java.io.IOException expected) {
            assert expected.getMessage().contains("git ls-tree") : expected.getMessage();
        }
        System.out.println("  PASS");
    }

    /** Official packages unpack from CUBRID/, the Builder's from _install/CUBRID/. */
    private static void testOfficialArchiveLayout(Path tmp) throws Exception {
        System.out.println("Test: official package layout detection");
        Path official = makeArchive(tmp, "official", "CUBRID/bin/cubrid_rel");
        Path builder = makeArchive(tmp, "builder", "_install/CUBRID/bin/cubrid_rel");
        Path notTar = tmp.resolve("not.tar.gz");
        Files.write(notTar, "garbage".getBytes(StandardCharsets.UTF_8));

        Class<?> installer = Class.forName("com.navercorp.cubridqa.builder.exec.CubridInstaller");
        Method m = installer.getDeclaredMethod("archiveHasOfficialRoot", String.class);
        m.setAccessible(true);
        assert (Boolean) m.invoke(null, official.toString()) : "CUBRID/ layout";
        assert !(Boolean) m.invoke(null, builder.toString()) : "_install/CUBRID layout";
        assert !(Boolean) m.invoke(null, notTar.toString()) : "not an archive";
        assert !(Boolean) m.invoke(null, tmp.resolve("missing.tar.gz").toString()) : "missing file";
        System.out.println("  PASS");
    }

    private static Path makeArchive(Path tmp, String name, String file) throws Exception {
        Path root = Files.createDirectories(tmp.resolve(name + "_root"));
        Path f = root.resolve(file);
        Files.createDirectories(f.getParent());
        Files.write(f, "#!/bin/sh\necho CUBRID 11.5\n".getBytes(StandardCharsets.UTF_8));
        Path archive = tmp.resolve(name + ".tar.gz");
        run(root, "tar", "-czf", archive.toString(), file.substring(0, file.indexOf('/')));
        return archive;
    }

    private static Builder bareBuilder(Path tmp, String storeUrl) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Builder builder = (Builder) unsafeType.getMethod("allocateInstance", Class.class)
            .invoke(unsafeField.get(null), Builder.class);
        Files.createDirectories(tmp.resolve("src"));
        Files.createDirectories(tmp.resolve("tc"));
        Path conf = tmp.resolve("builder.conf");
        Files.write(conf, ("cubrid_src_dir=" + tmp.resolve("src") + "\nshell_tc_dir=" + tmp.resolve("tc")
            + "\nwork_dir=" + tmp.resolve("work") + "\nofficial_build_store_url=" + storeUrl + "\n")
            .getBytes(StandardCharsets.UTF_8));
        Field f = Builder.class.getDeclaredField("config");
        f.setAccessible(true);
        f.set(builder, new BuilderConfig(conf.toString()));
        return builder;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        run(dir, cmd.toArray(new String[0]));
    }

    private static String gitOut(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
        assert p.waitFor() == 0 : cmd;
        return out;
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        assert p.waitFor() == 0 : Arrays.toString(cmd) + ": " + out;
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        for (int n; (n = in.read(chunk)) > 0; ) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
