package com.navercorp.cubridqa.builder.scheduler;

import java.util.Objects;

/**
 * Represents a test-to-node placement decision made by the scheduler.
 *
 * <p>Returned by SchedulerService.assignNext() to tell the builder where
 * to submit a test. Includes the test instance and target node ID.</p>
 */
public class Assignment {

    private final TestInstance test;
    private final String targetNodeId;
    private final double score;  // Lower is better

    public Assignment(TestInstance test, String targetNodeId, double score) {
        this.test = Objects.requireNonNull(test, "test");
        this.targetNodeId = Objects.requireNonNull(targetNodeId, "targetNodeId");
        this.score = score;
    }

    public TestInstance getTest() {
        return test;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public double getScore() {
        return score;
    }

    /**
     * Convenience getter for test key.
     */
    public String getTestKey() {
        return test.getTestKey();
    }

    /**
     * Convenience getter for commit.
     */
    public String getCommit() {
        return test.getCommit();
    }

    /**
     * Convenience getter for baseline.
     */
    public String getBaseline() {
        return test.getBaseline();
    }

    @Override
    public String toString() {
        return "Assignment{test=" + test.getTestKey() + ", node=" + targetNodeId + ", score=" + String.format("%.3f", score) + "}";
    }
}
