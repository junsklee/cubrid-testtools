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
 * KubernetesBuildManager - Manages CUBRID builds in Kubernetes
 */
public class KubernetesBuildManager {
    private static final Logger logger = Logger.getLogger(KubernetesBuildManager.class.getName());
    
    private final KubernetesManager manager;
    private final KubernetesConfig config;
    
    public KubernetesBuildManager(KubernetesManager manager) {
        this.manager = manager;
        this.config = manager.getConfig();
    }
    
    /**
     * Submit a build job to Kubernetes
     */
    public String submitBuildJob(String commitHash, String buildType, String taskId, 
                                 String baselineCommit, Map<String, String> envVars) 
                                 throws IOException {
        
        if (!manager.isAvailable()) {
            throw new IOException("Kubernetes manager is not available");
        }
        
        String jobName = KubernetesUtils.generateJobName("build", commitHash);
        logger.info("Submitting build job: " + jobName + " for commit: " + commitHash);
        
        try {
            Job job = createBuildJob(jobName, commitHash, buildType, taskId, baselineCommit, envVars);
            
            KubernetesClient client = manager.getClient();
            client.batch().v1().jobs()
                .inNamespace(config.getNamespace())
                .create(job);
            
            logger.info("Build job submitted: " + jobName);
            return jobName;
            
        } catch (Exception e) {
            throw new IOException("Failed to submit build job: " + e.getMessage(), e);
        }
    }    
    private Job createBuildJob(String jobName, String commitHash, String buildType, 
                               String taskId, String baselineCommit, Map<String, String> envVars) {
        
        Map<String, String> labels = KubernetesUtils.createJobLabels("build", taskId, commitHash);
        
        // Create environment variables
        List<EnvVar> env = new ArrayList<>();
        env.add(new EnvVar("COMMIT_HASH", commitHash, null));
        env.add(new EnvVar("BUILD_TYPE", buildType, null));
        env.add(new EnvVar("BASELINE_COMMIT", baselineCommit, null));
        env.add(new EnvVar("TASK_ID", taskId, null));
        
        // Add custom environment variables
        if (envVars != null) {
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                env.add(new EnvVar(entry.getKey(), entry.getValue(), null));
            }
        }
        
        // Add GitHub token from secret if configured
        if (config.getImagePullSecret() != null) {
            env.add(new EnvVarBuilder()
                .withName("GITHUB_TOKEN")
                .withNewValueFrom()
                    .withNewSecretKeyRef()
                        .withName("github-token")
                        .withKey("token")
                    .endSecretKeyRef()
                .endValueFrom()
                .build());
        }        
        // Select node for job
        String selectedNode = manager.selectNodeForJob("build");
        
        // Create volume mounts
        List<VolumeMount> volumeMounts = new ArrayList<>();
        volumeMounts.add(new VolumeMountBuilder()
            .withName("build-workspace")
            .withMountPath("/workspace")
            .build());
        volumeMounts.add(new VolumeMountBuilder()
            .withName("build-cache")
            .withMountPath("/cache")
            .build());
        
        // Create volumes
        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder()
            .withName("build-workspace")
            .withNewEmptyDir()
            .endEmptyDir()
            .build());
        volumes.add(new VolumeBuilder()
            .withName("build-cache")
            .withNewPersistentVolumeClaim()
                .withClaimName(config.getBuildPvcName())
            .endPersistentVolumeClaim()
            .build());
        
        // Build command
        List<String> command = Arrays.asList("/bin/bash", "-c");
        List<String> args = Arrays.asList(createBuildScript(commitHash, buildType, baselineCommit));        
        // Create pod spec
        PodSpec podSpec = new PodSpecBuilder()
            .withRestartPolicy("Never")
            .withNodeSelector(config.getBuildNodeSelector())
            .withAffinity(KubernetesUtils.createJobAffinity(
                config.getBuildNodeSelector(), 
                config.isEnableAntiAffinity(), 
                "build"))
            .withContainers(new ContainerBuilder()
                .withName("build")
                .withImage(config.getBuildImage())
                .withImagePullPolicy(config.getImagePullPolicy())
                .withCommand(command)
                .withArgs(args)
                .withEnv(env)
                .withVolumeMounts(volumeMounts)
                .withResources(KubernetesUtils.createResourceRequirements(
                    config.getBuildCpuRequest(),
                    config.getBuildCpuLimit(),
                    config.getBuildMemoryRequest(),
                    config.getBuildMemoryLimit()))
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
                .withActiveDeadlineSeconds((long) config.getJobActiveDeadlineSeconds())
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
    private String createBuildScript(String commitHash, String buildType, String baselineCommit) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -e\n");
        script.append("cd /workspace\n");
        script.append("echo 'Starting build for commit: ").append(commitHash).append("'\n");
        script.append("git clone https://github.com/CUBRID/cubrid.git\n");
        script.append("cd cubrid\n");
        script.append("git checkout ").append(baselineCommit).append("\n");
        script.append("git cherry-pick -m 1 ").append(commitHash).append("\n");
        script.append("git submodule update --init --recursive\n");
        script.append("./build.sh -g ninja -m ").append(buildType).append(" build\n");
        script.append("tar czf /workspace/cubrid-").append(commitHash).append(".tar.gz build\n");
        script.append("echo 'Build completed successfully'\n");
        return script.toString();
    }
    
    /**
     * Monitor build job status
     */
    public BuildResult monitorBuildJob(String jobName, long timeoutSeconds) {
        if (!manager.isAvailable()) {
            return new BuildResult(false, "Kubernetes manager not available", null);
        }
        
        logger.info("Monitoring build job: " + jobName);
        
        KubernetesUtils.JobResult result = KubernetesUtils.waitForJobCompletion(
            manager.getClient(), 
            config.getNamespace(), 
            jobName, 
            timeoutSeconds
        );
        
        if (result.success) {
            // Get build artifact location
            String artifactPath = "/cache/builds/" + jobName + ".tar.gz";
            return new BuildResult(true, "Build completed successfully", artifactPath);
        } else if (result.failed) {
            return new BuildResult(false, "Build failed: " + result.logs, null);
        } else {
            return new BuildResult(false, "Build timed out or interrupted", null);
        }
    }
    
    /**
     * Build result class
     */
    public static class BuildResult {
        public final boolean success;
        public final String message;
        public final String artifactPath;
        
        public BuildResult(boolean success, String message, String artifactPath) {
            this.success = success;
            this.message = message;
            this.artifactPath = artifactPath;
        }
    }
}
