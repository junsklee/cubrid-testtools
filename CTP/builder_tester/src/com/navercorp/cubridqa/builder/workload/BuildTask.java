package com.navercorp.cubridqa.builder.workload;

/**
 * BuildTask - Represents a build task to be executed
 */
public class BuildTask {
    private final String commit;
    private final String buildType;
    private final String baselineCommit;
    private String assignedNode;

    public BuildTask(String commit, String buildType, String baselineCommit) {
        this.commit = commit;
        this.buildType = buildType;
        this.baselineCommit = baselineCommit;
        this.assignedNode = null;
    }

    public String getCommit() {
        return commit;
    }

    public String getBuildType() {
        return buildType;
    }

    public String getBaselineCommit() {
        return baselineCommit;
    }

    public String getAssignedNode() {
        return assignedNode;
    }

    public void setAssignedNode(String nodeId) {
        this.assignedNode = nodeId;
    }

    @Override
    public String toString() {
        return String.format("BuildTask{commit='%s', type='%s', baseline='%s', node='%s'}",
            commit.substring(0, Math.min(7, commit.length())),
            buildType,
            baselineCommit != null ? baselineCommit.substring(0, Math.min(7, baselineCommit.length())) : "null",
            assignedNode);
    }
}
