package com.navercorp.cubridqa.builder.workload;

/**
 * NodeStatus - Represents the current status of a worker node
 */
public enum NodeStatus {
    /**
     * Node is idle and available for work
     */
    IDLE,

    /**
     * Node is currently building CUBRID
     */
    BUILDING,

    /**
     * Node is currently running tests
     */
    TESTING
}
