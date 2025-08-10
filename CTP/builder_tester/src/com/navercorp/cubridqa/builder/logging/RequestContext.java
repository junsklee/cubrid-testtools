package com.navercorp.cubridqa.builder.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thread-local context for tracking request IDs throughout execution.
 * Each request gets a unique ID that follows it through all components.
 */
public class RequestContext {
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    
    /**
     * Generate a new unique request ID with format: req_TIMESTAMP_RANDOM
     * Example: req_20250811_143022_a7f3
     */
    public static String generateRequestId() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String random = String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
        return String.format("req_%s_%s", timestamp, random);
    }
    
    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }
    
    public static String getRequestId() {
        return REQUEST_ID.get();
    }
    
    public static void clear() {
        REQUEST_ID.remove();
    }
    
    /**
     * Execute a task with a specific request context
     */
    public static void withRequestId(String requestId, Runnable task) {
        String previousId = getRequestId();
        try {
            setRequestId(requestId);
            task.run();
        } finally {
            if (previousId != null) {
                setRequestId(previousId);
            } else {
                clear();
            }
        }
    }
}
