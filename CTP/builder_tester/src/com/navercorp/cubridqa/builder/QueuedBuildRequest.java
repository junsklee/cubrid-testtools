/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import org.json.JSONObject;

/**
 * QueuedBuildRequest - Represents a build request waiting in the queue
 * 
 * This class encapsulates all information needed to process a queued build request,
 * including the request ID, request payload, and timestamp for queue position tracking.
 */
public class QueuedBuildRequest {
    private final String requestId;
    private final JSONObject request;
    private final long queuedAt;
    
    public QueuedBuildRequest(String requestId, JSONObject request) {
        this.requestId = requestId;
        this.request = request;
        this.queuedAt = System.currentTimeMillis();
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public JSONObject getRequest() {
        return request;
    }
    
    public long getQueuedAt() {
        return queuedAt;
    }
    
    public long getWaitTimeMs() {
        return System.currentTimeMillis() - queuedAt;
    }
    
    @Override
    public String toString() {
        return String.format("QueuedBuildRequest{requestId=%s, queuedAt=%d, waitTime=%dms}", 
            requestId, queuedAt, getWaitTimeMs());
    }
}
