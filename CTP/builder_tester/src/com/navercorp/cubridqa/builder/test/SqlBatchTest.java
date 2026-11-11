package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.SqlFullSuite;
import com.navercorp.cubridqa.builder.exec.SqlBatch;
import com.navercorp.cubridqa.builder.tester.TestRequest;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The full SQL suite in CTP batches, as cubridci's gha-ci runs it: the shard plan, a shard's tree,
 * CTP's summary_info read back as verdicts, and the shard request a tester receives.
 */
public class SqlBatchTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== SqlBatchTest Suite ===\n");
        testPlan();
        testLimit();
        testSummary();
        testRequestFields();
        testWithScenario();
        Path tmp = Files.createTempDirectory("sql_batch_test");
        try {
            testStageTree(tmp);
            testCollect(tmp);
        } finally {
            try (Stream<Path> paths = Files.walk(tmp)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        System.out.println("\n=== All SqlBatchTest tests passed! ===");
    }

    private static List<String> cases(String dir, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(String.format("%s/sub/cases/c%04d.sql", dir, i));
        }
        return out;
    }

    private static void testPlan() {
        System.out.println("Test: shards of whole top-level directories, balanced by case count");
        List<String> all = new ArrayList<>();
        all.addAll(cases("sql/_01_object", 3324));
        all.addAll(cases("sql/_13_issues", 1796));
        all.addAll(cases("sql/_23_apricot_qa", 1980));
        all.addAll(cases("sql/_27_banana_qa", 2052));
        all.addAll(cases("sql/_29_recovery", 2));
        all.addAll(cases("sql/_36_guava", 12));
        Collections.shuffle(all, new java.util.Random(7));

        List<SqlFullSuite.Shard> plan = SqlFullSuite.planShards(all, 3);
        assert plan.size() == 3 : plan.size();
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (SqlFullSuite.Shard s : plan) {
            assert s.of == 3;
            for (String d : s.dirs) {
                assert seen.add(d) : "a directory in two shards: " + d;
            }
            for (String c : s.cases) {
                assert s.dirs.contains(SqlFullSuite.topLevelDir(c)) : c + " outside its shard's directories";
            }
            total += s.cases.size();
        }
        assert total == all.size() : "cases lost or duplicated: " + total;
        // Largest first into the emptiest: 3324 | 2052+12+2 | 1980+1796 -> the longest 3776
        int max = plan.stream().mapToInt(s -> s.cases.size()).max().getAsInt();
        assert max == 3776 : "longest shard " + max;
        assert "1of3".equals(plan.get(0).label()) && plan.get(0).dirs.equals(Collections.singletonList("sql/_01_object"));

        // More shards than directories: one a directory, labels padded to the count's width
        List<SqlFullSuite.Shard> wide = SqlFullSuite.planShards(all, 10);
        assert wide.size() == 6 : wide.size();
        assert "1of6".equals(wide.get(0).label());
        assert SqlFullSuite.planShards(cases("sql/_01_object", 5), 12).size() == 1;
        List<SqlFullSuite.Shard> twelve = SqlFullSuite.planShards(manyDirs(12), 12);
        assert "01of12".equals(twelve.get(0).label()) && "12of12".equals(twelve.get(11).label()) : twelve.get(0).label();
        System.out.println("  PASS");
    }

    private static List<String> manyDirs(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.addAll(cases(String.format("sql/_%02d_dir", i), 3));
        }
        return out;
    }

    private static void testLimit() {
        System.out.println("Test: the suite limited to some top-level directories");
        List<String> all = new ArrayList<>(cases("sql/_01_object", 3));
        all.addAll(cases("sql/_01_objects", 2)); // a prefix of another name is not that directory
        all.addAll(cases("sql/_13_issues", 4));
        assert SqlFullSuite.limitTo(all, new HashSet<>(Arrays.asList("sql/_01_object"))).size() == 3;
        assert SqlFullSuite.limitTo(all, Collections.emptySet()).size() == 9;
        assert SqlFullSuite.isTopLevelDir("sql/_01_object");
        assert !SqlFullSuite.isTopLevelDir("sql/_01_object/sub") && !SqlFullSuite.isTopLevelDir("sql/config")
            && !SqlFullSuite.isTopLevelDir("sql/_..") && !SqlFullSuite.isTopLevelDir("shell/_01");
        assert "sql/_13_issues".equals(SqlFullSuite.topLevelDir("sql/_13_issues/_a/cases/x.sql"));
        System.out.println("  PASS");
    }

    private static void testSummary() throws Exception {
        System.out.println("Test: summary_info read back as verdicts, and the verdicts file");
        String summary = "total:3\nsuccess:2\nfail:1\ntotalTime:900ms\nSiteRunTimes:0times\n"
            + "/home/cubrid-testcases/sql/_01_object/a/cases/x.sql:ok    674ms\n"
            + "/home/cubrid-testcases/sql/_01_object/a/cases/y.sql:nok    12ms\n"
            + "/home/cubrid-testcases/sql/_01_object/a/cases/with space.sql:ok    3ms\n"
            + "/home/cubrid-testcases/sql/_01_object/a/cases/z.sql:    5ms\n"; // neither ok nor nok: skipped
        Method parse = SqlBatch.class.getDeclaredMethod("parse", String.class, String.class, Map.class);
        parse.setAccessible(true);
        Map<String, SqlBatch.Verdict> v = new java.util.LinkedHashMap<>();
        parse.invoke(null, summary, "/home/cubrid-testcases", v);
        assert v.size() == 3 : v.keySet();
        assert v.get("sql/_01_object/a/cases/x.sql").ok && v.get("sql/_01_object/a/cases/x.sql").ms == 674;
        assert !v.get("sql/_01_object/a/cases/y.sql").ok;
        assert v.containsKey("sql/_01_object/a/cases/with space.sql");

        // A VM's tree: another root, found by the sql/ component
        Map<String, SqlBatch.Verdict> vm = new java.util.LinkedHashMap<>();
        parse.invoke(null, "/home/shell/x/batch_1of2/cubrid-testcases/sql/_13_issues/b/cases/q.sql:nok    9ms\n",
            "/elsewhere", vm);
        assert vm.containsKey("sql/_13_issues/b/cases/q.sql") : vm.keySet();

        Map<String, SqlBatch.Verdict> back = SqlBatch.fromTsv(SqlBatch.toTsv(v) + "garbage line\nsql/x\tmaybe\t1\n");
        assert back.size() == 3 && !back.get("sql/_01_object/a/cases/y.sql").ok && back.get("sql/_01_object/a/cases/x.sql").ms == 674;
        System.out.println("  PASS");
    }

    private static void testRequestFields() {
        System.out.println("Test: a shard request on the tester");
        JSONObject json = new JSONObject().put("testPath", "sqlbatch:1of2").put("buildPackage", "http://x/p.tar.gz")
            .put("testType", "sql").put("sqlBatch", new JSONObject().put("shard", "1of2")
                .put("dirs", new JSONArray().put("sql/_01_object").put("sql/../etc").put("sql/_13_issues/sub").put("sql/_13_issues"))
                .put("timeoutMinutes", 90));
        TestRequest r = new TestRequest(json);
        assert r.isSqlBatch();
        assert r.getSqlBatchDirs().equals(Arrays.asList("sql/_01_object", "sql/_13_issues")) : r.getSqlBatchDirs();
        assert "1of2".equals(r.getSqlBatchShard()) && r.getSqlBatchTimeoutMinutes() == 90;
        assert r.getSqlBatchMemoryLimitMb() == 4096 : "a shard is always memory-capped, 4 GB by default";
        json.getJSONObject("sqlBatch").put("memoryLimitMb", 6144);
        assert new TestRequest(json).getSqlBatchMemoryLimitMb() == 6144;
        json.getJSONObject("sqlBatch").put("memoryLimitMb", 10);
        assert new TestRequest(json).getSqlBatchMemoryLimitMb() == 1024 : "never below 1 GB";
        assert !new TestRequest(new JSONObject().put("testPath", "sql/_01/cases/a.sql").put("buildPackage", "p")
            .put("testType", "sql")).isSqlBatch();
        // A shell request never is a shard
        assert !new TestRequest(json.put("testType", "shell")).isSqlBatch();
        System.out.println("  PASS");
    }

    private static void testWithScenario() throws Exception {
        System.out.println("Test: a VM's SQL conf pointed at the shard's tree");
        Class<?> vm = Class.forName("com.navercorp.cubridqa.builder.exec.VmSqlExecutor");
        Method m = vm.getDeclaredMethod("withScenario", String.class, String.class);
        m.setAccessible(true);
        String conf = "[sql]\n# The location of your testing scenario\nscenario=${HOME}/cubrid-testcases/sql\ndata_file=x\n";
        String out = (String) m.invoke(null, conf, "/w/batch_1of2/cubrid-testcases/sql");
        assert out.contains("\nscenario=/w/batch_1of2/cubrid-testcases/sql\n") && !out.contains("${HOME}/cubrid-testcases") : out;
        assert out.endsWith("data_file=x\n") : out;
        String none = (String) m.invoke(null, "[sql]\ndata_file=x", "/t/sql");
        assert none.endsWith("data_file=x\nscenario=/t/sql\n") : none;
        System.out.println("  PASS");
    }

    private static void testStageTree(Path tmp) throws Exception {
        System.out.println("Test: a shard's tree is sql/ without the other shards' directories");
        Path repo = tmp.resolve("repo");
        for (String f : new String[] {"sql/_01_object/a/cases/x.sql", "sql/_01_object/a/answers/x.answer",
                "sql/_13_issues/b/cases/y.sql", "sql/config/daily_regression_test_exclude_list_ha_repl.conf",
                "sql/README.md", "shell/_01/cases/s.sh"}) {
            Path p = repo.resolve(f);
            Files.createDirectories(p.getParent());
            Files.write(p, "x".getBytes(StandardCharsets.UTF_8));
        }
        Path target = tmp.resolve("tree");
        int n = SqlBatch.stageShardTree(repo, Arrays.asList("sql/_01_object"), target);
        assert n == 1 : n;
        assert Files.isRegularFile(target.resolve("sql/_01_object/a/answers/x.answer"));
        assert !Files.exists(target.resolve("sql/_13_issues")) : "another shard's directory";
        assert Files.isRegularFile(target.resolve("sql/config/daily_regression_test_exclude_list_ha_repl.conf"))
            && Files.isRegularFile(target.resolve("sql/README.md")) : "non-shard entries stay, as keep dir leaves them";
        assert !Files.exists(target.resolve("shell"));
        // Staging again starts from scratch
        assert SqlBatch.stageShardTree(repo, Arrays.asList("sql/_13_issues"), target) == 1;
        assert !Files.exists(target.resolve("sql/_01_object"));
        for (String bad : new String[] {"sql/_99_missing", "sql/_01_object/a", "sql/.hidden"}) {
            try {
                SqlBatch.stageShardTree(repo, Arrays.asList(bad), target);
                throw new AssertionError("staged " + bad);
            } catch (java.io.IOException expected) {
                // refused
            }
        }
        System.out.println("  PASS");
    }

    private static void testCollect(Path tmp) throws Exception {
        System.out.println("Test: verdicts from this run's summary_info files only");
        Path result = tmp.resolve("result");
        Path old = result.resolve("y2026/m10/run_a/sql/_01_object/summary_info");
        Path now = result.resolve("y2026/m11/run_b/sql/_13_issues/summary_info");
        Files.createDirectories(old.getParent());
        Files.createDirectories(now.getParent());
        Files.write(old, "/t/sql/_01_object/a/cases/x.sql:nok    1ms\n".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        long since = System.currentTimeMillis() - 60_000;
        Files.write(now, "/t/sql/_13_issues/b/cases/y.sql:ok    2ms\n".getBytes(StandardCharsets.UTF_8));
        Map<String, SqlBatch.Verdict> v = SqlBatch.collect(result, since, "/t");
        assert v.size() == 1 && v.containsKey("sql/_13_issues/b/cases/y.sql") : v.keySet();
        assert SqlBatch.collect(tmp.resolve("missing"), since, "/t").isEmpty();
        System.out.println("  PASS");
    }
}
