package com.navercorp.cubridqa.builder.logging;

/**
 * Configuration for log management and retention policies.
 */
public class LogConfig {
    private final int maxRequestLogs;
    private final int maxTarFiles;
    private final String logRootDir;
    private final boolean enableRequestGrouping;
    
    public LogConfig(int maxRequestLogs, int maxTarFiles, String logRootDir, boolean enableRequestGrouping) {
        this.maxRequestLogs = maxRequestLogs;
        this.maxTarFiles = maxTarFiles;
        this.logRootDir = logRootDir;
        this.enableRequestGrouping = enableRequestGrouping;
    }
    
    /**
     * Default configuration with sensible defaults
     */
    public static LogConfig defaults() {
        String homeDir = System.getProperty("user.home");
        String defaultLogDir = homeDir + "/cubrid-testtools/CTP/builder_tester/log";
        return new LogConfig(5, 10, defaultLogDir, true);
    }
    
    public int getMaxRequestLogs() {
        return maxRequestLogs;
    }
    
    public int getMaxTarFiles() {
        return maxTarFiles;
    }
    
    public String getLogRootDir() {
        return logRootDir;
    }
    
    public boolean isRequestGroupingEnabled() {
        return enableRequestGrouping;
    }
    
    /**
     * Get the requests directory path
     */
    public String getRequestsDir() {
        return logRootDir + "/requests";
    }
    
    /**
     * Get the system logs directory path
     */
    public String getSystemDir() {
        return logRootDir + "/system";
    }
    
    /**
     * Get the directory path for a specific request
     */
    public String getRequestDir(String requestId) {
        return getRequestsDir() + "/" + requestId;
    }
}
