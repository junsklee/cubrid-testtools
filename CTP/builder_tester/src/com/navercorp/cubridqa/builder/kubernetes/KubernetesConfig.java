/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder.kubernetes;

import java.io.*;
import java.util.*;
import java.util.logging.*;

/**
 * KubernetesConfig - Configuration for Kubernetes integration
 * 
 * Manages all Kubernetes-related configuration including cluster access,
 * resource limits, namespace settings, and scaling parameters.
 */
public class KubernetesConfig {
    private static final Logger logger = Logger.getLogger(KubernetesConfig.class.getName());
    
    // Core settings
    private boolean enabled = false;
    private String kubeconfigPath;
    private String namespace = "cubrid-testing";
    private String context;
    
    // Resource management
    private String buildCpuRequest = "2";
    private String buildCpuLimit = "4";
    private String buildMemoryRequest = "4Gi";
    private String buildMemoryLimit = "8Gi";
    
    private String testCpuRequest = "1";
    private String testCpuLimit = "2";
    private String testMemoryRequest = "2Gi";
    private String testMemoryLimit = "4Gi";
    
    // Job configuration
    private int jobBackoffLimit = 3;
    private int jobTTLSecondsAfterFinished = 3600; // 1 hour
    private int jobActiveDeadlineSeconds = 7200; // 2 hours for builds
    private int testJobActiveDeadlineSeconds = 1800; // 30 minutes for tests
    
    // Scaling configuration
    private Map<String, String> nodeSelector = new HashMap<>();
    private Map<String, String> buildNodeSelector = new HashMap<>();
    private Map<String, String> testNodeSelector = new HashMap<>();
    private boolean enableAntiAffinity = true;
    private int maxJobsPerNode = 4;
    
    // Storage configuration
    private String buildPvcName = "cubrid-build-pvc";
    private String testPvcName = "cubrid-test-pvc";
    private String storageClass = "standard";
    
    // Service configuration
    private String builderServiceName = "cubrid-builder";
    private String testerServiceName = "cubrid-tester";
    private String serviceType = "LoadBalancer"; // or NodePort, ClusterIP
    
    // Image configuration
    private String buildImage = "cubridci/cubridci:develop";
    private String testImage = "cubridci/cubridci:test_shell";
    private String imagePullPolicy = "IfNotPresent";
    private String imagePullSecret;
    
    // Load balancing
    private String loadBalancingStrategy = "RoundRobin"; // or Random, LeastConnection
    private boolean preferLocalNode = true;
    
    public KubernetesConfig() {
        // Set default node selectors
        nodeSelector.put("cubrid-testing", "enabled");
    }
    
    public void loadFromProperties(Properties props) {
        enabled = Boolean.parseBoolean(props.getProperty("kubernetes.enabled", "false"));
        kubeconfigPath = props.getProperty("kubernetes.kubeconfig", 
            System.getProperty("user.home") + "/.kube/config");
        namespace = props.getProperty("kubernetes.namespace", namespace);
        context = props.getProperty("kubernetes.context");
        
        // Load resource limits
        buildCpuRequest = props.getProperty("kubernetes.build.cpu.request", buildCpuRequest);
        buildCpuLimit = props.getProperty("kubernetes.build.cpu.limit", buildCpuLimit);
        buildMemoryRequest = props.getProperty("kubernetes.build.memory.request", buildMemoryRequest);
        buildMemoryLimit = props.getProperty("kubernetes.build.memory.limit", buildMemoryLimit);
        
        testCpuRequest = props.getProperty("kubernetes.test.cpu.request", testCpuRequest);
        testCpuLimit = props.getProperty("kubernetes.test.cpu.limit", testCpuLimit);
        testMemoryRequest = props.getProperty("kubernetes.test.memory.request", testMemoryRequest);
        testMemoryLimit = props.getProperty("kubernetes.test.memory.limit", testMemoryLimit);
        
        // Load job configuration
        jobBackoffLimit = Integer.parseInt(props.getProperty("kubernetes.job.backoffLimit", 
            String.valueOf(jobBackoffLimit)));
        jobTTLSecondsAfterFinished = Integer.parseInt(props.getProperty("kubernetes.job.ttlSecondsAfterFinished", 
            String.valueOf(jobTTLSecondsAfterFinished)));
        jobActiveDeadlineSeconds = Integer.parseInt(props.getProperty("kubernetes.job.activeDeadlineSeconds", 
            String.valueOf(jobActiveDeadlineSeconds)));
        testJobActiveDeadlineSeconds = Integer.parseInt(props.getProperty("kubernetes.test.job.activeDeadlineSeconds", 
            String.valueOf(testJobActiveDeadlineSeconds)));
        
        // Load node selectors
        loadNodeSelectors(props);
        
        // Load storage configuration
        buildPvcName = props.getProperty("kubernetes.build.pvc", buildPvcName);
        testPvcName = props.getProperty("kubernetes.test.pvc", testPvcName);
        storageClass = props.getProperty("kubernetes.storageClass", storageClass);
        
        // Load service configuration
        builderServiceName = props.getProperty("kubernetes.builder.service", builderServiceName);
        testerServiceName = props.getProperty("kubernetes.tester.service", testerServiceName);
        serviceType = props.getProperty("kubernetes.serviceType", serviceType);
        
        // Load image configuration
        buildImage = props.getProperty("kubernetes.build.image", buildImage);
        testImage = props.getProperty("kubernetes.test.image", testImage);
        imagePullPolicy = props.getProperty("kubernetes.imagePullPolicy", imagePullPolicy);
        imagePullSecret = props.getProperty("kubernetes.imagePullSecret");
        
        // Load balancing configuration
        loadBalancingStrategy = props.getProperty("kubernetes.loadBalancing", loadBalancingStrategy);
        preferLocalNode = Boolean.parseBoolean(props.getProperty("kubernetes.preferLocalNode", "true"));
        enableAntiAffinity = Boolean.parseBoolean(props.getProperty("kubernetes.enableAntiAffinity", "true"));
        maxJobsPerNode = Integer.parseInt(props.getProperty("kubernetes.maxJobsPerNode", 
            String.valueOf(maxJobsPerNode)));
    }
    
