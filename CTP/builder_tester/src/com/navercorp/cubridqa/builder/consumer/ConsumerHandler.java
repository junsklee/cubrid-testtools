package com.navercorp.cubridqa.builder.consumer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.logging.RequestIds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * HTTP side of {@link ConsumerPool}, used by the runner that run_builder_tester.sh starts on a
 * consumer VM:
 * <ul>
 * <li>GET  /consumer/config?requestId=&amp;slot= : whether to run, and tester settings</li>
 * <li>GET  /consumer/lib/ and /consumer/lib/&lt;jar&gt; : the runner's classpath</li>
 * <li>POST /consumer/lease {requestId, slot, runner} : the next case, or {"done": true}</li>
 * <li>POST /consumer/heartbeat {jobId} : {"stop": true} when the case was withdrawn</li>
 * <li>POST /consumer/result?jobId= : the VM tester's /test response (status in X-Tester-Status)</li>
 * </ul>
 */
public class ConsumerHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(ConsumerHandler.class.getName());
    /** Version of the runner protocol; a runner refuses a Builder that speaks another one. */
    public static final int PROTOCOL = 1;
    static final int HEARTBEAT_SECONDS = 20;
    static final int LEASE_WAIT_SECONDS = 20;
    /** Largest result (response and logs); the multipart parse holds a few copies of it. */
    private static final long MAX_RESULT_BYTES = 128L * 1024 * 1024;

    private final BuilderConfig config;
    private final File libDir;

    public ConsumerHandler(BuilderConfig config) {
        this.config = config;
        this.libDir = findLibDir();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            ConsumerPool pool = ConsumerPool.get();
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (pool == null) {
                sendJson(exchange, 404, new JSONObject().put("error", "consumer execution is not configured on this Builder"));
            } else if ("GET".equals(method) && path.equals("/consumer/config")) {
                handleConfig(exchange, pool);
            } else if ("GET".equals(method) && path.startsWith("/consumer/lib/")) {
                handleLib(exchange, path.substring("/consumer/lib/".length()));
            } else if ("POST".equals(method) && path.equals("/consumer/lease")) {
                handleLease(exchange, pool);
            } else if ("POST".equals(method) && path.equals("/consumer/heartbeat")) {
                JSONObject body = new JSONObject(readBody(exchange, 64 * 1024));
                sendJson(exchange, 200, new JSONObject().put("stop", pool.heartbeat(body.optString("jobId", null))));
            } else if ("POST".equals(method) && path.equals("/consumer/result")) {
                handleResult(exchange, pool);
            } else {
                sendJson(exchange, 404, new JSONObject().put("error", "not found"));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Consumer endpoint " + exchange.getRequestURI() + " failed", e);
            try {
                sendJson(exchange, 400, new JSONObject().put("error", String.valueOf(e.getMessage())));
            } catch (IOException ignore) {
                // client gone
            }
        } finally {
            exchange.close();
        }
    }

    private void handleConfig(HttpExchange exchange, ConsumerPool pool) throws IOException {
        Map<String, String> query = query(exchange);
        boolean active = pool.claim(query.get("requestId"), query.get("slot"));
        JSONObject tester = new JSONObject();
        for (Map.Entry<String, String> entry : config.getConsumerTesterOverrides().entrySet()) {
            tester.put(entry.getKey(), entry.getValue());
        }
        sendJson(exchange, 200, new JSONObject()
            .put("protocol", PROTOCOL)
            .put("active", active)
            .put("heartbeatSeconds", HEARTBEAT_SECONDS)
            .put("tester", tester));
    }

    private void handleLib(HttpExchange exchange, String name) throws IOException {
        List<String> jars = listJars();
        if (name.isEmpty()) {
            sendText(exchange, 200, String.join("\n", jars) + "\n");
            return;
        }
        if (!jars.contains(name)) {
            sendText(exchange, 404, "not found\n");
            return;
        }
        File jar = new File(libDir, name);
        exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
        exchange.sendResponseHeaders(200, jar.length());
        try (OutputStream os = exchange.getResponseBody()) {
            Files.copy(jar.toPath(), os);
        }
    }

    private void handleLease(HttpExchange exchange, ConsumerPool pool) throws IOException, InterruptedException {
        JSONObject body = new JSONObject(readBody(exchange, 64 * 1024));
        String requestId = body.optString("requestId", "");
        String runner = body.optString("runner", "unknown").replaceAll("[^A-Za-z0-9_.@:-]", "_");
        if (!RequestIds.isValid(requestId)) {
            sendJson(exchange, 400, new JSONObject().put("error", "invalid requestId"));
            return;
        }
        ConsumerPool.Lease lease = pool.lease(requestId, body.optString("slot", null), runner, LEASE_WAIT_SECONDS * 1000L);
        if (lease == null) {
            sendJson(exchange, 200, new JSONObject().put("done", true));
            return;
        }
        logger.info("Consumer runner " + runner + " leased " + lease.testRequest.optString("testName") + " of " + requestId);
        sendJson(exchange, 200, new JSONObject().put("jobId", lease.jobId).put("testRequest", lease.testRequest));
    }

    private void handleResult(HttpExchange exchange, ConsumerPool pool) throws IOException {
        String jobId = query(exchange).get("jobId");
        int status;
        try {
            status = Integer.parseInt(exchange.getRequestHeaders().getFirst("X-Tester-Status"));
        } catch (Exception e) {
            sendJson(exchange, 400, new JSONObject().put("error", "missing X-Tester-Status"));
            return;
        }
        // Refuse without keeping the body: only a leased case's runner may post one this large. It
        // is drained (read and discarded) first: the runner sends the whole body before reading the
        // answer, and the server drains only 64 KB on close, so for a larger body the runner got a
        // broken connection instead of 410/413, retried for minutes, and the case ran again on
        // other VMs.
        if (!pool.isExpected(jobId)) {
            drain(exchange.getRequestBody(), 2 * MAX_RESULT_BYTES);
            sendJson(exchange, 410, new JSONObject().put("accepted", false).put("error", "the case is no longer expected"));
            return;
        }
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null && length.matches("\\d+") && Long.parseLong(length) > MAX_RESULT_BYTES) {
            drain(exchange.getRequestBody(), 2 * MAX_RESULT_BYTES);
            sendJson(exchange, 413, new JSONObject().put("accepted", false)
                .put("error", "result larger than " + MAX_RESULT_BYTES + " bytes"));
            return;
        }
        byte[] body = readBytes(exchange.getRequestBody(), MAX_RESULT_BYTES);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!pool.complete(jobId, new ConsumerPool.Relay(status, contentType, body))) {
            sendJson(exchange, 410, new JSONObject().put("accepted", false).put("error", "the case is no longer expected"));
            return;
        }
        sendJson(exchange, 200, new JSONObject().put("accepted", true));
    }

    /** The jars a runner needs: everything in the Builder's lib directory. */
    private List<String> listJars() {
        List<String> jars = new ArrayList<>();
        File[] files = libDir == null ? null : libDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files != null) {
            for (File file : files) {
                jars.add(file.getName());
            }
        }
        Collections.sort(jars);
        return jars;
    }

    /** lib/ next to the jar this class came from, or ../lib from a build/ directory. */
    private static File findLibDir() {
        try {
            File source = new File(ConsumerHandler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = source.isFile() ? source.getParentFile() : new File(source.getParentFile(), "lib");
            return dir.isDirectory() ? dir : null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Cannot locate the lib directory served to consumer runners", e);
            return null;
        }
    }

    private static Map<String, String> query(HttpExchange exchange) throws IOException {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        return params;
    }

    private static String readBody(HttpExchange exchange, long limit) throws IOException {
        return new String(readBytes(exchange.getRequestBody(), limit), StandardCharsets.UTF_8);
    }

    /** Reads and discards up to limit bytes of a request body. */
    private static void drain(InputStream in, long limit) {
        byte[] chunk = new byte[64 * 1024];
        long total = 0;
        try {
            for (int n; total < limit && (n = in.read(chunk)) > 0; ) {
                total += n;
            }
        } catch (IOException ignore) {
            // the runner gave up: nothing left to answer
        }
    }

    private static byte[] readBytes(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) {
                throw new IOException("request body larger than " + limit + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void sendJson(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
