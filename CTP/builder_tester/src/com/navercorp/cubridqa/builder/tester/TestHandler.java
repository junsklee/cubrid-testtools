package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.http.MultipartHelper;
import com.navercorp.cubridqa.builder.http.HttpUtils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestHandler implements HttpHandler {
    private final Config config;
    private final TestOrchestrator orchestrator;
    private final Logger logger;

    public TestHandler(Config config, TestOrchestrator orchestrator, Logger logger) {
        this.config = config;
        this.orchestrator = orchestrator;
        this.logger = logger;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Logger requestLogger = logger;  // Default to system logger

        logger.info("Received " + exchange.getRequestMethod() + " request");

        if (!"POST".equals(exchange.getRequestMethod())) {
            HttpUtils.sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        JSONObject responsePayload = null;
        List<Path> logFilesToSend = new ArrayList<>();
        int httpStatus = 200;

        try {
            String requestBody = HttpUtils.readRequestBody(exchange);
            JSONObject request = new JSONObject(requestBody);

            // Extract request ID if provided
            String requestId = request.optString("requestId", null);
            if (requestId != null) {
                RequestContext.setRequestId(requestId);

                // Try to get request-specific logger
                try {
                    if (config.isRequestGroupingEnabled()) {
                        requestLogger = RequestLogManager.getInstance().getRequestLogger(requestId, "tester");
                    }
                } catch (Exception e) {
                    logger.warning("Failed to create request logger: " + e.getMessage());
                }
            }

            requestLogger.info("Test request for: " + request.getString("testPath") +
                       (request.has("requestId") ? " [" + request.optString("requestId") + "]" : ""));
            requestLogger.info("Build package: " + request.getString("buildPackage"));

            // Run test with retry using request logger
            responsePayload = orchestrator.runTestWithRetry(request, requestLogger);
            
            // Extract log files to send
            if (responsePayload.has("attemptLogFiles")) {
                Object logFilesObj = responsePayload.get("attemptLogFiles");
                if (logFilesObj instanceof List) {
                    logFilesToSend = (List<Path>) logFilesObj;
                }
                responsePayload.remove("attemptLogFiles");
            }
            
            httpStatus = 200;

        } catch (Exception e) {
            // Only errors that occur before generating the result should reach here
            logger.log(Level.SEVERE, "Error processing test request (pre-response)", e);
            responsePayload = new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", e.getMessage());
            httpStatus = 500;
        } finally {
            // Nothing here yet; we still need to attempt to send the response below
        }

        // Try to send the response once. If client disconnected (broken pipe), just log and do not overwrite result.
        if (responsePayload == null) {
            responsePayload = new JSONObject()
                .put("status", TestStatus.EXECUTION_ERROR.getValue())
                .put("message", "No result generated");
            httpStatus = 500;
        }

        try {
            // Check if we should send multipart response (when we have log files)
            if (!logFilesToSend.isEmpty()) {
                // Prepare files map for multipart sending
                Map<String, Path> files = new HashMap<>();
                for (int i = 0; i < logFilesToSend.size(); i++) {
                    Path logFile = logFilesToSend.get(i);
                    String fieldName = "log_attempt_" + (i + 1);
                    files.put(fieldName, logFile);
                }
                
                // Send multipart response with JSON and log files
                MultipartHelper.sendMultipartResponse(exchange, httpStatus, responsePayload, files);
                logger.info("Sent multipart response with " + files.size() + " log files: " + responsePayload.toString());
            } else {
                // Send regular JSON response (backward compatibility or no logs)
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                HttpUtils.sendResponse(exchange, httpStatus, responsePayload.toString());
                logger.info("Sent JSON response: " + responsePayload.toString());
            }
        } catch (IOException ioe) {
            String msg = ioe.getMessage() == null ? "" : ioe.getMessage();
            if (HttpUtils.isClientAbort(ioe) || msg.contains("insufficient bytes written")) {
                // Client (likely Builder) closed connection early. Do not treat as test failure.
                logger.warning("Client disconnected before response was fully sent. Result was: " + responsePayload.toString());
            } else {
                logger.log(Level.SEVERE, "Failed to send response", ioe);
            }
        } finally {
            RequestContext.clear();
        }
    }
}