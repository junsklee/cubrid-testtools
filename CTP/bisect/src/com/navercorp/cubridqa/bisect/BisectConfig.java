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
        
        // Validate directories exist
        validateDirectory(CUBRID_SRC_DIR);
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
