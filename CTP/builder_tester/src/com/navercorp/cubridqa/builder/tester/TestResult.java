package com.navercorp.cubridqa.builder.tester;

import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

public class TestResult {
    private final String status;
    private final String message;
    private final String executionTime;
    private final boolean flaky;
    private final int attempts;
    private final List<Path> attemptLogFiles;
    private final JSONArray attemptLogMetadata;
    private final String logPath;
    
    private TestResult(Builder builder) {
        this.status = builder.status;
        this.message = builder.message;
        this.executionTime = builder.executionTime;
        this.flaky = builder.flaky;
        this.attempts = builder.attempts;
        this.attemptLogFiles = new ArrayList<>(builder.attemptLogFiles);
        this.attemptLogMetadata = builder.attemptLogMetadata;
        this.logPath = builder.logPath;
    }
    
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public String getExecutionTime() { return executionTime; }
    public boolean isFlaky() { return flaky; }
    public int getAttempts() { return attempts; }
    public List<Path> getAttemptLogFiles() { return attemptLogFiles; }
    public JSONArray getAttemptLogMetadata() { return attemptLogMetadata; }
    public String getLogPath() { return logPath; }
    
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
    
    public static class Builder {
        private String status;
        private String message;
        private String executionTime;
        private boolean flaky = false;
        private int attempts = 1;
        private List<Path> attemptLogFiles = new ArrayList<>();
        private JSONArray attemptLogMetadata = new JSONArray();
        private String logPath;
        
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
        
        public TestResult build() {
            return new TestResult(this);
        }
    }
}