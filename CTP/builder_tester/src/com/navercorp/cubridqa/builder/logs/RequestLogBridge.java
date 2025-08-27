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
}