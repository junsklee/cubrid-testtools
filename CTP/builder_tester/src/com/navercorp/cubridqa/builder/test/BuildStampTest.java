package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuildStamp;
import com.navercorp.cubridqa.builder.BuilderTask;
import com.navercorp.cubridqa.builder.config.Config;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The Builder warns in the request log when a tester runs other Builder-Tester code than its own:
 * the VM tester stayed on code from days before after the Builder and the local tester were restarted.
 */
public class BuildStampTest {
    private static final List<String> WARNINGS = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Field stampField = BuildStamp.class.getDeclaredField("STAMP");
        stampField.setAccessible(true);
        Properties stamp = (Properties) stampField.get(null);
        stamp.setProperty("fingerprint", "aaaaaaaaaaaa");
        stamp.setProperty("built", "2026-10-27T04:41:00Z");

        final String[] health = new String[1];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = health[0].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String worker = "127.0.0.1:" + server.getAddress().getPort();
            Path tmp = Files.createTempDirectory("build_stamp");
            Path conf = tmp.resolve("builder.conf");
            Files.write(conf, ("cubrid_src_dir=" + tmp + "\nshell_tc_dir=" + tmp + "\nwork_dir=" + tmp + "\n")
                .getBytes(StandardCharsets.UTF_8));
            BuilderTask task = new BuilderTask("t", new JSONObject(), new Config(conf.toString()), null);
            Logger log = Logger.getAnonymousLogger();
            log.setUseParentHandlers(false);
            log.addHandler(new Handler() {
                @Override public void publish(LogRecord r) { if (r.getLevel() == Level.WARNING) WARNINGS.add(r.getMessage()); }
                @Override public void flush() {}
                @Override public void close() {}
            });
            Field taskLogger = BuilderTask.class.getDeclaredField("taskLogger");
            taskLogger.setAccessible(true);
            taskLogger.set(task, log);
            Method fetch = BuilderTask.class.getDeclaredMethod("fetchTesterCaps", String.class);
            fetch.setAccessible(true);
            JSONObject concurrency = new JSONObject().put("max", 1).put("maxWhileHeavy", 1);

            health[0] = new JSONObject().put("concurrency", concurrency).put("code",
                new JSONObject().put("fingerprint", "aaaaaaaaaaaa").put("built", "x").put("startedAt", "y")).toString();
            fetch.invoke(task, worker);
            check(WARNINGS.isEmpty(), "same code: no warning: " + WARNINGS);

            health[0] = new JSONObject().put("concurrency", concurrency).put("code",
                new JSONObject().put("fingerprint", "bbbbbbbbbbbb").put("built", "2026-10-21T04:12:00Z")
                    .put("startedAt", "2026-10-21T04:13:00Z")).toString();
            fetch.invoke(task, worker);
            check(WARNINGS.size() == 1 && WARNINGS.get(0).contains("bbbbbbbbbbbb") && WARNINGS.get(0).contains("aaaaaaaaaaaa")
                && WARNINGS.get(0).contains("2026-10-21T04:13:00Z"), "other code: warning names both: " + WARNINGS);

            WARNINGS.clear();
            health[0] = new JSONObject().put("concurrency", concurrency).toString();
            fetch.invoke(task, worker);
            check(WARNINGS.size() == 1 && WARNINGS.get(0).contains("no code fingerprint"),
                "tester from before fingerprints: warning: " + WARNINGS);

            WARNINGS.clear();
            stamp.remove("fingerprint");
            fetch.invoke(task, worker);
            check(WARNINGS.isEmpty(), "Builder without a stamp (not built by compile.sh): no warning: " + WARNINGS);
        } finally {
            server.stop(0);
        }
        System.out.println("\n=== All BuildStampTest tests passed! ===");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
        System.out.println("PASS: " + what);
    }
}
