package com.navercorp.cubridqa.builder.tester;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.tester.stats.*;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Score handler for batch test predictions.
 *
 * <p>Accepts POST requests with a list of tests, commit, and baseline,
 * and returns predicted resource demands for each test. Used by the
 * builder's scheduler to make informed placement decisions.</p>
 *
 * <p>Request format:
 * <pre>
 * {
 *   "tests": ["shell/sql/ha/test_01.sh", ...],
 *   "commit": "6ea587e",
 *   "baseline": "4b045a6"
 * }
 * </pre>
 * </p>
 *
 * <p>Response format:
 * <pre>
 * {
 *   "predictions": {
 *     "shell/sql/ha/test_01.sh": {
 *       "tpred_ms": 14250,
 *       "cpu_pct": 38.2,
 *       "mem_mb": 512,
 *       "io_mb_s": 12.4,
 *       "iops": 220,
 *       "net_mb_s": 3.1,
 *       "confidence": 0.85
 *     },
 *     ...
 *   }
 * }
 * </pre>
 * </p>
 */
public class ScoreHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(ScoreHandler.class.getName());

    private final HttpResponseWriter responseWriter;
    private final BuilderConfig config;
    private final TestStatsStore testStatsStore;
    private final NodeCapacity nodeCapacity;

    public ScoreHandler(BuilderConfig config, HttpResponseWriter responseWriter,
                        TestStatsStore testStatsStore, NodeCapacity nodeCapacity) {
        this.config = config;
        this.responseWriter = responseWriter;
        this.testStatsStore = testStatsStore;
        this.nodeCapacity = nodeCapacity;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Only accept POST requests
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            responseWriter.sendJson(exchange, 405,
                new JSONObject().put("error", "Method not allowed. Use POST."));
            return;
        }

        try {
            // Parse request body
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }

            JSONObject request = new JSONObject(body.toString());

            // Validate required fields
            if (!request.has("tests") || !request.has("commit") || !request.has("baseline")) {
                responseWriter.sendJson(exchange, 400,
                    new JSONObject().put("error", "Missing required fields: tests, commit, baseline"));
                return;
            }

            JSONArray tests = request.getJSONArray("tests");
            String commit = request.getString("commit");
            String baseline = request.getString("baseline");

            // Build context and hardware
            BuildContext context = new BuildContext(commit, baseline);
            NodeHardware hardware = NodeHardware.builder()
                .cpuPct(nodeCapacity.getCpuPct())
                .memMb(nodeCapacity.getMemMb())
                .ioMbPerSec(nodeCapacity.getIoMbPerSec())
                .iops(nodeCapacity.getIops())
                .netMbPerSec(nodeCapacity.getNetMbPerSec())
                .build();

            // Generate predictions
            JSONObject predictions = new JSONObject();
            for (int i = 0; i < tests.length(); i++) {
                String testKey = tests.getString(i);
                PredictedDemand demand = testStatsStore.predict(testKey, context, hardware);
                predictions.put(testKey, demand.toJSON());
            }

            // Build response
            JSONObject response = new JSONObject();
            response.put("predictions", predictions);

            responseWriter.sendJson(exchange, 200, response);

            logger.info("Scored " + tests.length() + " tests for commit " + commit);

        } catch (org.json.JSONException e) {
            logger.log(Level.WARNING, "Invalid JSON in score request: " + e.getMessage(), e);
            responseWriter.sendJson(exchange, 400,
                new JSONObject().put("error", "Invalid JSON: " + e.getMessage()));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in score endpoint: " + e.getMessage(), e);
            responseWriter.sendJson(exchange, 500,
                new JSONObject().put("error", "Internal error: " + e.getMessage()));
        }
    }
}
