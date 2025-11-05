package com.navercorp.cubridqa.builder.workload;

/**
 * TestTask - Represents a test task to be executed
 */
public class TestTask {
    private final String commit;
    private final String testPath;
    private String assignedNode;
    private boolean requiresPackageTransfer;

    public TestTask(String commit, String testPath) {
        this.commit = commit;
        this.testPath = testPath;
        this.assignedNode = null;
        this.requiresPackageTransfer = false;
    }

    public String getCommit() {
        return commit;
    }

    public String getTestPath() {
        return testPath;
    }

    public String getAssignedNode() {
        return assignedNode;
    }

    public void setAssignedNode(String nodeId) {
        this.assignedNode = nodeId;
    }

    public boolean requiresPackageTransfer() {
        return requiresPackageTransfer;
    }

    public void setRequiresPackageTransfer(boolean requires) {
        this.requiresPackageTransfer = requires;
    }

    @Override
    public String toString() {
        return String.format("TestTask{commit='%s', test='%s', node='%s', transfer=%s}",
            commit.substring(0, Math.min(7, commit.length())),
            testPath,
            assignedNode,
            requiresPackageTransfer);
    }
}
