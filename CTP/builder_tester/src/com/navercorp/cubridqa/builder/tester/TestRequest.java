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
    private final List<CustomAttachment> customAttachments; // Additional files for custom script mode

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
        this.customShellScript = json.optString("customShellScript", null);
        this.customAttachments = parseCustomAttachments(json);

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
    public boolean hasCustomShellScript() { return customShellScript != null && !customShellScript.isEmpty(); }
    public List<CustomAttachment> getCustomAttachments() { return customAttachments; }
    public boolean hasCustomAttachments() { return customAttachments != null && !customAttachments.isEmpty(); }
}
