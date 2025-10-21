package com.navercorp.cubridqa.builder.tester;

import org.json.JSONObject;

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
        this.buildType = json.optString("buildType", "debug");

        if (json.has("timeBudgetMs")) {
            long tb = json.optLong("timeBudgetMs", -1);
            this.timeBudgetMs = tb >= 1 ? tb : null;
        } else {
            this.timeBudgetMs = null;
        }
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
}