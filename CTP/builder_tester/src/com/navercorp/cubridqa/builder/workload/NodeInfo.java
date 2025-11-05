package com.navercorp.cubridqa.builder.workload;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NodeInfo - Represents information about a worker node
 */
public class NodeInfo {
    private final String nodeId;
    private final boolean isLocal;
    private volatile NodeStatus status;
    private volatile String currentBuildCommit;
    private volatile int activeTestCount;
    private final Set<String> localPackages;

    public NodeInfo(String nodeId, boolean isLocal) {
        this.nodeId = nodeId;
        this.isLocal = isLocal;
        this.status = NodeStatus.IDLE;
        this.currentBuildCommit = null;
        this.activeTestCount = 0;
        this.localPackages = ConcurrentHashMap.newKeySet();
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

    @Override
    public String toString() {
        return String.format("NodeInfo{nodeId='%s', local=%s, status=%s, buildCommit='%s', activeTests=%d, packages=%d}",
            nodeId, isLocal, status, currentBuildCommit, activeTestCount, localPackages.size());
    }
}
