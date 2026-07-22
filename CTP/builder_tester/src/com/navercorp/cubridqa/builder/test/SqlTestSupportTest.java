package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderTask;
import com.navercorp.cubridqa.builder.exec.SqlResultParser;
import com.navercorp.cubridqa.builder.tester.TestRequest;
import org.json.JSONObject;

/**
 * Unit tests for the SQL tester support: test-name derivation, status-line
 * parsing, and request-type plumbing.
 */
public class SqlTestSupportTest {

    public static void main(String[] args) throws Exception {
        testBuildSqlTestName();
        testStatusLineParsing();
        testStatusConsoleParsing();
        testTestRequestTypePlumbing();
        System.out.println("SqlTestSupportTest passed");
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

    private static void assertEquals(String expected, String actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected '" + expected + "' but got '" + actual + "'");
        }
    }
}
