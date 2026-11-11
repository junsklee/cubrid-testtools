package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.tester.demand.PredictedDemand;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestRequest {
    private final String testPath;
    private final String buildPackage;
    private final String requestId;
    private final String runMode;
    private final int minRuns;
    private final int maxRuns;
    private final Long timeBudgetMs;
    private final boolean keepAlive;
    private final String expectedBuildVersion;
    private final String baselineShort;
    private final String commit;
    private final String commitShort;
    private final String baseline;
    private final String testDir;
    private final String testScript;
    private final String testName;
    private final String containerName;
    private final int attemptNumber;
    private final String buildType;
    private final PredictedDemand predictedDemand;  // Resource predictions for enforcement
    private final String customShellScript;  // Custom script contents to execute instead of test
    private final String customShellScriptBase64; // The same as exact bytes (an EUC-KR case is not UTF-8 text)
    private final List<CustomAttachment> customAttachments; // Additional files for custom script mode
    private final String shellTcBranch; // Request-scoped testcase branch selection
    private final String shellTcCommit; // Exact testcase commit resolved by Builder
    private final String testType; // "shell" (default) or "sql"
    private final String sqlTcBranch; // SQL testcase branch selection
    private final String sqlTcCommit; // Exact SQL testcase commit resolved by Builder
    private final String ctpSqlBaseSha; // Exact CTP base SHA resolved by Builder (may be null)
    private final String customSqlScript; // Ad-hoc SQL case contents (custom SQL mode)
    private final String customSqlAnswer; // Expected .answer for the ad-hoc SQL case
    // A full-suite shard (sqlBatch): its top-level sql/ directories, run as one CTP SQL run
    private final List<String> sqlBatchDirs;
    private final String sqlBatchShard;
    private final int sqlBatchTimeoutMinutes;
    private final int sqlBatchMemoryLimitMb;

    public TestRequest(JSONObject json) {
        this.testPath = json.getString("testPath");
        this.buildPackage = json.getString("buildPackage");
        this.requestId = json.optString("requestId", null);
        this.runMode = json.optString("runMode", "until-pass");
        this.minRuns = Math.max(1, json.optInt("minRuns", 1));
        this.maxRuns = Math.max(this.minRuns, json.optInt("maxRuns", this.minRuns));
        this.keepAlive = json.optBoolean("keepAlive", false);
        this.expectedBuildVersion = json.optString("expectedBuildVersion", null);
        this.baselineShort = json.optString("baselineShort", null);
        this.commit = json.optString("commit", null);
        this.commitShort = json.optString("commitShort", null);
        this.baseline = json.optString("baseline", null);
        this.testDir = json.optString("testDir", null);
        this.testScript = json.optString("testScript", null);
        this.testName = json.optString("testName", null);
        this.containerName = json.optString("containerName", null);
        this.attemptNumber = json.optInt("attemptNumber", 1);
        org.json.JSONObject batch = json.optJSONObject("sqlBatch");
        if (batch != null && batch.optJSONArray("dirs") != null) {
            List<String> dirs = new java.util.ArrayList<>();
            org.json.JSONArray raw = batch.getJSONArray("dirs");
            for (int i = 0; i < raw.length(); i++) {
                String d = raw.optString(i, "").trim();
                // Top-level directories of sql/ only: they name copies and paths on the tester
                if (d.matches("sql/_[A-Za-z0-9_.-]+") && !d.contains("..")) {
                    dirs.add(d);
                }
            }
            this.sqlBatchDirs = java.util.Collections.unmodifiableList(dirs);
            this.sqlBatchShard = batch.optString("shard", "0").replaceAll("[^A-Za-z0-9_-]", "_");
            this.sqlBatchTimeoutMinutes = batch.optInt("timeoutMinutes", 180);
            this.sqlBatchMemoryLimitMb = Math.max(1024, Math.min(65536, batch.optInt("memoryLimitMb", 4096)));
        } else {
            this.sqlBatchDirs = null;
            this.sqlBatchShard = null;
            this.sqlBatchTimeoutMinutes = 180;
            this.sqlBatchMemoryLimitMb = 4096;
        }
        this.buildType = json.optString("buildType", "release");
        String scriptBase64 = json.optString("customShellScriptBase64", "").trim();
        this.customShellScriptBase64 = scriptBase64.isEmpty() ? null : scriptBase64;
        // The Builder refuses a request with both; the exact bytes would win here
        this.customShellScript = customShellScriptBase64 != null ? null : json.optString("customShellScript", null);
        this.customAttachments = withEntryScript(parseCustomAttachments(json), testScript, customShellScriptBase64);
        this.shellTcBranch = json.optString("shellTcBranch", null);
        this.shellTcCommit = json.optString("shellTcCommit", null);
        this.testType = json.optString("testType", "shell");
        this.sqlTcBranch = json.optString("sqlTcBranch", null);
        this.sqlTcCommit = json.optString("sqlTcCommit", null);
        this.ctpSqlBaseSha = json.optString("ctpSqlBaseSha", null);
        this.customSqlScript = json.optString("customSqlScript", null);
        this.customSqlAnswer = json.optString("customSqlAnswer", null);

        if (json.has("timeBudgetMs")) {
            long tb = json.optLong("timeBudgetMs", -1);
            this.timeBudgetMs = tb >= 1 ? tb : null;
        } else {
            this.timeBudgetMs = null;
        }

        // Parse predicted demand for resource enforcement
        this.predictedDemand = PredictedDemand.fromRequest(json, null);
    }

    private List<CustomAttachment> parseCustomAttachments(JSONObject json) {
        try {
            if (json == null || !json.has("customAttachments")) {
                return Collections.emptyList();
            }
            Object raw = json.get("customAttachments");
            if (!(raw instanceof JSONArray)) {
                return Collections.emptyList();
            }
            JSONArray arr = (JSONArray) raw;
            if (arr.length() == 0) {
                return Collections.emptyList();
            }
            List<CustomAttachment> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.get(i);
                if (item instanceof JSONObject) {
                    CustomAttachment ca = CustomAttachment.fromJson((JSONObject) item);
                    if (ca != null && ca.isValid()) {
                        out.add(ca);
                    }
                }
            }
            return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    /**
     * customShellScriptBase64 becomes the last attachment, over the entry script: executors write
     * attachments byte for byte (and before a text custom script), so the case gets the bytes as sent.
     */
    private static List<CustomAttachment> withEntryScript(List<CustomAttachment> attachments, String testScript,
                                                          String scriptBase64) {
        if (scriptBase64 == null || testScript == null || testScript.trim().isEmpty()) {
            return attachments;
        }
        List<CustomAttachment> out = new ArrayList<>(attachments);
        out.add(new CustomAttachment(testScript.trim(), scriptBase64));
        return Collections.unmodifiableList(out);
    }
    
    public String getTestPath() { return testPath; }
    public String getBuildPackage() { return buildPackage; }
    public String getRequestId() { return requestId; }
    public String getRunMode() { return runMode; }
    public int getMinRuns() { return minRuns; }
    public int getMaxRuns() { return maxRuns; }
    public Long getTimeBudgetMs() { return timeBudgetMs; }
    public boolean isKeepAlive() { return keepAlive; }
    public String getExpectedBuildVersion() { return expectedBuildVersion; }
    public String getBaselineShort() { return baselineShort; }
    public String getCommit() { return commit; }
    public String getCommitShort() { return commitShort; }
    public String getBaseline() { return baseline; }
    public String getTestDir() { return testDir; }
    public String getTestScript() { return testScript; }
    public String getTestName() { return testName; }
    public String getContainerName() { return containerName; }
    public int getAttemptNumber() { return attemptNumber; }
    public String getBuildType() { return buildType; }
    public PredictedDemand getPredictedDemand() { return predictedDemand; }
    public String getCustomShellScript() { return customShellScript; }
    public boolean hasCustomShellScript() {
        return (customShellScript != null && !customShellScript.isEmpty()) || customShellScriptBase64 != null;
    }
    /** The entry script as base64 (also the last of getCustomAttachments), or null. */
    public String getCustomShellScriptBase64() { return customShellScriptBase64; }
    public List<CustomAttachment> getCustomAttachments() { return customAttachments; }
    public boolean hasCustomAttachments() { return customAttachments != null && !customAttachments.isEmpty(); }
    public String getShellTcBranch() { return shellTcBranch; }
    public String getShellTcCommit() { return shellTcCommit; }
    public String getTestType() { return testType == null || testType.trim().isEmpty() ? "shell" : testType.trim().toLowerCase(); }
    public boolean isSqlTest() { return "sql".equals(getTestType()); }
    public String getSqlTcBranch() { return sqlTcBranch; }
    public String getSqlTcCommit() { return sqlTcCommit; }
    public String getCtpSqlBaseSha() { return ctpSqlBaseSha; }
    public String getCustomSqlScript() { return customSqlScript; }
    public String getCustomSqlAnswer() { return customSqlAnswer; }
    public boolean hasCustomSqlScript() { return customSqlScript != null && !customSqlScript.isEmpty(); }
    /** A shard of the full SQL suite: its directories run as one CTP SQL run, not one case. */
    public boolean isSqlBatch() { return isSqlTest() && sqlBatchDirs != null && !sqlBatchDirs.isEmpty(); }
    public List<String> getSqlBatchDirs() { return sqlBatchDirs; }
    public String getSqlBatchShard() { return sqlBatchShard; }
    public int getSqlBatchTimeoutMinutes() { return sqlBatchTimeoutMinutes; }
    /** A shard container's hard memory limit (1 GB to 64 GB, default 4 GB). */
    public int getSqlBatchMemoryLimitMb() { return sqlBatchMemoryLimitMb; }
}
