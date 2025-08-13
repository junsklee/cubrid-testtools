/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder.kubernetes;

import java.io.*;
import java.util.*;
import java.util.logging.*;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.*;
import io.fabric8.kubernetes.client.*;

/**
 * KubernetesTesterManager - Manages test execution in Kubernetes
 */
public class KubernetesTesterManager {
    private static final Logger logger = Logger.getLogger(KubernetesTesterManager.class.getName());
    
    private final KubernetesManager manager;
    private final KubernetesConfig config;
    
    public KubernetesTesterManager(KubernetesManager manager) {
        this.manager = manager;
        this.config = manager.getConfig();
    }
    
    /**
     * Submit a test job to Kubernetes
     */
    public String submitTestJob(String testName, String testPath, String buildPackage, 
                               String taskId, Map<String, String> envVars) 
                               throws IOException {
        
        if (!manager.isAvailable()) {
            throw new IOException("Kubernetes manager is not available");
        }
        
        String jobName = KubernetesUtils.generateJobName("test", testName.replace("/", "-"));
        logger.info("Submitting test job: " + jobName + " for test: " + testName);
        
        try {
            Job job = createTestJob(jobName, testName, testPath, buildPackage, taskId, envVars);
            
            KubernetesClient client = manager.getClient();
            client.batch().v1().jobs()
                .inNamespace(config.getNamespace())
                .create(job);
            
            logger.info("Test job submitted: " + jobName);
            return jobName;
            
        } catch (Exception e) {
            throw new IOException("Failed to submit test job: " + e.getMessage(), e);
        }
    }    
    private Job createTestJob(String jobName, String testName, String testPath, 
                             String buildPackage, String taskId, Map<String, String> envVars) {
        
        Map<String, String> labels = KubernetesUtils.createJobLabels("test", taskId, null);
        labels.put("test-name", testName.replace("/", "-"));
        
        // Create environment variables
        List<EnvVar> env = new ArrayList<>();
        env.add(new EnvVar("TEST_NAME", testName, null));
        env.add(new EnvVar("TEST_PATH", testPath, null));
        env.add(new EnvVar("BUILD_PACKAGE", buildPackage, null));
        env.add(new EnvVar("TASK_ID", taskId, null));
        env.add(new EnvVar("CTP_HOME", "/home/CTP", null));
        env.add(new EnvVar("init_path", "/home/CTP/shell/init_path", null));
        
        // Add custom environment variables
        if (envVars != null) {
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                env.add(new EnvVar(entry.getKey(), entry.getValue(), null));
            }
        }
        
        // Select node for job
        String selectedNode = manager.selectNodeForJob("test");
        
        // Create volume mounts
        List<VolumeMount> volumeMounts = new ArrayList<>();
        volumeMounts.add(new VolumeMountBuilder()
            .withName("test-workspace")
            .withMountPath("/workspace")
            .build());        volumeMounts.add(new VolumeMountBuilder()
            .withName("test-cases")
            .withMountPath("/home/cubrid-testcases-private-ex")
            .build());
        volumeMounts.add(new VolumeMountBuilder()
            .withName("cubrid-testtools")
            .withMountPath("/home/cubrid-testtools")
            .build());
        
        // Create volumes
        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder()
            .withName("test-workspace")
            .withNewEmptyDir()
            .endEmptyDir()
            .build());
        volumes.add(new VolumeBuilder()
            .withName("test-cases")
            .withNewPersistentVolumeClaim()
                .withClaimName(config.getTestPvcName())
            .endPersistentVolumeClaim()
            .build());
        volumes.add(new VolumeBuilder()
            .withName("cubrid-testtools")
            .withNewPersistentVolumeClaim()
                .withClaimName("cubrid-testtools-pvc")
            .endPersistentVolumeClaim()
            .build());
        
        // Build command
        List<String> command = Arrays.asList("/bin/bash", "-c");
        List<String> args = Arrays.asList(createTestScript(testName, testPath, buildPackage));        
        // Create pod spec with affinity
        PodSpec podSpec = new PodSpecBuilder()
            .withRestartPolicy("Never")
            .withNodeSelector(config.getTestNodeSelector())
            .withAffinity(KubernetesUtils.createJobAffinity(
                config.getTestNodeSelector(),
                config.isEnableAntiAffinity(),
                "test"))
            .withContainers(new ContainerBuilder()
                .withName("test")
                .withImage(config.getTestImage())
                .withImagePullPolicy(config.getImagePullPolicy())
                .withCommand(command)
                .withArgs(args)
                .withEnv(env)
                .withVolumeMounts(volumeMounts)
                .withResources(KubernetesUtils.createResourceRequirements(
                    config.getTestCpuRequest(),
                    config.getTestCpuLimit(),
                    config.getTestMemoryRequest(),
                    config.getTestMemoryLimit()))
                .build())
            .withVolumes(volumes)
            .build();
        
        if (selectedNode != null) {
            podSpec.setNodeName(selectedNode);
        }        
        // Create job spec
        return new JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(config.getNamespace())
                .withLabels(labels)
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(config.getJobBackoffLimit())
                .withActiveDeadlineSeconds((long) config.getTestJobActiveDeadlineSeconds())
                .withTtlSecondsAfterFinished(config.getJobTTLSecondsAfterFinished())
                .withNewTemplate()
                    .withNewMetadata()
                        .withLabels(labels)
                    .endMetadata()
                    .withSpec(podSpec)
                .endTemplate()
            .endSpec()
            .build();
    }
    
    private String createTestScript(String testName, String testPath, String buildPackage) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -e\n");
        script.append("cd /workspace\n");
        script.append("echo 'Extracting build package: ").append(buildPackage).append("'\n");
        script.append("tar xzf /cache/builds/").append(buildPackage).append("\n");
        script.append("export CUBRID=/workspace/CUBRID\n");
        script.append("export CUBRID_DATABASES=/workspace/databases\n");
        script.append("mkdir -p $CUBRID_DATABASES\n");
        script.append("echo 'Running test: ").append(testName).append("'\n");
        script.append("cd ").append(testPath).append("\n");
        script.append("bash ").append(testName).append("\n");
        script.append("echo 'Test completed'\n");
        return script.toString();
    }    
    /**
     * Monitor test job status
     */
    public TestResult monitorTestJob(String jobName, long timeoutSeconds) {
        if (!manager.isAvailable()) {
            return new TestResult("error", "Kubernetes manager not available", null);
        }
        
        logger.info("Monitoring test job: " + jobName);
        
        KubernetesUtils.JobResult result = KubernetesUtils.waitForJobCompletion(
            manager.getClient(),
            config.getNamespace(),
            jobName,
            timeoutSeconds
        );
        
        if (result.success) {
            // Parse test result from logs
            String status = parseTestStatus(result.logs);
            return new TestResult(status, "Test completed", result.logs);
        } else if (result.failed) {
            return new TestResult("fail", "Test execution failed", result.logs);
        } else {
            return new TestResult("error", "Test timed out or interrupted", null);
        }
    }
    
    private String parseTestStatus(String logs) {
        if (logs == null) {
            return "error";
        }
        
        if (logs.contains("PASS") || logs.contains("OK")) {
            return "pass";
        } else if (logs.contains("FAIL") || logs.contains("NOK")) {
            return "fail";
        } else {
            return "error";
        }
    }
    
    /**
     * Test result class
     */
    public static class TestResult {
        public final String status; // pass, fail, error
        public final String message;
        public final String logs;
        
        public TestResult(String status, String message, String logs) {
            this.status = status;
            this.message = message;
            this.logs = logs;
        }
    }
}
