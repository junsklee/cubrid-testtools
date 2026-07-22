package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.exec.SqlAgentPool;
import com.navercorp.cubridqa.builder.http.HttpUtils;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.git.SqlTcSync;
import com.navercorp.cubridqa.builder.tester.stats.TestStatsStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles POST /finalize-request to flush a request journal.
 *
 * <p>Called by the builder after all tests for a requestId are complete.
 * This signals the tester to flush and close the request journal.
 */
public class FinalizeRequestHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(FinalizeRequestHandler.class.getName());

    private final TestStatsStore testStatsStore;
    private final ShellTcSync shellTcSync;
    private final SqlTcSync sqlTcSync;
    private final SqlAgentPool sqlAgentPool;
    private final HttpResponseWriter responseWriter;

    public FinalizeRequestHandler(TestStatsStore testStatsStore, ShellTcSync shellTcSync) {
        this(testStatsStore, shellTcSync, null, null);
    }

    public FinalizeRequestHandler(TestStatsStore testStatsStore, ShellTcSync shellTcSync,
                                  SqlTcSync sqlTcSync, SqlAgentPool sqlAgentPool) {
        this.testStatsStore = testStatsStore;
        this.shellTcSync = shellTcSync;
        this.sqlTcSync = sqlTcSync;
        this.sqlAgentPool = sqlAgentPool;
        this.responseWriter = new HttpResponseWriter();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            HttpUtils.sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String requestBody = HttpUtils.readRequestBody(exchange);
            JSONObject request = new JSONObject(requestBody);
            String requestId = request.getString("requestId");

            logger.info("Received finalize request for: " + requestId);

            // Flush the request journal
            testStatsStore.flushRequestJournal(requestId);
            if (sqlAgentPool != null) {
                sqlAgentPool.teardownRequest(requestId, logger);
            }
            if (shellTcSync != null) {
                shellTcSync.cleanupRequestWorkspace(logger, requestId);
            }
            if (sqlTcSync != null) {
                sqlTcSync.cleanupRequestWorkspace(logger, requestId);
            }

            JSONObject response = new JSONObject()
                .put("status", "success")
                .put("requestId", requestId);

            responseWriter.sendJson(exchange, 200, response);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error finalizing request", e);
            JSONObject response = new JSONObject()
                .put("status", "error")
                .put("message", e.getMessage());
            responseWriter.sendJson(exchange, 500, response);
        }
    }
}
