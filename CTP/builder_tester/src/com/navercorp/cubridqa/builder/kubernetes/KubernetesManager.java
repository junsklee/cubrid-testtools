/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder.kubernetes;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.*;

/**
 * KubernetesManager - Main orchestration class for Kubernetes operations
 */
public class KubernetesManager {
    private static final Logger logger = Logger.getLogger(KubernetesManager.class.getName());
    
    private final KubernetesConfig config;
    private KubernetesClient client;
    private final Map<String, String> activeJobs;
    private final ScheduledExecutorService cleanupExecutor;
    private boolean initialized = false;
    
    public KubernetesManager(KubernetesConfig config) {
        this.config = config;
        this.activeJobs = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    }
    
    /**
     * Initialize the Kubernetes manager
     */
    public void initialize() throws IOException {
        if (!config.isEnabled()) {
            logger.info("Kubernetes is disabled in configuration");
            return;
        }
        
        try {
            client = KubernetesUtils.createClient(config);
            
            // Test connection
            client.namespaces().withName(config.getNamespace()).get();
            
            // Ensure namespace exists
            ensureNamespace();
            
            // Start cleanup task for completed jobs
            startCleanupTask();
            
            initialized = true;
            config.logConfiguration();
            logger.info("Kubernetes manager initialized successfully");
        } catch (Exception e) {
            throw new IOException("Failed to initialize Kubernetes manager: " + e.getMessage(), e);
        }
    }
    
    private void ensureNamespace() {
        String ns = config.getNamespace();
        Namespace namespace = client.namespaces().withName(ns).get();
        
        if (namespace == null) {
            logger.info("Creating namespace: " + ns);
            namespace = new NamespaceBuilder()
                .withNewMetadata()
                    .withName(ns)
                    .addToLabels("app", "cubrid-testing")
                .endMetadata()
                .build();
            client.namespaces().create(namespace);
        }
    }
    
    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupCompletedJobs();
            } catch (Exception e) {
                logger.warning("Error during job cleanup: " + e.getMessage());
            }
        }, 1, 5, TimeUnit.MINUTES);
    }
    
    private void cleanupCompletedJobs() {
        if (!initialized || client == null) {
            return;
        }
        
        try {
            JobList jobs = client.batch().v1().jobs()
                .inNamespace(config.getNamespace())
                .withLabel("app", "cubrid-testing")
                .list();
            
            for (Job job : jobs.getItems()) {
                String jobName = job.getMetadata().getName();
                if (KubernetesUtils.isJobComplete(job) || KubernetesUtils.isJobFailed(job)) {
                    // Check if job is old enough to clean up
                    String creationTimestamp = job.getMetadata().getCreationTimestamp();
                    if (isOldEnough(creationTimestamp, config.getJobTTLSecondsAfterFinished())) {
                        KubernetesUtils.cleanupJob(client, config.getNamespace(), jobName);
                        activeJobs.remove(jobName);
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to cleanup jobs: " + e.getMessage());
        }
    }
    
    private boolean isOldEnough(String timestamp, int ttlSeconds) {
        try {
            long creationTime = javax.xml.bind.DatatypeConverter.parseDateTime(timestamp)
                .getTimeInMillis();
            long age = System.currentTimeMillis() - creationTime;
            return age > (ttlSeconds * 1000L);
        } catch (Exception e) {
            return false;
        }
    }    
    /**
     * Get available nodes for workload distribution
     */
    public List<Node> getAvailableNodes() {
        if (!initialized || client == null) {
            return Collections.emptyList();
        }
        
        try {
            NodeList nodes = client.nodes().list();
            List<Node> availableNodes = new ArrayList<>();
            
            for (Node node : nodes.getItems()) {
                if (isNodeReady(node) && matchesNodeSelector(node)) {
                    availableNodes.add(node);
                }
            }
            
            return availableNodes;
        } catch (Exception e) {
            logger.warning("Failed to get available nodes: " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    private boolean isNodeReady(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) {
            return false;
        }
        
        for (NodeCondition condition : node.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType()) && "True".equals(condition.getStatus())) {
                return true;
            }
        }
        return false;
    }
    
    private boolean matchesNodeSelector(Node node) {
        if (config.getNodeSelector().isEmpty()) {
            return true;
        }
        
        Map<String, String> nodeLabels = node.getMetadata().getLabels();
        if (nodeLabels == null) {
            return false;
        }
        
        for (Map.Entry<String, String> selector : config.getNodeSelector().entrySet()) {
            if (!selector.getValue().equals(nodeLabels.get(selector.getKey()))) {
                return false;
            }
        }
        return true;
    }    
    /**
     * Get current workload distribution across nodes
     */
    public Map<String, Integer> getNodeWorkloadDistribution() {
        Map<String, Integer> distribution = new HashMap<>();
        
        if (!initialized || client == null) {
            return distribution;
        }
        
        try {
            PodList pods = client.pods()
                .inNamespace(config.getNamespace())
                .withLabel("app", "cubrid-testing")
                .list();
            
            for (Pod pod : pods.getItems()) {
                String nodeName = pod.getSpec().getNodeName();
                if (nodeName != null) {
                    distribution.merge(nodeName, 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to get workload distribution: " + e.getMessage());
        }
        
        return distribution;
    }
    
    /**
     * Select best node for new job based on load balancing strategy
     */
    public String selectNodeForJob(String jobType) {
        List<Node> availableNodes = getAvailableNodes();
        if (availableNodes.isEmpty()) {
            return null;
        }
        
        Map<String, Integer> workload = getNodeWorkloadDistribution();
        
        switch (config.getLoadBalancingStrategy()) {
            case "Random":
                return selectRandomNode(availableNodes);
            case "LeastConnection":
                return selectLeastLoadedNode(availableNodes, workload);
            case "RoundRobin":
            default:
                return selectRoundRobinNode(availableNodes, workload);
        }
    }    
    private String selectRandomNode(List<Node> nodes) {
        Random random = new Random();
        Node selected = nodes.get(random.nextInt(nodes.size()));
        return selected.getMetadata().getName();
    }
    
    private String selectLeastLoadedNode(List<Node> nodes, Map<String, Integer> workload) {
        String selectedNode = null;
        int minLoad = Integer.MAX_VALUE;
        
        for (Node node : nodes) {
            String nodeName = node.getMetadata().getName();
            int load = workload.getOrDefault(nodeName, 0);
            
            if (load < minLoad && load < config.getMaxJobsPerNode()) {
                minLoad = load;
                selectedNode = nodeName;
            }
        }
        
        return selectedNode != null ? selectedNode : selectRandomNode(nodes);
    }
    
    private String selectRoundRobinNode(List<Node> nodes, Map<String, Integer> workload) {
        // Find node with least jobs that hasn't exceeded limit
        return selectLeastLoadedNode(nodes, workload);
    }
    
    /**
     * Check if Kubernetes is available and initialized
     */
    public boolean isAvailable() {
        return initialized && client != null;
    }
    
    /**
     * Get Kubernetes client
     */
    public KubernetesClient getClient() {
        return client;
    }
    
    /**
     * Get configuration
     */
    public KubernetesConfig getConfig() {
        return config;
    }
    
    /**
     * Shutdown the manager
     */
    public void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
            }
        }
        
        if (client != null) {
            client.close();
        }
        
        initialized = false;
        logger.info("Kubernetes manager shut down");
    }
}
