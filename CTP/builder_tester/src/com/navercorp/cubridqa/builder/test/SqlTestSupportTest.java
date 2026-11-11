package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderTask;
import com.navercorp.cubridqa.builder.exec.SqlResultParser;
import com.navercorp.cubridqa.builder.exec.SqlScriptFactory;
import com.navercorp.cubridqa.builder.tester.TestRequest;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Unit tests for the SQL tester support: test-name derivation, status-line
 * parsing, request-type plumbing, and the generated single-case runner.
 */
public class SqlTestSupportTest {

    public static void main(String[] args) throws Exception {
        testBuildSqlTestName();
        testStatusLineParsing();
        testStatusConsoleParsing();
        testTestRequestTypePlumbing();
        testCaseRunScript();
        testDbCharsetResolution();
        System.out.println("SqlTestSupportTest passed");
    }

    /**
     * The SQL test DB charset follows CTP run.sh do_configure: sql.conf's db_charset (en_US, which
     * CUBRID reads as ISO-8859-1), replaced by en_US.utf8 from CUBRID 11.5 (CUBRIDQA-1287); an
     * explicit sql_db_charset in tester.conf wins over both.
     */
    private static void testDbCharsetResolution() throws Exception {
        String rel115 = "11.5.0 (11.5.0.2565-b319ce1) (64bit debug build for Linux)";
        String rel114 = "11.4 (11.4.5.1906-4e4fa29) (64bit release build for Linux)";
        String rel120 = "12.0.0 (12.0.0.0001-0000000) (64bit release build for Linux)";
        assertEquals("en_US.utf8", createdbCharset(rel115, "en_US", null), "11.5 + sql.conf en_US -> utf8");
        assertEquals("en_US.utf8", createdbCharset(rel120, "en_US", null), "12.0 -> utf8");
        assertEquals("en_US.utf8", createdbCharset(rel115, null, null), "11.5 without db_charset -> utf8");
        assertEquals("en_US", createdbCharset(rel114, "en_US", null), "11.4 keeps sql.conf's en_US");
        assertEquals("en_US.iso88591", createdbCharset(rel114, null, null), "11.4 without db_charset -> iso88591");
        assertEquals("ko_KR.euckr", createdbCharset(rel115, "en_US", "ko_KR.euckr"), "tester.conf override wins");
    }

