package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.Builder;
import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.QueuedBuildRequest;
import com.navercorp.cubridqa.builder.logging.LogConfig;
import com.navercorp.cubridqa.builder.logging.LogRotationManager;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

/**
 * /status and /queue/remove as a client polling a request sees them: a queued request is
 * "queued" (not "not_found", which read as a lost request), and one removed through
 * /queue/remove says so, and who asked, after its logs are gone.
 */
public class QueueStatusTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== QueueStatusTest Suite ===\n");
        Path logRoot = Files.createTempDirectory("queue_status_test");
        try {
            Builder builder = bareBuilder(logRoot);
            @SuppressWarnings("unchecked")
            LinkedBlockingQueue<QueuedBuildRequest> queue = (LinkedBlockingQueue<QueuedBuildRequest>) field(builder, "pendingRequests");
            queue.offer(new QueuedBuildRequest("req_20261021_173845_32f5", new JSONObject()));
            queue.offer(new QueuedBuildRequest("req_20261021_173848_d3f3", new JSONObject()));
            Files.createDirectories(logRoot.resolve("requests/req_20261021_173845_32f5"));

            HttpHandler status = handler(builder, "StatusHandler");
            HttpHandler remove = handler(builder, "QueueRemoveHandler");
            HttpHandler health = handler(builder, "HealthCheckHandler");

            JSONObject s = call(status, "GET", "/status?taskId=req_20261021_173848_d3f3", null, null);
            check("queued".equals(s.getString("status")) && s.getInt("queuePosition") == 2, "queued request is queued: " + s);
            s = call(status, "GET", "/status?taskId=req_20261021_999999_0000", null, null);
            check("not_found".equals(s.getString("status")), "unknown request is not_found");

            JSONObject r = call(remove, "POST", "/queue/remove", "{\"taskId\":\"req_20261021_173845_32f5\"}",
                "172.31.1.59, 127.0.0.1");
            check("removed".equals(r.getString("status")) && r.getBoolean("logsDeleted"), "removed from the queue: " + r);
            check(!Files.exists(logRoot.resolve("requests/req_20261021_173845_32f5")), "its logs are deleted");

            s = call(status, "GET", "/status?taskId=req_20261021_173845_32f5", null, null);
            check("removed".equals(s.getString("status")), "status after removal says removed: " + s);
            check(s.getString("removedBy").startsWith("172.31.1.59 (via 10.0.0.9)"), "and who asked (gateway-forwarded address)");
            check(s.getString("removedBy").contains("Firefox"), "with the client's User-Agent");
            check(s.getLong("removedAt") > 0, "and when");
            s = call(status, "GET", "/status?taskId=req_20261021_173848_d3f3", null, null);
            check("queued".equals(s.getString("status")) && s.getInt("queuePosition") == 1, "the next one moved up: " + s);

            JSONObject h = call(health, "GET", "/health?requestId=req_20261021_173845_32f5", null, null);
            check("removed".equals(h.getString("requestStatus")), "/health requestId lookup agrees: " + h.optString("requestStatus"));

            r = call(remove, "POST", "/queue/remove", "{\"taskId\":\"req_20261021_173845_32f5\"}", null);
            check("not_found".equals(r.getString("status")), "removing again finds nothing to remove");
            s = call(status, "GET", "/status?taskId=req_20261021_173845_32f5", null, null);
            check("removed".equals(s.getString("status")), "and keeps the first removal's record");

            // A restart: the record survives it
            Builder restarted = bareBuilder(logRoot);
            invoke(restarted, "loadEndedRequests");
            s = call(handler(restarted, "StatusHandler"), "GET", "/status?taskId=req_20261021_173845_32f5", null, null);
            check("removed".equals(s.getString("status")) && s.getString("removedBy").startsWith("172.31.1.59"),
                "removal record survives a Builder restart: " + s);

            recoverAfterRestart(logRoot);
        } finally {
            try (Stream<Path> paths = Files.walk(logRoot)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        System.out.println("\n=== All QueueStatusTest tests passed! ===");
    }

    /** A Builder with only the state the handlers use: no HTTP server, Docker or threads. */
    private static Builder bareBuilder(Path logRoot) throws Exception {
        // By name: a compile-time reference to sun.misc.Unsafe warns in every build
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Builder builder = (Builder) unsafeType.getMethod("allocateInstance", Class.class)
            .invoke(unsafeField.get(null), Builder.class);
        Files.createDirectories(logRoot.resolve("src"));
        Files.createDirectories(logRoot.resolve("tc"));
        Path conf = logRoot.resolve("builder.conf");
        Files.write(conf, ("cubrid_src_dir=" + logRoot.resolve("src") + "\nshell_tc_dir=" + logRoot.resolve("tc")
            + "\nwork_dir=" + logRoot.resolve("work") + "\n").getBytes(StandardCharsets.UTF_8));
        setField(builder, "config", new BuilderConfig(conf.toString()));
        setField(builder, "activeTasks", new ConcurrentHashMap<>());
        setField(builder, "pendingRequests", new LinkedBlockingQueue<QueuedBuildRequest>());
        setField(builder, "taskDispatchLock", new Object());
        LogConfig logConfig = new LogConfig(10, 10, logRoot.toString(), true);
        setField(builder, "logConfig", logConfig);
        setField(builder, "logRotationManager", new LogRotationManager(logConfig));
        // Field initializers do not run under allocateInstance
        setField(builder, "endedRequests", java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, JSONObject>()));
        return builder;
    }

    /**
     * The queue and running tasks are memory only: a restart used to drop them without a trace
     * (req_20261019_144158_a01d, queued at 14:41:58, gone with the restart at 14:47:20).
     */
    private static void recoverAfterRestart(Path logRoot) throws Exception {
        java.util.List<JSONObject> callbacks = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        com.sun.net.httpserver.HttpServer gateway = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/callback", exchange -> {
            byte[] body = readAll(exchange.getRequestBody());
            callbacks.add(new JSONObject(new String(body, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        gateway.start();
        try {
            String callbackUrl = "http://127.0.0.1:" + gateway.getAddress().getPort() + "/callback";
            writeRequest(logRoot, "req_20261019_144158_a01d", ".bt_queued", new JSONObject().put("callbackUrl", callbackUrl)
                .put("commits", new org.json.JSONArray().put("abc")));
            writeRequest(logRoot, "req_20261019_144100_0001", ".bt_queued", new JSONObject().put("callbackUrl", callbackUrl));
            writeRequest(logRoot, "req_20261019_144000_0002", ".bt_running", new JSONObject().put("callbackUrl", callbackUrl));
            writeRequest(logRoot, "req_20261019_144200_0003", ".bt_queued", new JSONObject().put("callbackUrl", callbackUrl)
                .put("buildOnly", true).put("buildUpload", new JSONObject().put("host", "h").put("password", "***")));
            writeRequest(logRoot, "req_20261019_143000_0004", null, new JSONObject().put("callbackUrl", callbackUrl));

            Builder builder = bareBuilder(logRoot);
            invoke(builder, "loadEndedRequests");
            invoke(builder, "recoverRequestsOfLastRun");
            @SuppressWarnings("unchecked")
            LinkedBlockingQueue<QueuedBuildRequest> queue = (LinkedBlockingQueue<QueuedBuildRequest>) field(builder, "pendingRequests");
            java.util.List<String> order = new java.util.ArrayList<>();
            for (QueuedBuildRequest q : queue) {
                order.add(q.getRequestId());
            }
            check(order.equals(java.util.Arrays.asList("req_20261019_144100_0001", "req_20261019_144158_a01d")),
                "queued requests are queued again, in arrival order: " + order);
            QueuedBuildRequest a01d = queue.toArray(new QueuedBuildRequest[0])[1];
            check("abc".equals(a01d.getRequest().getJSONArray("commits").getString(0)), "with their request");
            check(Files.exists(logRoot.resolve("requests/req_20261019_144158_a01d/.bt_queued")), "still marked queued");
            HttpHandler status = handler(builder, "StatusHandler");
            JSONObject s = call(status, "GET", "/status?taskId=req_20261019_144158_a01d", null, null);
            check("queued".equals(s.getString("status")) && s.getInt("queuePosition") == 2, "and /status says so: " + s);

            s = call(status, "GET", "/status?taskId=req_20261019_144000_0002", null, null);
            check("interrupted".equals(s.getString("status")) && s.getString("message").contains("running"),
                "a request running at the restart is interrupted: " + s);
            check(!Files.exists(logRoot.resolve("requests/req_20261019_144000_0002/.bt_running")), "its marker is cleared");
            s = call(status, "GET", "/status?taskId=req_20261019_144200_0003", null, null);
            check("interrupted".equals(s.getString("status")) && s.getString("message").contains("upload password"),
                "a queued request whose secret is not on disk cannot be queued again: " + s);
            s = call(status, "GET", "/status?taskId=req_20261019_143000_0004", null, null);
            check("not_found".equals(s.getString("status")), "a finished request is left alone");

            long deadline = System.currentTimeMillis() + 10_000;
            while (callbacks.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            java.util.Set<String> called = new java.util.HashSet<>();
            for (JSONObject cb : callbacks) {
                check("error".equals(cb.getString("status")) && cb.getString("message").contains("restarted"),
                    "error callback, as for a failed request: " + cb);
                called.add(cb.getString("requestId"));
            }
            check(called.equals(new java.util.HashSet<>(java.util.Arrays.asList("req_20261019_144000_0002", "req_20261019_144200_0003"))),
                "the interrupted requests' callbacks are sent: " + called);
        } finally {
            gateway.stop(0);
        }
    }

    private static void writeRequest(Path logRoot, String requestId, String marker, JSONObject request) throws Exception {
        Path dir = Files.createDirectories(logRoot.resolve("requests").resolve(requestId));
        Files.write(dir.resolve("request.json"), request.put("requestId", requestId).toString().getBytes(StandardCharsets.UTF_8));
        if (marker != null) {
            Files.write(dir.resolve(marker), "1".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void invoke(Builder builder, String method) throws Exception {
        java.lang.reflect.Method m = Builder.class.getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(builder);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        for (int n; (n = in.read(chunk)) > 0; ) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static HttpHandler handler(Builder builder, String name) throws Exception {
        Class<?> type = Class.forName(Builder.class.getName() + "$" + name);
        Constructor<?> ctor = type.getDeclaredConstructor(Builder.class);
        ctor.setAccessible(true);
        return (HttpHandler) ctor.newInstance(builder);
    }

    private static JSONObject call(HttpHandler handler, String method, String uri, String body, String forwardedFor) throws Exception {
        FakeExchange exchange = new FakeExchange(method, uri, body);
        if (forwardedFor != null) {
            exchange.requestHeaders.add("X-Forwarded-For", forwardedFor);
            exchange.requestHeaders.add("User-Agent", "Mozilla/5.0 (Macintosh) Firefox/156.0");
        }
        handler.handle(exchange);
        return new JSONObject(new String(exchange.responseBody.toByteArray(), StandardCharsets.UTF_8));
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = Builder.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = Builder.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
        System.out.println("  ok: " + what);
    }

    /** The parts of an exchange the Builder's handlers use; the peer is the gateway at 10.0.0.9. */
    private static final class FakeExchange extends HttpExchange {
        final Headers requestHeaders = new Headers();
        final Headers responseHeaders = new Headers();
        final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final String method;
        private final URI uri;
        private final InputStream requestBody;
        private int responseCode = -1;

        FakeExchange(String method, String uri, String body) {
            this.method = method;
            this.uri = URI.create(uri);
            this.requestBody = new ByteArrayInputStream((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int code, long length) { responseCode = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("10.0.0.9", 40000); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8089); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream in, OutputStream out) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
