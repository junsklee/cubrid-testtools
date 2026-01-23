package com.navercorp.cubridqa.builder.tester;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CancelledRequests {
    private static final long DEFAULT_TTL_MS = 6L * 60 * 60 * 1000; // 6 hours
    private static final ConcurrentHashMap<String, Long> cancelled = new ConcurrentHashMap<>();

    private CancelledRequests() {
    }

    public static void cancel(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return;
        }
        cancelled.put(requestId, System.currentTimeMillis());
        cleanupExpired();
    }

    public static boolean isCancelled(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return false;
        }
        Long ts = cancelled.get(requestId);
        if (ts == null) {
            return false;
        }
        if (System.currentTimeMillis() - ts > DEFAULT_TTL_MS) {
            cancelled.remove(requestId);
            return false;
        }
        return true;
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = cancelled.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > DEFAULT_TTL_MS) {
                it.remove();
            }
        }
    }
}
