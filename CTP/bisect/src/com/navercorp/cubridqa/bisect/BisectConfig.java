/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.util.*;

/**
 * BisectConfig - Configuration for bisect services
 */
public class BisectConfig {
    private final Properties properties;
    
    // Configuration keys
    private static final String LISTEN_PORT = "listen_port";
    private static final String CUBRID_SRC_DIR = "cubrid_src_dir";
    private static final String SHELL_TC_DIR = "shell_tc_dir";
    private static final String BUILD_ARG = "build_arg";
    private static final String BUILD_DIR = "build_dir";
    private static final String WORK_DIR = "work_dir";
    private static final String CONSUMER_PORT = "consumer_port";
    private static final String MAX_CONCURRENT_BISECTS = "max_concurrent_bisects";
    private static final String USE_DOCKER = "use_docker";
    private static final String DOCKER_BUILD_IMAGE = "docker_build_image";
    private static final String DOCKER_TEST_IMAGE = "docker_test_image";
    private static final String STANDALONE_MODE = "standalone_mode";
    private static final String DOCKER_STANDALONE_IMAGE = "docker_standalone_image";
    
    public BisectConfig(String configFile) throws IOException {
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
    
    public String getBuildArg() {
        return properties.getProperty(BUILD_ARG, "-g ninja -m debug build");
    }
    
    public String getBuildDir() {
        return properties.getProperty(BUILD_DIR, "build_x86_64_debug");
    }
    
    public String getWorkDir() {
        return properties.getProperty(WORK_DIR, "/tmp/bisect_work");
    }
    
    public int getConsumerPort() {
        return Integer.parseInt(properties.getProperty(CONSUMER_PORT, "8090"));
    }
    
    public int getMaxConcurrentBisects() {
        return Integer.parseInt(properties.getProperty(MAX_CONCURRENT_BISECTS, "4"));
    }
    
    public boolean useDocker() {
        return Boolean.parseBoolean(properties.getProperty(USE_DOCKER, "true"));
    }
    
    public boolean useDockerForConsumer() {
        return Boolean.parseBoolean(properties.getProperty("use_docker_consumer", "true"));
    }
    
    public String getDockerBuildImage() {
        return properties.getProperty(DOCKER_BUILD_IMAGE, "cubrid-bisect-builder:latest");
    }
    
    public String getDockerTestImage() {
        return properties.getProperty(DOCKER_TEST_IMAGE, "cubrid-bisect-tester:latest");
    }
    
    public boolean isStandaloneMode() {
        return Boolean.parseBoolean(properties.getProperty(STANDALONE_MODE, "false"));
    }
    
    public String getDockerStandaloneImage() {
        return properties.getProperty(DOCKER_STANDALONE_IMAGE, "cubrid-bisect-standalone:latest");
    }
    
    @Override
    public String toString() {
        return "BisectConfig{" +
               "listenPort=" + getListenPort() +
               ", cubridSrcDir='" + getCubridSrcDir() + '\'' +
               ", shellTcDir='" + getShellTcDir() + '\'' +
               ", buildArg='" + getBuildArg() + '\'' +
               ", buildDir='" + getBuildDir() + '\'' +
               ", workDir='" + getWorkDir() + '\'' +
               ", consumerPort=" + getConsumerPort() +
               ", maxConcurrentBisects=" + getMaxConcurrentBisects() +
               '}';
    }
}
