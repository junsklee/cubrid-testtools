package com.navercorp.cubridqa.builder.logs;

import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.tester.TestResult;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.logging.Logger;

public class RequestLogBridge {
    
    public static Logger createRequestLogger(String requestId, String serviceType) {
        try {
            if (RequestLogManager.getInstance().isRequestGroupingEnabled()) {
                return RequestLogManager.getInstance().getRequestLogger(requestId, serviceType);
            }
        } catch (Exception e) {
            Logger.getLogger(RequestLogBridge.class.getName()).warning("Failed to create request logger: " + e.getMessage());
        }
        return Logger.getLogger("fallback");
    }
    
    /**
     * Helper method to add log file path to response JSON for multipart sending
     * Also stores minimal metadata about the log for the JSON response
     */
    public static void addLogToResult(TestResult.Builder resultBuilder, Path logFilePath, String logFileName) {
        if (logFilePath == null || !Files.exists(logFilePath)) {
            return;
        }
        
        // Add the log file path for multipart sending
        resultBuilder.addAttemptLogFile(logFilePath);
    }
    
    /**
     * Helper method to add log content to response JSON (DEPRECATED - for backward compatibility only)
     * Truncates large logs to avoid overwhelming the network
     */
    public static void addLogContentToResult(JSONObject response, String logContent, String logFileName) {
        if (logContent == null || logContent.isEmpty()) {
            return;
        }
        
        // Limit log size to 500KB to avoid network issues
        final int MAX_LOG_SIZE = 500 * 1024;
        String truncatedLog = logContent;
        boolean wasTruncated = false;
        
        if (logContent.length() > MAX_LOG_SIZE) {
            // Keep first and last parts of the log
            int keepSize = MAX_LOG_SIZE / 2;
            truncatedLog = logContent.substring(0, keepSize) + 
                          "\n\n... [LOG TRUNCATED - Total size: " + logContent.length() + " bytes] ...\n\n" +
                          logContent.substring(logContent.length() - keepSize);
            wasTruncated = true;
        }
        
        response.put("logContent", truncatedLog);
        response.put("logTruncated", wasTruncated);
        if (logFileName != null) {
            response.put("logFileName", logFileName);
        }
    }
}