    private void loadNodeSelectors(Properties props) {
        // Load general node selectors
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("kubernetes.nodeSelector.")) {
                String label = key.substring("kubernetes.nodeSelector.".length());
                nodeSelector.put(label, props.getProperty(key));
            } else if (key.startsWith("kubernetes.build.nodeSelector.")) {
                String label = key.substring("kubernetes.build.nodeSelector.".length());
                buildNodeSelector.put(label, props.getProperty(key));
            } else if (key.startsWith("kubernetes.test.nodeSelector.")) {
                String label = key.substring("kubernetes.test.nodeSelector.".length());
                testNodeSelector.put(label, props.getProperty(key));
            }
        }
    }
    
    // Getters
    public boolean isEnabled() { return enabled; }
    public String getKubeconfigPath() { return kubeconfigPath; }
    public String getNamespace() { return namespace; }
    public String getContext() { return context; }
    
    public String getBuildCpuRequest() { return buildCpuRequest; }
    public String getBuildCpuLimit() { return buildCpuLimit; }
    public String getBuildMemoryRequest() { return buildMemoryRequest; }
    public String getBuildMemoryLimit() { return buildMemoryLimit; }
    
    public String getTestCpuRequest() { return testCpuRequest; }
    public String getTestCpuLimit() { return testCpuLimit; }
    public String getTestMemoryRequest() { return testMemoryRequest; }
    public String getTestMemoryLimit() { return testMemoryLimit; }
    
    public int getJobBackoffLimit() { return jobBackoffLimit; }
    public int getJobTTLSecondsAfterFinished() { return jobTTLSecondsAfterFinished; }
    public int getJobActiveDeadlineSeconds() { return jobActiveDeadlineSeconds; }
    public int getTestJobActiveDeadlineSeconds() { return testJobActiveDeadlineSeconds; }
    
    public Map<String, String> getNodeSelector() { return nodeSelector; }
    public Map<String, String> getBuildNodeSelector() { 
        Map<String, String> combined = new HashMap<>(nodeSelector);
        combined.putAll(buildNodeSelector);
        return combined;
    }
    public Map<String, String> getTestNodeSelector() { 
        Map<String, String> combined = new HashMap<>(nodeSelector);
        combined.putAll(testNodeSelector);
        return combined;
    }
    
    public boolean isEnableAntiAffinity() { return enableAntiAffinity; }
    public int getMaxJobsPerNode() { return maxJobsPerNode; }
    
    public String getBuildPvcName() { return buildPvcName; }
    public String getTestPvcName() { return testPvcName; }
    public String getStorageClass() { return storageClass; }
    
    public String getBuilderServiceName() { return builderServiceName; }
    public String getTesterServiceName() { return testerServiceName; }
    public String getServiceType() { return serviceType; }
    
    public String getBuildImage() { return buildImage; }
    public String getTestImage() { return testImage; }
    public String getImagePullPolicy() { return imagePullPolicy; }
    public String getImagePullSecret() { return imagePullSecret; }
    
    public String getLoadBalancingStrategy() { return loadBalancingStrategy; }
    public boolean isPreferLocalNode() { return preferLocalNode; }
    
    public void logConfiguration() {
        logger.info("Kubernetes Configuration:");
        logger.info("  Enabled: " + enabled);
        logger.info("  Namespace: " + namespace);
        logger.info("  Build Image: " + buildImage);
        logger.info("  Test Image: " + testImage);
        logger.info("  Build Resources: CPU=" + buildCpuRequest + "/" + buildCpuLimit + 
                   ", Memory=" + buildMemoryRequest + "/" + buildMemoryLimit);
        logger.info("  Test Resources: CPU=" + testCpuRequest + "/" + testCpuLimit + 
                   ", Memory=" + testMemoryRequest + "/" + testMemoryLimit);
        logger.info("  Load Balancing: " + loadBalancingStrategy);
        logger.info("  Anti-Affinity: " + enableAntiAffinity);
        logger.info("  Max Jobs Per Node: " + maxJobsPerNode);
    }
}
