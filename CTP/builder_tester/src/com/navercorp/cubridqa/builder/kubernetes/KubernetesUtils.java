/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder.kubernetes;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.*;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.*;

/**
 * KubernetesUtils - Utility functions for Kubernetes operations
 */
public class KubernetesUtils {
    private static final Logger logger = Logger.getLogger(KubernetesUtils.class.getName());
    
    /**
     * Create a Kubernetes client with the given configuration
     */
    public static KubernetesClient createClient(KubernetesConfig config) throws IOException {
        ConfigBuilder builder = new ConfigBuilder();
        
        if (config.getKubeconfigPath() != null && Files.exists(Paths.get(config.getKubeconfigPath()))) {
            Config kubeConfig = Config.fromKubeconfig(
                new String(Files.readAllBytes(Paths.get(config.getKubeconfigPath())))
            );
            builder = new ConfigBuilder(kubeConfig);
        }
        
        if (config.getContext() != null) {
            builder.withCurrentContext(config.getContext());
        }
        
        if (config.getNamespace() != null) {
            builder.withNamespace(config.getNamespace());
        }
        
        return new KubernetesClientBuilder()
            .withConfig(builder.build())
            .build();
    }
    
    /**
     * Generate a unique job name
     */
    public static String generateJobName(String prefix, String identifier) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String shortId = identifier.length() > 7 ? identifier.substring(0, 7) : identifier;
        return String.format("%s-%s-%s", prefix, shortId, timestamp);
    }
    
    /**
     * Create resource requirements
     */
    public static ResourceRequirements createResourceRequirements(
            String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit) {
        return new ResourceRequirementsBuilder()
            .addToRequests("cpu", new Quantity(cpuRequest))
            .addToRequests("memory", new Quantity(memoryRequest))
            .addToLimits("cpu", new Quantity(cpuLimit))
            .addToLimits("memory", new Quantity(memoryLimit))
            .build();
    }
    
    /**
     * Create common labels for jobs
     */
    public static Map<String, String> createJobLabels(String type, String taskId, String commitHash) {
        Map<String, String> labels = new HashMap<>();
        labels.put("app", "cubrid-testing");
        labels.put("type", type); // "build" or "test"
        labels.put("task-id", taskId);
        if (commitHash != null) {
            labels.put("commit", commitHash.length() > 7 ? commitHash.substring(0, 7) : commitHash);
        }
        return labels;
    }
    
    /**
     * Create node affinity for job distribution
     */
    public static Affinity createJobAffinity(Map<String, String> nodeSelector, boolean enableAntiAffinity, String jobType) {
        AffinityBuilder affinityBuilder = new AffinityBuilder();
        
        // Node affinity based on node selectors
        if (!nodeSelector.isEmpty()) {
            NodeSelectorRequirement[] requirements = nodeSelector.entrySet().stream()
                .map(entry -> new NodeSelectorRequirementBuilder()
                    .withKey(entry.getKey())
                    .withOperator("In")
                    .withValues(entry.getValue())
                    .build())
                .toArray(NodeSelectorRequirement[]::new);
            
            affinityBuilder.withNewNodeAffinity()
                .withNewRequiredDuringSchedulingIgnoredDuringExecution()
                    .addNewNodeSelectorTerm()
                        .withMatchExpressions(requirements)
                    .endNodeSelectorTerm()
                .endRequiredDuringSchedulingIgnoredDuringExecution()
            .endNodeAffinity();
        }
        
        // Pod anti-affinity to spread jobs across nodes
        if (enableAntiAffinity) {
            affinityBuilder.withNewPodAntiAffinity()
                .addNewPreferredDuringSchedulingIgnoredDuringExecution()
                    .withWeight(100)
                    .withNewPodAffinityTerm()
                        .withNewLabelSelector()
                            .addToMatchLabels("type", jobType)
                        .endLabelSelector()
                        .withTopologyKey("kubernetes.io/hostname")
                    .endPodAffinityTerm()
                .endPreferredDuringSchedulingIgnoredDuringExecution()
            .endPodAntiAffinity();
        }
        
        return affinityBuilder.build();
    }
    
    /**
     * Check if a job is complete
     */
    public static boolean isJobComplete(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        JobStatus status = job.getStatus();
        return status.getSucceeded() != null && status.getSucceeded() > 0;
    }
    
    /**
     * Check if a job has failed
     */
    public static boolean isJobFailed(Job job) {
        if (job == null || job.getStatus() == null) {
            return false;
        }
        JobStatus status = job.getStatus();
        
        // Check if job has exceeded backoff limit
        if (status.getFailed() != null && job.getSpec() != null && 
            job.getSpec().getBackoffLimit() != null) {
            return status.getFailed() > job.getSpec().getBackoffLimit();
        }
        
        // Check for any failure condition
        if (status.getConditions() != null) {
            for (JobCondition condition : status.getConditions()) {
                if ("Failed".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Get pod logs from a job
     */
    public static String getJobLogs(KubernetesClient client, String namespace, String jobName) {
        try {
            PodList pods = client.pods()
                .inNamespace(namespace)
                .withLabel("job-name", jobName)
                .list();
            
            if (pods.getItems().isEmpty()) {
                return "No pods found for job: " + jobName;
            }
            
            Pod pod = pods.getItems().get(0);
            return client.pods()
                .inNamespace(namespace)
                .withName(pod.getMetadata().getName())
                .getLog();
        } catch (Exception e) {
            logger.warning("Failed to get logs for job " + jobName + ": " + e.getMessage());
            return "Failed to retrieve logs: " + e.getMessage();
        }
    }
    
    /**
     * Clean up completed jobs
     */
    public static void cleanupJob(KubernetesClient client, String namespace, String jobName) {
        try {
            client.batch().v1().jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .delete();
            logger.info("Cleaned up job: " + jobName);
        } catch (Exception e) {
            logger.warning("Failed to cleanup job " + jobName + ": " + e.getMessage());
        }
    }
    
    /**
     * Wait for job completion with timeout
     */
    public static JobResult waitForJobCompletion(KubernetesClient client, String namespace, 
                                                 String jobName, long timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000;
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                Job job = client.batch().v1().jobs()
                    .inNamespace(namespace)
                    .withName(jobName)
                    .get();
                
                if (isJobComplete(job)) {
                    String logs = getJobLogs(client, namespace, jobName);
                    return new JobResult(true, false, logs);
                }
                
                if (isJobFailed(job)) {
                    String logs = getJobLogs(client, namespace, jobName);
                    return new JobResult(false, true, logs);
                }
                
                Thread.sleep(5000); // Check every 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new JobResult(false, false, "Job monitoring interrupted");
            } catch (Exception e) {
                logger.warning("Error checking job status: " + e.getMessage());
            }
        }
        
        return new JobResult(false, false, "Job timed out after " + timeoutSeconds + " seconds");
    }
    
    /**
     * Result class for job execution
     */
    public static class JobResult {
        public final boolean success;
        public final boolean failed;
        public final String logs;
        
        public JobResult(boolean success, boolean failed, String logs) {
            this.success = success;
            this.failed = failed;
            this.logs = logs;
        }
    }
}
