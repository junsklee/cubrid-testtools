package com.navercorp.cubridqa.builder.workload;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NodeInfo - Represents information about a worker node with circuit breaker capabilities
 */
public class NodeInfo {
    private final String nodeId;
    private final boolean isLocal;
    private volatile NodeStatus status;
    private volatile String currentBuildCommit;
    private volatile int activeTestCount;
    private final Set<String> localPackages;

    // Circuit breaker fields
    private volatile int consecutiveFailures;
    private volatile Instant lastFailureTime;
    private volatile Instant lastSuccessTime;
    private final int failureThreshold;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public NodeInfo(String nodeId, boolean isLocal) {
        this(nodeId, isLocal, 3, 10000L, 300000L); // Default: 3 failures, 10s base backoff, 5min max backoff
    }

    public NodeInfo(String nodeId, boolean isLocal, int failureThreshold, long baseBackoffMs, long maxBackoffMs) {
        this.nodeId = nodeId;
        this.isLocal = isLocal;
        this.status = NodeStatus.IDLE;
        this.currentBuildCommit = null;
        this.activeTestCount = 0;
        this.localPackages = ConcurrentHashMap.newKeySet();

        // Circuit breaker initialization
        this.consecutiveFailures = 0;
        this.lastFailureTime = null;
        this.lastSuccessTime = Instant.now();
        this.failureThreshold = failureThreshold;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    public String getNodeId() {
        return nodeId;
    }

    public boolean isLocal() {
        return isLocal;
    }

    public synchronized NodeStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(NodeStatus status) {
        this.status = status;
    }

    public synchronized String getCurrentBuildCommit() {
        return currentBuildCommit;
    }

    public synchronized void setCurrentBuildCommit(String commit) {
        this.currentBuildCommit = commit;
    }

    public synchronized int getActiveTestCount() {
        return activeTestCount;
    }

    public synchronized void incrementTestCount() {
        this.activeTestCount++;
        if (this.activeTestCount > 0 && this.status == NodeStatus.IDLE) {
            this.status = NodeStatus.TESTING;
        }
    }

    public synchronized void decrementTestCount() {
        this.activeTestCount--;
        if (this.activeTestCount <= 0) {
            this.activeTestCount = 0;
            if (this.status == NodeStatus.TESTING) {
                this.status = NodeStatus.IDLE;
            }
        }
    }

    public void addLocalPackage(String commit) {
        localPackages.add(commit);
    }

    public boolean hasLocalPackage(String commit) {
        return localPackages.contains(commit);
    }

    public Set<String> getLocalPackages() {
        return new HashSet<>(localPackages);
    }

    public synchronized boolean isIdle() {
        return status == NodeStatus.IDLE;
    }

    public synchronized boolean isBusy() {
        return status != NodeStatus.IDLE;
    }

    /**
     * Records a failure for this node (e.g., connection timeout, HTTP error).
     * Increments consecutive failure count and updates last failure timestamp.
     */
    public synchronized void recordFailure() {
        consecutiveFailures++;
        lastFailureTime = Instant.now();
    }

    /**
     * Records a successful operation for this node.
     * Resets consecutive failure count and updates last success timestamp.
     */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        lastSuccessTime = Instant.now();
    }

    /**
     * Returns the current consecutive failure count.
     */
    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Checks if this node is healthy (failures below threshold).
     */
    public synchronized boolean isHealthy() {
        return consecutiveFailures < failureThreshold;
    }

    /**
     * Checks if this node should be skipped due to circuit breaker backoff.
     * Uses exponential backoff: backoffTime = min(baseBackoff * 2^failures, maxBackoff)
     */
    public synchronized boolean shouldSkip() {
        if (consecutiveFailures == 0) {
            return false; // Healthy, never skip
        }

        if (lastFailureTime == null) {
            return false; // No failure recorded, don't skip
        }

        // Calculate exponential backoff time
        long backoffMs = Math.min(
            baseBackoffMs * (1L << Math.min(consecutiveFailures - 1, 10)), // Cap exponent to prevent overflow
            maxBackoffMs
        );

        Instant backoffUntil = lastFailureTime.plusMillis(backoffMs);
        boolean inBackoff = Instant.now().isBefore(backoffUntil);

        return inBackoff;
    }

    /**
     * Gets the remaining backoff time in milliseconds, or 0 if not in backoff.
     */
    public synchronized long getRemainingBackoffMs() {
        if (consecutiveFailures == 0 || lastFailureTime == null) {
            return 0;
        }

        long backoffMs = Math.min(
            baseBackoffMs * (1L << Math.min(consecutiveFailures - 1, 10)),
            maxBackoffMs
        );

        Instant backoffUntil = lastFailureTime.plusMillis(backoffMs);
        long remaining = backoffUntil.toEpochMilli() - Instant.now().toEpochMilli();

        return Math.max(0, remaining);
    }

    @Override
    public String toString() {
        return String.format("NodeInfo{nodeId='%s', local=%s, status=%s, buildCommit='%s', activeTests=%d, packages=%d, failures=%d, inBackoff=%s}",
            nodeId, isLocal, status, currentBuildCommit, activeTestCount, localPackages.size(),
            consecutiveFailures, shouldSkip() ? "yes (" + getRemainingBackoffMs() + "ms)" : "no");
    }
}
