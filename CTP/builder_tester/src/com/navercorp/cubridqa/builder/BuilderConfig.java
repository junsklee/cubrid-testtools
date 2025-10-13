/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.util.*;

/**
 * BuilderConfig - Configuration for builder/tester services
 */
public class BuilderConfig {
    private final Properties properties;
    
    // Configuration keys
    private static final String LISTEN_PORT = "listen_port";
    private static final String CUBRID_SRC_DIR = "cubrid_src_dir";
    private static final String SHELL_TC_DIR = "shell_tc_dir";
    private static final String SHELL_TC_BRANCH = "shell_tc_branch";
    private static final String SHELL_TC_PREFERRED_REMOTE = "shell_tc_preferred_remote";
    private static final String BUILD_ARG = "build_arg";
    private static final String BUILD_DIR = "build_dir";
    private static final String WORK_DIR = "work_dir";
    private static final String TESTER_PORT = "tester_port";
    private static final String MAX_CONCURRENT_BUILDS = "max_concurrent_builds";
    private static final String MAX_CONCURRENT_TESTS = "max_concurrent_tests"; // Only valid in tester.conf
    private static final String USE_DOCKER = "use_docker";
    private static final String USE_PREBUILT_DOCKER_IMAGES = "use_prebuilt_docker_images";
    private static final String DOCKER_BUILD_IMAGE = "docker_build_image";
    private static final String DOCKER_TEST_IMAGE = "docker_test_image";
    private static final String BUILD_CACHE_SIZE = "build_cache_size";
    private static final String MAX_REQUEST_LOGS = "max_request_logs";
    private static final String MAX_TAR_FILES = "max_tar_files";
    private static final String ENABLE_REQUEST_GROUPING = "enable_request_grouping";
    private static final String RETRY_COUNT = "retry_count"; // DEPRECATED v1: number of retries/repeats (kept for migration)
    private static final String RUN_MODE = "run_mode"; // Builder: test execution mode (until-pass, until-fail, fixed-runs)
    // v2 unified run semantics
    private static final String MIN_RUNS = "min_runs";
    private static final String MAX_RUNS = "max_runs";
    private static final String TIME_BUDGET_MS = "time_budget_ms";
    private static final String TEST_READ_TIMEOUT_MINUTES = "test_read_timeout_minutes"; // Tester: HTTP read timeout for /test
    private static final String LOG_FETCH_CONNECT_TIMEOUT_SECONDS = "log_fetch_connect_timeout_seconds";
    private static final String LOG_FETCH_READ_TIMEOUT_SECONDS = "log_fetch_read_timeout_seconds";
    private static final String LOG_FILE_VERIFICATION_TIMEOUT_SECONDS = "log_file_verification_timeout_seconds";
    private static final String OPTIMIZED_DOCKER_ENABLED = "optimized_docker_enabled"; // Enable Docker image caching
    private static final String CCACHE_ENABLED = "ccache_enabled";
    private static final String CCACHE_DIR = "ccache_dir";
    private static final String CCACHE_MAX_SIZE = "ccache_max_size";
    private static final String CCACHE_COMPILERCHECK = "ccache_compilercheck";
    private static final String CCACHE_HARDLINK = "ccache_hardlink";
    private static final String CCACHE_READONLY_DIRECT = "ccache_readonly_direct";
    private static final String CCACHE_STATS = "ccache_stats";
    private static final String CCACHE_NAMESPACE = "ccache_namespace";
    private static final String CCACHE_SLOPPINESS = "ccache_sloppiness";
    private static final String PARALLEL_JOBS = "parallel_jobs";
    
    public BuilderConfig(String configFile) throws IOException {
        this.properties = new Properties();
        loadConfiguration(configFile);
        validateConfiguration();
    }
    
    private void loadConfiguration(String configFile) throws IOException {
        File file = new File(configFile);
        if (!file.exists()) {
            throw new FileNotFoundException("Configuration file not found: " + configFile);
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            properties.load(fis);
        }
        
        // Expand environment variables in property values
        expandEnvironmentVariables();
    }
    
