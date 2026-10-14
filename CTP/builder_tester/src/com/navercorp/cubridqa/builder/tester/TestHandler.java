package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.logging.RequestIds;
import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.http.HttpUtils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestHandler implements HttpHandler {
    private final Config config;
    private final TestOrchestrator orchestrator;
    private final Logger logger;
    private final HttpResponseWriter responseWriter;

    public TestHandler(Config config, TestOrchestrator orchestrator, Logger logger) {
        this.config = config;
        this.orchestrator = orchestrator;
        this.logger = logger;
        this.responseWriter = new HttpResponseWriter();
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
        int httpStatus = 200;

        try {
            String requestBody = HttpUtils.readRequestBody(exchange);
            JSONObject request = new JSONObject(requestBody);

            // Extract request ID if provided
            String requestId = request.optString("requestId", null);
            if (requestId != null && requestId.isEmpty()) {
                requestId = null;
            }
            if (requestId != null) {
                RequestIds.require(requestId);
            }

            if (CancelledRequests.isCancelled(requestId)) {
                requestLogger.warning("Request cancelled before execution: " + requestId);
                responsePayload = new JSONObject()
                    .put("status", "cancelled")
                    .put("message", "Request cancelled")
                    .put("requestId", requestId);
                httpStatus = 200;
            } else {
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

            // Run test with retry using request logger (handles queueing and resource reservation)
            // This blocks until resources are available (or timeout)
            responsePayload = orchestrator.runTestWithRetry(request, requestLogger);
            
            // Check for rejection (timeout waiting for resources)
            if ("rejected".equals(responsePayload.optString("status"))) {
                httpStatus = 409; // Conflict/Busy
                requestLogger.warning("Test rejected: " + responsePayload.optString("error"));
            }
            }
            
            // Logs and artifacts are not sent with the result: the Builder fetches each file named
            // in attemptLogMetadata through GET /log/<name>?requestId=<id>.

        } catch (JSONException | IllegalArgumentException e) {
            logger.log(Level.WARNING, "Bad test request", e);
            responsePayload = new JSONObject()
                .put("status", "bad_request")
                .put("message", e.getMessage());
            httpStatus = 400;
        } catch (Exception e) {
            // Only errors that occur before generating the result should reach here
            logger.log(Level.SEVERE, "Error processing test request (pre-response)", e);
            responsePayload = new JSONObject()
                .put("status", "execution_error")
                .put("message", e.getMessage());
            httpStatus = 500;
        }

        // Try to send the response once. If client disconnected (broken pipe), just log and do not overwrite result.
        if (responsePayload == null) {
            responsePayload = new JSONObject()
                .put("status", "execution_error")
                .put("message", "No result generated");
            httpStatus = 500;
        }

        try {
            responseWriter.sendJson(exchange, httpStatus, responsePayload);
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
