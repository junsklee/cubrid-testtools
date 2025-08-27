package com.navercorp.cubridqa.builder.tester;

import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

public class TestResult {
    private final String testName;
    private final String status;
    private final String message;
    private final String executionTime;
    private final String executionMode;
    private final boolean flaky;
    private final int attempts;
    private final List<Path> attemptLogFiles;
    private final JSONArray attemptLogMetadata;
    private final String logPath;
    private final String commit;
    private final String commitShort;
    private final Integer exitCode;
    private final Long timestamp;
    private final String containerName;
    private final String execCommand;
    private final String workspace;
    
    private TestResult(Builder builder) {
        this.testName = builder.testName;
        this.status = builder.status;
        this.message = builder.message;
        this.executionTime = builder.executionTime;
        this.executionMode = builder.executionMode;
        this.flaky = builder.flaky;
        this.attempts = builder.attempts;
        this.attemptLogFiles = new ArrayList<>(builder.attemptLogFiles);
        this.attemptLogMetadata = builder.attemptLogMetadata;
        this.logPath = builder.logPath;
        this.commit = builder.commit;
        this.commitShort = builder.commitShort;
        this.exitCode = builder.exitCode;
        this.timestamp = builder.timestamp;
        this.containerName = builder.containerName;
        this.execCommand = builder.execCommand;
        this.workspace = builder.workspace;
    }
    
    public String getTestName() { return testName; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public String getExecutionTime() { return executionTime; }
    public String getExecutionMode() { return executionMode; }
    public boolean isFlaky() { return flaky; }
    public int getAttempts() { return attempts; }
    public List<Path> getAttemptLogFiles() { return attemptLogFiles; }
    public JSONArray getAttemptLogMetadata() { return attemptLogMetadata; }
    public String getLogPath() { return logPath; }
    public String getCommit() { return commit; }
    public String getCommitShort() { return commitShort; }
    public Integer getExitCode() { return exitCode; }
    public Long getTimestamp() { return timestamp; }
    public String getContainerName() { return containerName; }
    public String getExecCommand() { return execCommand; }
    public String getWorkspace() { return workspace; }
    
    public JSONObject toJson() {
        JSONObject json = new JSONObject()
            .put("status", status)
            .put("attempts", attempts);
            
        if (message != null) {
            json.put("message", message);
        }
        if (executionTime != null) {
            json.put("executionTime", executionTime);
        }
        if (flaky) {
            json.put("flaky", flaky);
        }
        if (attemptLogMetadata != null && attemptLogMetadata.length() > 0) {
            json.put("attemptLogMetadata", attemptLogMetadata);
        }
        if (logPath != null) {
            json.put("logPath", logPath);
        }
        
        return json;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String testName;
        private String status;
        private String message;
        private String executionTime;
        private String executionMode;
        private boolean flaky = false;
        private int attempts = 1;
        private List<Path> attemptLogFiles = new ArrayList<>();
        private JSONArray attemptLogMetadata = new JSONArray();
        private String logPath;
        private String commit;
        private String commitShort;
        private Integer exitCode;
        private Long timestamp;
        private String containerName;
        private String execCommand;
        private String workspace;
        
        public Builder testName(String testName) {
            this.testName = testName;
            return this;
        }
        
        public Builder status(TestStatus status) {
            this.status = status.getValue();
            return this;
        }
        
        public Builder status(String status) {
            this.status = status;
            return this;
        }
        
        public Builder message(String message) {
            this.message = message;
            return this;
        }
        
        public Builder executionTime(String executionTime) {
            this.executionTime = executionTime;
            return this;
        }
        
        public Builder executionMode(String executionMode) {
            this.executionMode = executionMode;
            return this;
        }
        
        public Builder flaky(boolean flaky) {
            this.flaky = flaky;
            return this;
        }
        
        public Builder attempts(int attempts) {
            this.attempts = attempts;
            return this;
        }
        
        public Builder addAttemptLogFile(Path logFile) {
            this.attemptLogFiles.add(logFile);
            return this;
        }
        
        public Builder attemptLogMetadata(JSONArray metadata) {
            this.attemptLogMetadata = metadata;
            return this;
        }
        
        public Builder logPath(String logPath) {
            this.logPath = logPath;
            return this;
        }
        
        public Builder commit(String commit) {
            this.commit = commit;
            return this;
        }
        
        public Builder commitShort(String commitShort) {
            this.commitShort = commitShort;
            return this;
        }
        
        public Builder exitCode(Integer exitCode) {
            this.exitCode = exitCode;
            return this;
        }
        
        public Builder timestamp(Long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder containerName(String containerName) {
            this.containerName = containerName;
            return this;
        }
        
        public Builder execCommand(String execCommand) {
            this.execCommand = execCommand;
            return this;
        }
        
        public Builder workspace(String workspace) {
            this.workspace = workspace;
            return this;
        }
        
        public TestResult build() {
            return new TestResult(this);
        }
    }
}