package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderTask;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * A result's logPath (the report's log link) is the final attempt's run log. Artifacts follow the
 * attempt logs in attemptLogMetadata, and taking the last entry pointed every SQL result at its
 * case source (.sql) and failed VM results at their case-file bundle.
 */
public class ResultLogPathTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== ResultLogPathTest Suite ===\n");
        Method pick = BuilderTask.class.getDeclaredMethod("finalAttemptLogName", JSONArray.class);
        pick.setAccessible(true);

        JSONArray sql = new JSONArray()
            .put(new JSONObject().put("logFileName", "sql_0c310e2_custom_sql_case.log").put("attempt", 1).put("status", "fail"))
            .put(artifact("answer_diff", "sql_diff_0c310e2_custom_sql_case.diff", 1))
            .put(artifact("expected_answer", "sql_expected_0c310e2_custom_sql_case.answer", 1))
            .put(artifact("case_source", "sql_case_0c310e2_custom_sql_case.sql", 1));
        check("sql_0c310e2_custom_sql_case.log".equals(pick.invoke(null, sql)), "SQL result -> run log, not the case source");

        JSONArray retried = new JSONArray()
            .put(new JSONObject().put("logFileName", "vm_a.log").put("attempt", 1).put("status", "fail"))
            .put(artifact("case_files", "vmcase_a.txt", 1))
            .put(new JSONObject().put("logFileName", "vm_a.2.log").put("attempt", 2).put("status", "pass"));
        check("vm_a.2.log".equals(pick.invoke(null, retried)), "the final attempt's log");

        JSONArray failedVm = new JSONArray()
            .put(new JSONObject().put("logFileName", "vm_b.log").put("attempt", 1).put("status", "fail"))
            .put(artifact("case_files", "vmcase_b.txt", 1));
        check("vm_b.log".equals(pick.invoke(null, failedVm)), "failed VM result -> run log, not the case-file bundle");

        JSONArray onlyArtifacts = new JSONArray().put(artifact("case_source", "x.sql", 1));
        check("x.sql".equals(pick.invoke(null, onlyArtifacts)), "no run log at all -> the last entry, as before");
        check(pick.invoke(null, new JSONArray()) == null && pick.invoke(null, (Object) null) == null, "nothing -> null");

        System.out.println("\n=== All ResultLogPathTest tests passed! ===");
    }

    private static JSONObject artifact(String type, String name, int attempt) {
        return new JSONObject().put("artifactType", type).put("executionEnv", "fresh_container")
            .put("logFileName", name).put("attempt", attempt);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
        System.out.println("  ok: " + what);
    }
}
