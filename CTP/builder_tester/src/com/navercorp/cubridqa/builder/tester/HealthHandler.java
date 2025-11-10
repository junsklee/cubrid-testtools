package com.navercorp.cubridqa.builder.tester;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.navercorp.cubridqa.builder.BuilderConfig;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Extended health handler for smart scheduling.
 *
 * <p>Returns node capacity, utilization, cached artifacts, and health flags.
 * This endpoint is polled by the builder's NodeDirectory to maintain
 * cluster state for scheduling decisions.</p>
 */
public class HealthHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(HealthHandler.class.getName());

    private final HttpResponseWriter responseWriter;
    private final BuilderConfig config;
    private final NodeCapacity nodeCapacity;
    private final TestOrchestrator testOrchestrator; // For running test count

    public HealthHandler(BuilderConfig config, HttpResponseWriter responseWriter,
                         NodeCapacity nodeCapacity, TestOrchestrator testOrchestrator) {
        this.config = config;
        this.responseWriter = responseWriter;
        this.nodeCapacity = nodeCapacity;
        this.testOrchestrator = testOrchestrator;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            JSONObject healthStatus = buildHealthResponse();
            responseWriter.sendJson(exchange, 200, healthStatus);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in health check: " + e.getMessage(), e);
            responseWriter.sendJson(exchange, 500,
                new JSONObject().put("status", "error").put("message", e.getMessage()));
        }
    }

    private JSONObject buildHealthResponse() {
        JSONObject response = new JSONObject();

        // Basic info
        response.put("v", 1);
        response.put("nodeId", getNodeId());
        response.put("ts", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("status", "healthy");

        // Concurrency
        int runningTests = testOrchestrator != null ? testOrchestrator.getRunningTestCount() : 0;
        JSONObject concurrency = new JSONObject();
        concurrency.put("max", Math.max(1, config.getMaxConcurrentTests()));
        concurrency.put("running", runningTests);
        concurrency.put("queued", 0); // Not implemented yet
        response.put("concurrency", concurrency);

        // Capacity
        response.put("capacity", nodeCapacity.toJSON());

        // Utilization (simplified - actual would sum predicted demands of running tests)
        JSONObject utilization = new JSONObject();
        utilization.put("cpu_pct", 0.0); // TODO: Sum from running tests
        utilization.put("mem_mb", 0.0);
        utilization.put("io_mb_s", 0.0);
        utilization.put("iops", 0.0);
        utilization.put("net_mb_s", 0.0);
        response.put("utilization", utilization);

        // Docker images
        JSONObject images = new JSONObject();
        List<String> imageList = listDockerImages();
        images.put("present", new JSONArray(imageList));
        images.put("cache_size", imageList.size());
        response.put("images", images);

        // Build packages
        JSONObject packages = new JSONObject();
        List<String> packageList = listBuildPackages();
        packages.put("present", new JSONArray(packageList));
        response.put("packages", packages);

        // Health flags
        JSONObject flags = new JSONObject();
        flags.put("degraded", false); // TODO: Could check load average
        flags.put("disk_pressure", nodeCapacity.isDiskPressure());
        response.put("flags", flags);

        return response;
    }

    private String getNodeId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostAddress();
            return hostname + ":" + config.getTesterPort();
        } catch (Exception e) {
            return "unknown:" + config.getTesterPort();
        }
    }

    private List<String> listDockerImages() {
        List<String> images = new ArrayList<>();
        if (!config.useDockerForTester()) {
            return images;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "docker", "images", "--filter", "reference=cubrid-test:*",
                "--format", "{{.Repository}}:{{.Tag}}"
            );
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.contains("<none>")) {
                        images.add(line);
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to list Docker images: " + e.getMessage(), e);
        }

        return images;
    }

    private List<String> listBuildPackages() {
        List<String> packages = new ArrayList<>();

        try {
            File cacheDir = new File(config.getWorkDir(), "cache");
            if (!cacheDir.exists() || !cacheDir.isDirectory()) {
                return packages;
            }

            File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".tar.gz"));
            if (files != null) {
                for (File file : files) {
                    packages.add(file.getName());
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to list build packages: " + e.getMessage(), e);
        }

        return packages;
    }
}
