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
        return Integer.parseInt(properties.getProperty(BUILD_CACHE_SIZE, "20"));
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
        return Integer.parseInt(properties.getProperty(MAX_TAR_FILES, "10"));
    }
    
    public boolean isRequestGroupingEnabled() {
        return Boolean.parseBoolean(properties.getProperty(ENABLE_REQUEST_GROUPING, "true"));
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
