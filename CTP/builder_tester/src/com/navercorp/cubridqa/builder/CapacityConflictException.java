package com.navercorp.cubridqa.builder;

/**
 * Exception thrown when a tester node returns HTTP 409 Conflict,
 * indicating it cannot accept a test due to capacity constraints.
 *
 * <p>This triggers node cooldown and test re-offering in the smart
 * scheduling system.</p>
 */
public class CapacityConflictException extends Exception {
    private final String nodeId;
    private final String testPath;

    public CapacityConflictException(String nodeId, String testPath, String message) {
        super(String.format("Node %s rejected test %s: %s", nodeId, testPath, message));
        this.nodeId = nodeId;
        this.testPath = testPath;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getTestPath() {
        return testPath;
    }
}