    private void expandEnvironmentVariables() {
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            String expandedValue = expandEnvironmentVariables(value);
            properties.setProperty(key, expandedValue);
        }
    }
    
    private String expandEnvironmentVariables(String value) {
        if (value == null) {
            return null;
        }
        
        String result = value;
        
        // Expand tilde (~) to home directory
        if (result.startsWith("~/")) {
            String homeDir = System.getProperty("user.home");
            result = result.replace("~/", homeDir + "/");
        }
        
        // Expand environment variables for $VAR format
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            result = result.replace("$" + env.getKey(), env.getValue());
        }
        
        return result;
    }
    
    private void validateConfiguration() throws IllegalArgumentException {
        // Check required properties
        String[] required = {
            CUBRID_SRC_DIR, SHELL_TC_DIR
        };
        
        for (String key : required) {
            if (!properties.containsKey(key)) {
                throw new IllegalArgumentException("Missing required configuration: " + key);
            }
        }
        
        // Validate directories exist (CUBRID_SRC_DIR will be created/cloned automatically)
        validateDirectory(SHELL_TC_DIR);
    }
    
    private void validateDirectory(String key) {
        String path = properties.getProperty(key);
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException(
                "Directory does not exist: " + key + " = " + path);
        }
    }
    
    // Getters with defaults
    
    public int getListenPort() {
        return Integer.parseInt(properties.getProperty(LISTEN_PORT, "8089"));
    }
    
    public String getCubridSrcDir() {
        return properties.getProperty(CUBRID_SRC_DIR);
    }
    
    public String getShellTcDir() {
        return properties.getProperty(SHELL_TC_DIR);
    }

    public String getShellTcBranch() {
        return properties.getProperty(SHELL_TC_BRANCH, "develop");
    }

    public String getShellTcPreferredRemote() {
        return properties.getProperty(SHELL_TC_PREFERRED_REMOTE, "upstream");
    }
    
    public String getBuildArg() {
        return properties.getProperty(BUILD_ARG, "-g ninja -m debug build");
    }
    
    public String getBuildDir() {
        return properties.getProperty(BUILD_DIR, "build_x86_64_debug");
    }
    
    public String getWorkDir() {
        return properties.getProperty(WORK_DIR, "/tmp/builder_work");
    }
    
    public int getTesterPort() {
        return Integer.parseInt(properties.getProperty(TESTER_PORT, "8090"));
    }
    
    public int getMaxConcurrentBuilds() {
        return Integer.parseInt(properties.getProperty(MAX_CONCURRENT_BUILDS, "4"));
    }

    public int getMaxConcurrentTests() {
        // Note: Only tester.conf should define this. If absent (e.g., when using builder.conf), this value is unused.
        return Integer.parseInt(properties.getProperty(MAX_CONCURRENT_TESTS, "4"));
    }
    
    public boolean useDocker() {
        return Boolean.parseBoolean(properties.getProperty(USE_DOCKER, "true"));
    }

    public boolean usePrebuiltDockerImages() {
        return Boolean.parseBoolean(properties.getProperty(USE_PREBUILT_DOCKER_IMAGES, "true"));
    }
    
    public boolean useDockerForTester() {
        return Boolean.parseBoolean(properties.getProperty("use_docker_tester", "true"));
    }
    
    public String getDockerBuildImage() {
        return properties.getProperty(DOCKER_BUILD_IMAGE, "cubridci/cubridci:develop");
    }
    
    public String getDockerTestImage() {
        return properties.getProperty(DOCKER_TEST_IMAGE, "cubridci/cubridci:test_shell");
    }
    
    public int getBuildCacheSize() {
        return Integer.parseInt(properties.getProperty(BUILD_CACHE_SIZE, "10"));
    }
    
    public boolean getKeepFailedContainers() {
        return Boolean.parseBoolean(properties.getProperty("keep_failed_containers", "true"));
    }

    public int getBuildTimeoutMinutes() {
        return Integer.parseInt(properties.getProperty("build_timeout_minutes", "180"));
    }
    
    public String getDockerHostRoot() {
        return properties.getProperty("docker_host_root", System.getProperty("user.home") + "/docker-work");
    }
    
    public int getMaxRequestLogs() {
        return Integer.parseInt(properties.getProperty(MAX_REQUEST_LOGS, "5"));
    }
    
    public int getMaxTarFiles() {
        return Integer.parseInt(properties.getProperty(MAX_TAR_FILES, "5"));
    }
    
    public boolean isRequestGroupingEnabled() {
        return Boolean.parseBoolean(properties.getProperty(ENABLE_REQUEST_GROUPING, "true"));
    }
    
    /**
     * Check if optimized Docker execution with pre-built images is enabled.
     * Default is true when Docker is enabled.
     */
    public boolean isOptimizedDockerEnabled() {
        return Boolean.parseBoolean(properties.getProperty(OPTIMIZED_DOCKER_ENABLED, "true"));
    }
    
    // ---- V2 unified config with migration from v1 (retry_count) ----
    public String getRunMode() {
        String mode = properties.getProperty(RUN_MODE, "until-pass").toLowerCase();
        if (!mode.equals("until-pass") && !mode.equals("until-fail") && !mode.equals("fixed-runs")) {
            System.err.println("Invalid run_mode '" + mode + "' in builder.conf. Using default 'until-pass'");
            return "until-pass";
        }
        return mode;
    }

    public int getMinRuns() {
        String mode = getRunMode();
        Integer configured = parsePositiveInt(properties.getProperty(MIN_RUNS, null));
        if (configured != null) {
            return Math.max(1, configured);
        }
        // Migrate from v1 retry_count
        int retry = getRetryCountForMigration();
        if ("fixed-runs".equals(mode)) {
            return Math.max(1, retry);
        }
        return 1; // until-pass / until-fail default lower bound
    }

    public int getMaxRuns() {
        String mode = getRunMode();
        Integer configured = parsePositiveInt(properties.getProperty(MAX_RUNS, null));
        if (configured != null) {
            return Math.max(1, configured);
        }
        // Migrate from v1 retry_count
        int retry = getRetryCountForMigration();
        if ("until-pass".equals(mode)) {
            return 1 + Math.max(0, retry);
        } else if ("until-fail".equals(mode)) {
            int mr = Math.max(1, retry);
            if (retry == 0) {
                System.err.println("[config] WARNING: retry_count=0 no longer means unlimited; capped at 1. Use time_budget_ms or a large max_runs.");
            }
            return mr;
        } else { // fixed-runs
            int mr = Math.max(1, retry);
            if (retry == 0) {
                System.err.println("[config] WARNING: fixed-runs requires ≥1 run; upgraded to 1.");
            }
            return mr;
        }
    }

    public Long getTimeBudgetMs() {
        String v = properties.getProperty(TIME_BUDGET_MS, null);
        if (v == null || v.trim().isEmpty() || v.trim().equalsIgnoreCase("null")) return null;
        try {
            long val = Long.parseLong(v.trim());
            return val >= 1 ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parsePositiveInt(String v) {
        if (v == null) return null;
        try {
            int i = Integer.parseInt(v.trim());
            return i >= 1 ? i : 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int getRetryCountForMigration() {
        try {
            return Math.max(0, Integer.parseInt(properties.getProperty(RETRY_COUNT, "0").trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Test execution mode that determines how tests are run.
     * - until-pass: Run up to retry_count attempts or until first success (default)
     * - until-fail: Run repeatedly until first failure (reproduce mode)
     * - fixed-runs: Run exactly retry_count times regardless of pass/fail
     * Configured in builder.conf; default is "until-pass" when not specified.
     */
    // Removed retry_count feature usage; callers must use min/max runs.

    /**
     * Tester-side preferred HTTP read timeout, in minutes, for Builder -> Tester /test calls.
     * Default is 60 minutes when not specified in tester.conf.
     */
    public int getTestReadTimeoutMinutes() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(TEST_READ_TIMEOUT_MINUTES, "60"));
        } catch (NumberFormatException e) {
            value = 60;
        }
        return Math.max(1, value);
    }
    
    /**
     * Get the HTTP connect timeout for fetching logs from remote testers (in seconds).
     * Default is 10 seconds.
     */
    public int getLogFetchConnectTimeoutSeconds() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(LOG_FETCH_CONNECT_TIMEOUT_SECONDS, "10"));
        } catch (NumberFormatException e) {
            value = 10;
        }
        return Math.max(1, value);
    }
    
    /**
     * Get the HTTP read timeout for fetching logs from remote testers (in seconds).
     * Default is 120 seconds (2 minutes) to handle large log files.
     */
    public int getLogFetchReadTimeoutSeconds() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(LOG_FETCH_READ_TIMEOUT_SECONDS, "120"));
        } catch (NumberFormatException e) {
            value = 120;
        }
        return Math.max(1, value);
    }
    
    /**
     * Get the timeout for verifying log file availability after write (in seconds).
     * This prevents timing issues where files are written but not yet accessible via HTTP.
     * Default is 5 seconds.
     */
    public int getLogFileVerificationTimeoutSeconds() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(LOG_FILE_VERIFICATION_TIMEOUT_SECONDS, "5"));
        } catch (NumberFormatException e) {
            value = 5;
        }
        return Math.max(1, value);
    }
    
    // Ccache configuration methods
    
    public boolean isCcacheEnabled() {
        return Boolean.parseBoolean(properties.getProperty(CCACHE_ENABLED, "true"));
    }
    
    public String getCcacheDir() {
        String dir = properties.getProperty(CCACHE_DIR, System.getProperty("user.home") + "/ccache");
        return expandEnvironmentVariables(dir);
    }
    
    public String getCcacheMaxSize() {
        return properties.getProperty(CCACHE_MAX_SIZE, "15G");
    }
    
    public String getCcacheCompilerCheck() {
        return properties.getProperty(CCACHE_COMPILERCHECK, "content");
    }
    
    public boolean getCcacheHardlink() {
        return Boolean.parseBoolean(properties.getProperty(CCACHE_HARDLINK, "true"));
    }
    
    public boolean getCcacheReadonlyDirect() {
        return Boolean.parseBoolean(properties.getProperty(CCACHE_READONLY_DIRECT, "false"));
    }
    
    public boolean getCcacheStatsEnabled() {
        return Boolean.parseBoolean(properties.getProperty(CCACHE_STATS, "true"));
    }
    
    public String getCcacheNamespace() {
        return properties.getProperty(CCACHE_NAMESPACE, "").trim();
    }
    
    public String getCcacheSloppiness() {
        return properties.getProperty(CCACHE_SLOPPINESS, "").trim();
    }
    
    public int getParallelJobs() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(PARALLEL_JOBS, "0"));
        } catch (NumberFormatException e) {
            value = 0;
        }
        // 0 means auto-detect from CPU cores
        if (value == 0) {
            value = Runtime.getRuntime().availableProcessors();
        }
        return Math.max(1, value);
    }
    
    @Override
    public String toString() {
        return "BuilderConfig{" +
               "listenPort=" + getListenPort() +
               ", cubridSrcDir='" + getCubridSrcDir() + '\'' +
               ", shellTcDir='" + getShellTcDir() + '\'' +
               ", buildArg='" + getBuildArg() + '\'' +
               ", buildDir='" + getBuildDir() + '\'' +
               ", workDir='" + getWorkDir() + '\'' +
               ", testerPort=" + getTesterPort() +
               ", maxConcurrentBuilds=" + getMaxConcurrentBuilds() +
               ", maxConcurrentTests=" + getMaxConcurrentTests() +
               ", useDocker=" + useDocker() +
               ", buildCacheSize=" + getBuildCacheSize() +
               '}';
    }
}