    /** Runs the generated VM env setup against fake cubrid tools; returns the createdb locale argument. */
    private static String createdbCharset(String rel, String confCharset, String override) throws Exception {
        Path root = Files.createTempDirectory("sqlcharset");
        try {
            Path ctp = root.resolve("ctp");
            Files.createDirectories(ctp.resolve("sql/lib"));
            Path ini = Files.createDirectories(ctp.resolve("bin")).resolve("ini.sh");
            // Only `ini.sh -s sql <file> <key>` reads; every other call (conf updates) is a no-op
            Files.write(ini, ("#!/bin/bash\n"
                + "[ \"$1\" = -s ] && [ \"$2\" = sql ] && [ $# -eq 4 ] && sed -n \"s/^$4=//p\" \"$3\" | head -1\n"
                + "exit 0\n").getBytes("UTF-8"));
            Path cubridBin = Files.createDirectories(root.resolve("cubrid/bin"));
            Files.createDirectories(root.resolve("cubrid/conf"));
            for (String conf : new String[] {"cubrid.conf", "cubrid_broker.conf", "cubrid_ha.conf"}) {
                Files.write(root.resolve("cubrid/conf/" + conf), new byte[0]);
            }
            Files.write(cubridBin.resolve("cubrid_rel"), ("#!/bin/bash\necho 'CUBRID " + rel + "'\n").getBytes("UTF-8"));
            Files.write(cubridBin.resolve("cubrid"), ("#!/bin/bash\n"
                + "if [ \"$1\" = createdb ]; then echo \"CREATEDB_ARGS $*\"; exit 3; fi\nexit 0\n").getBytes("UTF-8"));
            cubridBin.resolve("cubrid_rel").toFile().setExecutable(true);
            cubridBin.resolve("cubrid").toFile().setExecutable(true);
            Path conf = root.resolve("sql_local.conf");
            Files.write(conf, ("[sql]\n" + (confCharset == null ? "" : "db_charset=" + confCharset + "\n")
                + "need_make_locale=no\n").getBytes("UTF-8"));
            Path ws = Files.createDirectories(root.resolve("ws"));
            Files.createDirectories(root.resolve("tc"));
            Files.createDirectories(root.resolve("home"));

            SqlScriptFactory.SqlEnvSettings s = new SqlScriptFactory.SqlEnvSettings(
                "basic", override, null, false, false, null, null);
            Path script = root.resolve("sql_env_setup.sh");
            Files.write(script, SqlScriptFactory.createVmEnvSetupScript(s, root.resolve("cubrid").toString(),
                ctp.toString(), root.resolve("tc").toString(), ws.toString(), conf.toString(), false).getBytes("UTF-8"));

            ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
            pb.environment().put("HOME", root.resolve("home").toString());
            pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(readAll(p.getInputStream()), "UTF-8");
            p.waitFor();
            for (String line : out.split("\n")) {
                if (line.startsWith("CREATEDB_ARGS ")) {
                    String[] args = line.trim().split("\\s+");
                    return args[args.length - 1];
                }
            }
            throw new AssertionError("the setup never reached createdb:\n" + out);
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static void testBuildSqlTestName() {
        assertEquals("_01_object._01_type._004_integer.1014",
            BuilderTask.buildSqlTestName("sql/_01_object/_01_type/_004_integer/cases/1014.sql"),
            "standard 3-level case");
        assertEquals("_08_javasp.case_index_scan_01",
            BuilderTask.buildSqlTestName("sql/_08_javasp/cases/case_index_scan_01.sql"),
            "cases directly under suite");
        assertEquals("_13_issues._12_2h.bug_bts_8298",
            BuilderTask.buildSqlTestName("sql/_13_issues/_12_2h/cases/bug_bts_8298.sql"),
            "bug case name");
        // Uniqueness across suites with identical basenames
        String a = BuilderTask.buildSqlTestName("sql/_01_object/_01_type/_004_integer/cases/1001.sql");
        String b = BuilderTask.buildSqlTestName("sql/_02_user_authorization/_001_grant/cases/1001.sql");
        assert !a.equals(b) : "same basename in different suites must produce different test names";
    }

    private static void testStatusLineParsing() {
        SqlResultParser.SqlCaseOutcome ok = SqlResultParser.parseStatusLine("SQLCASE_RESULT status=ok rc=0 core=0");
        assert ok != null && ok.isPass() && !ok.core : "ok line parse";

        SqlResultParser.SqlCaseOutcome nok = SqlResultParser.parseStatusLine("SQLCASE_RESULT status=nok rc=0 core=1");
        assert nok != null && nok.isFail() && nok.core : "nok+core line parse";

        SqlResultParser.SqlCaseOutcome notrun =
            SqlResultParser.parseStatusLine("SQLCASE_RESULT status=notrun rc=0 core=0 reason=missing_answer");
        assert notrun != null && "notrun".equals(notrun.status) : "notrun parse";
        assertEquals("missing_answer", notrun.reason, "notrun reason");

        assert SqlResultParser.parseStatusLine("Testing /x/y.sql (1/1 100.00%) [OK]") == null
            : "non-marker lines must not parse";
    }

    private static void testStatusConsoleParsing() {
        String console = "Launching agent...\n"
            + "Result Root Dir:/workspace/CTP/sql/result/y2026/m7/schedule_x\n"
            + "[10:00:00] Testing /w/t/cases/1014.sql (1/1 100.00%) [NOK]\n"
            + "SQLCASE_RESULT status=nok rc=0 core=0\n";
        SqlResultParser.SqlCaseOutcome outcome = SqlResultParser.parseConsole(console);
        assert outcome != null && outcome.isFail() : "console scan finds marker";
    }

    private static void testTestRequestTypePlumbing() {
        JSONObject json = new JSONObject()
            .put("testPath", "sql/_01_object/cases/1.sql")
            .put("buildPackage", "/tmp/x.tar.gz")
            .put("testType", "sql")
            .put("sqlTcBranch", "develop")
            .put("sqlTcCommit", "abc123")
            .put("ctpSqlBaseSha", "deadbeef");
        TestRequest request = new TestRequest(json);
        assert request.isSqlTest() : "testType=sql must be recognized";
        assertEquals("develop", request.getSqlTcBranch(), "sqlTcBranch");
        assertEquals("abc123", request.getSqlTcCommit(), "sqlTcCommit");
        assertEquals("deadbeef", request.getCtpSqlBaseSha(), "ctpSqlBaseSha");

        TestRequest shell = new TestRequest(new JSONObject()
            .put("testPath", "shell/x/cases/x.sh")
            .put("buildPackage", "/tmp/x.tar.gz"));
        assert !shell.isSqlTest() : "missing testType must default to shell";
        assertEquals("shell", shell.getTestType(), "default testType");
    }

    /**
     * sql_run_case.sh against a fake ConsoleAgent that, like CTP's, writes a scratch <case>.result
     * next to the case and prints [OK] even after an exception: a case on a read-only testcase
     * tree (the Docker mount) must still run cleanly, and [OK] with a stack trace is no pass.
     */
    private static void testCaseRunScript() throws Exception {
        Path root = Files.createTempDirectory("sql_run_case_test");
        Path tc = root.resolve("tc");
        try {
            Path cases = Files.createDirectories(tc.resolve("sql/_s/cases"));
            Files.createDirectories(tc.resolve("sql/_s/answers"));
            Files.write(cases.resolve("c1.sql"), "select 1;\n".getBytes("UTF-8"));
            Files.write(tc.resolve("sql/_s/answers/c1.answer"), "1\n".getBytes("UTF-8"));
            Files.createDirectories(root.resolve("ctp/sql/lib"));
            Files.write(root.resolve("ctp/sql/lib/x.jar"), new byte[0]);
            Files.createDirectories(root.resolve("cubrid"));
            Files.createDirectories(root.resolve("tmp"));
            Path java = Files.createDirectories(root.resolve("jdk/bin")).resolve("java");
            Files.write(java, ("#!/bin/bash\n"
                + "c=\"${@: -1}\"; c=\"${c%%\\?*}\"; d=$(dirname \"$c\"); b=$(basename \"$c\" .sql)\n"
                + "echo 'Result Root Dir:/nowhere'\n"
                + "[ -r \"$d/../answers/$b.answer\" ] || printf 'java.io.FileNotFoundException\\n\\tat x.Answer.read(Unknown Source)\\n'\n"
                + "echo r 2>/dev/null > \"$d/$b.result\" || printf 'java.io.IOException: Read-only file system\\n\\tat java.io.File.createNewFile(File.java:1023)\\n'\n"
                + "[ -z \"$FAKE_EXCEPTION\" ] || printf 'java.lang.NullPointerException\\n\\tat x.Y.z(Unknown Source)\\n'\n"
                + "echo ' [OK]'\n").getBytes("UTF-8"));
            java.toFile().setExecutable(true);
            Path ws = Files.createDirectories(root.resolve("ws"));
            Files.write(ws.resolve("sql_env.sh"), ("export TESTCASE_ROOT='" + tc + "' CTP_HOME='" + root.resolve("ctp")
                + "' JAVA_HOME='" + root.resolve("jdk") + "' CUBRID='" + root.resolve("cubrid")
                + "' TMPDIR='" + root.resolve("tmp") + "' SQL_DB_NAME=db\n").getBytes("UTF-8"));
            Path script = root.resolve("sql_run_case.sh");
            Files.write(script, SqlScriptFactory.createCaseRunScript(60, ws.toString()).getBytes("UTF-8"));
            setTreeWritable(tc, false);

            String clean = runCaseScript(script, "s1", false);
            assert clean.contains("SQLCASE_RESULT status=ok ") : "read-only tree runs cleanly: " + clean;
            assert !new String(Files.readAllBytes(ws.resolve("results/s1/attempt_1/console.log")), "UTF-8")
                .contains("Exception") : "no exception on a read-only tree";
            assert !Files.exists(cases.resolve("c1.result")) : "the testcase tree is never written";
            try (Stream<Path> left = Files.list(root.resolve("tmp"))) {
                assert left.count() == 0 : "the writable shadow is removed";
            }

            String failed = runCaseScript(script, "s2", true);
            assert failed.contains("SQLCASE_RESULT status=error ") && failed.contains("reason=console_exception")
                : "[OK] with a stack trace is an error: " + failed;
            SqlResultParser.SqlCaseOutcome outcome = SqlResultParser.parseStatusLine(failed.trim());
            assert outcome != null && !outcome.isPass() : "console_exception never parses as a pass";
        } finally {
            setTreeWritable(tc, true);
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static String runCaseScript(Path script, String slot, boolean fakeException) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString(), "sql/_s/cases/c1.sql", "1", slot);
        if (fakeException) {
            pb.environment().put("FAKE_EXCEPTION", "1");
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(readAll(p.getInputStream()), "UTF-8");
        p.waitFor();
        return out;
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        for (int n; (n = in.read(chunk)) > 0; ) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static void setTreeWritable(Path tree, boolean writable) throws Exception {
        if (!Files.exists(tree)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tree)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) ? (writable ? "rwxr-xr-x" : "r-xr-xr-x") : (writable ? "rw-r--r--" : "r--r--r--")));
            }
        }
    }

    private static void assertEquals(String expected, String actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected '" + expected + "' but got '" + actual + "'");
        }
    }
}
