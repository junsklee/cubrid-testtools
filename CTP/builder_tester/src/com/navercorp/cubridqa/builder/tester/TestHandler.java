package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.MultipartHelper;
import com.navercorp.cubridqa.builder.tester.HttpResponseWriter;
import com.navercorp.cubridqa.builder.http.HttpUtils;
import com.navercorp.cubridqa.builder.tester.demand.PredictedDemand;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;

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
    private final NodeCapacity nodeCapacity;
    private final Logger logger;
    private final HttpResponseWriter responseWriter;

    public TestHandler(Config config, TestOrchestrator orchestrator, NodeCapacity nodeCapacity, Logger logger) {
        this.config = config;
        this.orchestrator = orchestrator;
        this.nodeCapacity = nodeCapacity;
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
        List<Path> logFilesToSend = new ArrayList<>();
        int httpStatus = 200;
        boolean releaseSlotNeeded = false;
        boolean heavySlot = false;

        try {
            String requestBody = HttpUtils.readRequestBody(exchange);
            JSONObject request = new JSONObject(requestBody);

            // TOCTOU race mitigation: Fast-fail if no local capacity (before expensive Docker setup)
            // This prevents the race where Builder polls /health (sees capacity) but another test
            // is admitted before /test arrives. Return 409 so Builder retries on other nodes.
            PredictedDemand pd = PredictedDemand.fromRequest(request, null);
            if (!hasLocalHeadroom(pd)) {
                logger.warning("Rejecting test request due to insufficient capacity");
                responseWriter.sendJson(exchange, 409, new JSONObject()
                        .put("status", "rejected")
                        .put("error", "No capacity - node oversubscribed"));
                return;
            }

            boolean heavyTest = isHeavyTest(pd);
            if (!orchestrator.tryAcquireSlot(heavyTest)) {
                logger.warning(String.format(
                        "Rejecting test due to concurrency cap (heavy=%s, running=%d, limit=%d)",
                        heavyTest, orchestrator.getRunningTestCount(), orchestrator.getActiveConcurrencyLimit()));
                responseWriter.sendJson(exchange, 409, new JSONObject()
                        .put("status", "rejected")
                        .put("error", "No capacity - concurrency cap reached"));
                return;
            }
            releaseSlotNeeded = true;
            heavySlot = heavyTest;

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
            if (releaseSlotNeeded) {
                orchestrator.releaseSlot(heavySlot);
            }
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
                responseWriter.sendMultipart(exchange, httpStatus, responsePayload, logFilesToSend);
            } else {
                // Send regular JSON response (backward compatibility or no logs)
                responseWriter.sendJson(exchange, httpStatus, responsePayload);
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

    /**
     * Checks if the tester has enough local capacity to admit the predicted demand.
     * Uses the same margin policy as NodeDirectory to prevent TOCTOU oversubscription.
     *
     * @param pd the predicted demand
     * @return true if there's enough headroom
     */
    private boolean hasLocalHeadroom(PredictedDemand pd) {
        // Dimension-specific base margins (same as NodeDirectory) - I/O-first with separate read/write
        final double baseCpu = 0.10, baseMem = 0.20, baseIoRead = 0.35, baseIoWrite = 0.35;
        final double baseNet = 0.25, baseIops = 0.25;
        final double k = 0.50; // Confidence factor
        final double ioSafetyHeadroom = 0.15; // 15% global IO safety headroom

        // Scalar confidence (per-dimension confidence not available at tester level yet)
        double conf = Math.max(0.0, Math.min(1.0, pd.getConfidence()));

        // Compute dimension-specific margins with confidence scaling
        double mCpu = baseCpu + k * (1.0 - conf);
        double mMem = baseMem + k * (1.0 - conf);
        double mIoRead = baseIoRead + k * (1.0 - conf);
        double mIoWrite = baseIoWrite + k * (1.0 - conf);
        double mNet = baseNet + k * (1.0 - conf);
        double mIops = baseIops + k * (1.0 - conf);

        // Get current reserved utilization from orchestrator
        UtilizationSnapshot reserved = orchestrator.getCurrentUtilization();

        // Convert predicted demand to legacy units for comparison
        double reqCpuPct = pd.getCpuMillicores() / 10.0; // mCPU → %
        double reqMemMb = pd.getMemBytes() / (1024.0 * 1024.0); // bytes → MB
        double reqIoReadMbPerSec = pd.getIoReadBytesPerSec() / (1024.0 * 1024.0);
        double reqIoWriteMbPerSec = pd.getIoWriteBytesPerSec() / (1024.0 * 1024.0);
        double reqNetMbPerSec = pd.getNetBytesPerSec() / (1024.0 * 1024.0);
        long reqIops = pd.getIops();

        // Apply margins to required resources
        double requiredCpu = reqCpuPct * (1.0 + mCpu);
        double requiredMem = reqMemMb + Math.max(reqMemMb * mMem, 100.0); // +100MB floor
        double requiredIoRead = reqIoReadMbPerSec * (1.0 + mIoRead);
        double requiredIoWrite = reqIoWriteMbPerSec * (1.0 + mIoWrite);
        double requiredIops = reqIops * (1.0 + mIops);
        double requiredNet = reqNetMbPerSec * (1.0 + mNet);

        // Compute free capacity (capacity - reserved)
        double freeCpu = nodeCapacity.getCpuPct() - reserved.getTotalCpuPct();
        double freeMem = nodeCapacity.getMemMb() - reserved.getTotalMemMb();
        double freeIoRead = nodeCapacity.getIoReadMbPerSec() - reserved.getTotalIoReadBytesPerSec() / (1024.0 * 1024.0);
        double freeIoWrite = nodeCapacity.getIoWriteMbPerSec() - reserved.getTotalIoWriteBytesPerSec() / (1024.0 * 1024.0);
        double freeIops = nodeCapacity.getIops() - reserved.getTotalIops();
        double freeNet = nodeCapacity.getNetMbPerSec() - reserved.getTotalNetBytesPerSec() / (1024.0 * 1024.0);

        // Get safety headroom
        double keepFreeRead = nodeCapacity.getIoReadMbPerSec() * ioSafetyHeadroom;
        double keepFreeWrite = nodeCapacity.getIoWriteMbPerSec() * ioSafetyHeadroom;

        // Check all dimensions - I/O-first: enforce per-direction headroom
        boolean hasHeadroom = freeCpu >= requiredCpu
                && freeMem >= requiredMem
                && (requiredIoRead <= 0 || (freeIoRead - keepFreeRead >= requiredIoRead))
                && (requiredIoWrite <= 0 || (freeIoWrite - keepFreeWrite >= requiredIoWrite))
                && freeIops >= requiredIops
                && freeNet >= requiredNet;

        if (!hasHeadroom) {
            logger.fine(String.format(
                    "Local capacity check failed (conf=%.2f): CPU %.1f < %.1f, Mem %.0f < %.0f, " +
                            "IO_R %.1f < %.1f (keep_free=%.0f), IO_W %.1f < %.1f (keep_free=%.0f), IOPS %.0f < %.0f, Net %.1f < %.1f",
                    conf, freeCpu, requiredCpu, freeMem, requiredMem,
                    freeIoRead, requiredIoRead, keepFreeRead,
                    freeIoWrite, requiredIoWrite, keepFreeWrite,
                    freeIops, requiredIops, freeNet, requiredNet));
        }

        return hasHeadroom;
    }

    private boolean isHeavyTest(PredictedDemand pd) {
        if (pd == null) {
            return false;
        }
        // Do not treat implicit/default predictions as heavy; require explicit predicted block.
        if (!pd.hasExplicitPrediction()) {
            return false;
        }
        long durationMs = pd.getDurationMs();
        if (durationMs < config.getSchedulingMiceThresholdMs()) {
            return false;
        }
        double totalIoMb = (Math.max(0, pd.getIoReadBytesPerSec()) + Math.max(0, pd.getIoWriteBytesPerSec()))
                / (1024.0 * 1024.0);
        return totalIoMb >= config.getSchedulingIoHeavyThreshold();
    }
}
