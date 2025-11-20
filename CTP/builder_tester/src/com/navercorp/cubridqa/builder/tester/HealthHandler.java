package com.navercorp.cubridqa.builder.tester;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;
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
    private final ActualSampler actualSampler; // For actual resource sampling

    public HealthHandler(BuilderConfig config, HttpResponseWriter responseWriter,
                         NodeCapacity nodeCapacity, TestOrchestrator testOrchestrator,
                         ActualSampler actualSampler) {
        this.config = config;
        this.responseWriter = responseWriter;
        this.nodeCapacity = nodeCapacity;
        this.testOrchestrator = testOrchestrator;
        this.actualSampler = actualSampler;
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
        int peakLimit = Math.max(1, config.getMaxConcurrentTests());
        int heavyLimit = Math.max(1, config.getMaxConcurrentTestsWhileHeavy());
        int postHeavyLimit = Math.max(heavyLimit, config.getMaxConcurrentTestsAfterHeavy());
        int activeLimit = testOrchestrator != null
                ? Math.max(1, testOrchestrator.getActiveConcurrencyLimit())
                : heavyLimit;

        concurrency.put("max", activeLimit); // Advertise current usable capacity (heavy vs post-heavy)
        concurrency.put("maxWhileHeavy", heavyLimit);
        concurrency.put("maxAfterHeavy", postHeavyLimit);
        concurrency.put("maxPeak", peakLimit);
        if (testOrchestrator != null) {
            concurrency.put("activeLimit", activeLimit);
            concurrency.put("heavyRunning", Math.max(0, testOrchestrator.getHeavyInFlightCount()));
        }
        concurrency.put("running", runningTests);
        concurrency.put("queued", 0); // Not implemented yet
        response.put("concurrency", concurrency);

        // Back-compat: expose current limit at top level for legacy builders
        response.put("maxConcurrentTests", activeLimit);

        // Capacity (canonical units)
        JSONObject capacity = new JSONObject();
        // NodeCapacity.getCpuPct() is cores×100, so divide by 100 to get cores, then multiply by 1000 for millicores
        capacity.put("cpu_millicores", (int) (nodeCapacity.getCpuPct() / 100.0 * 1000.0));
        capacity.put("mem_bytes", (long) (nodeCapacity.getMemMb() * 1024 * 1024)); // Convert MB to bytes
        capacity.put("io_read_bytes_per_sec", nodeCapacity.getIoReadCapacityBytesPerSec());
        capacity.put("io_write_bytes_per_sec", nodeCapacity.getIoWriteCapacityBytesPerSec());
        capacity.put("iops", (long) nodeCapacity.getIops());
        capacity.put("net_bytes_per_sec", (long) (nodeCapacity.getNetMbPerSec() * 1024 * 1024));
        response.put("capacity", capacity);

        // Utilization Reserved (sum of predicted demands from running tests)
        JSONObject utilizationReserved = new JSONObject();
        if (testOrchestrator != null) {
            UtilizationSnapshot reserved = testOrchestrator.getCurrentUtilization();
            utilizationReserved.put("cpu_millicores", (long) reserved.getTotalCpuMillicores());
            utilizationReserved.put("mem_bytes", reserved.getTotalMemBytes());
            utilizationReserved.put("io_read_bytes_per_sec", reserved.getTotalIoReadBytesPerSec());
            utilizationReserved.put("io_write_bytes_per_sec", reserved.getTotalIoWriteBytesPerSec());
            utilizationReserved.put("iops", reserved.getTotalIops());
            utilizationReserved.put("net_bytes_per_sec", reserved.getTotalNetBytesPerSec());
            utilizationReserved.put("tests", reserved.getTestCount());
            utilizationReserved.put("defaults", reserved.getDefaultCount());

            // Log if tests are using conservative defaults (no predictions)
            if (reserved.hasDefaults()) {
                logger.log(Level.FINE, "{0}/{1} running tests using default predictions",
                        new Object[]{reserved.getDefaultCount(), reserved.getTestCount()});
            }
        } else {
            // Fallback if orchestrator not available
            utilizationReserved.put("cpu_millicores", 0);
            utilizationReserved.put("mem_bytes", 0L);
            utilizationReserved.put("io_read_bytes_per_sec", 0L);
            utilizationReserved.put("io_write_bytes_per_sec", 0L);
            utilizationReserved.put("iops", 0L);
            utilizationReserved.put("net_bytes_per_sec", 0L);
            utilizationReserved.put("tests", 0);
            utilizationReserved.put("defaults", 0);
        }
        response.put("utilization_reserved", utilizationReserved);

        // Utilization Actual (sampled from Docker stats)
        UtilizationSnapshot actual = actualSampler != null
            ? actualSampler.sampleCurrentUtilization()
            : UtilizationSnapshot.empty();

        JSONObject utilizationActual = new JSONObject();
        utilizationActual.put("cpu_millicores", (long) actual.getTotalCpuMillicores());
        utilizationActual.put("mem_bytes", actual.getTotalMemBytes());
        utilizationActual.put("io_read_bytes_per_sec", actual.getTotalIoReadBytesPerSec());
        utilizationActual.put("io_write_bytes_per_sec", actual.getTotalIoWriteBytesPerSec());
        utilizationActual.put("iops", actual.getTotalIops());
        utilizationActual.put("net_bytes_per_sec", actual.getTotalNetBytesPerSec());
        response.put("utilization_actual", utilizationActual);

        // Error ratios (actual - reserved) / reserved
        // Shows prediction accuracy for feedback loops
        JSONObject errorRatio = new JSONObject();

        // Get reserved values from utilizationReserved object
        long reservedCpu = utilizationReserved.optLong("cpu_millicores", 0);
        long reservedMem = utilizationReserved.optLong("mem_bytes", 0);
        long reservedIoRead = utilizationReserved.optLong("io_read_bytes_per_sec", 0);
        long reservedIoWrite = utilizationReserved.optLong("io_write_bytes_per_sec", 0);
        long reservedIops = utilizationReserved.optLong("iops", 0);
        long reservedNet = utilizationReserved.optLong("net_bytes_per_sec", 0);

        errorRatio.put("cpu", calculateErrorRatio(actual.getTotalCpuMillicores(), reservedCpu));
        errorRatio.put("mem", calculateErrorRatio(actual.getTotalMemBytes(), reservedMem));
        errorRatio.put("io_r", calculateErrorRatio(actual.getTotalIoReadBytesPerSec(), reservedIoRead));
        errorRatio.put("io_w", calculateErrorRatio(actual.getTotalIoWriteBytesPerSec(), reservedIoWrite));
        errorRatio.put("iops", calculateErrorRatio(actual.getTotalIops(), reservedIops));
        errorRatio.put("net", calculateErrorRatio(actual.getTotalNetBytesPerSec(), reservedNet));
        response.put("error_ratio", errorRatio);

        // Optional: publish safety headroom as an advisory value
        double ioReadCap = capacity.optDouble("io_read_bytes_per_sec", 0);
        double ioWriteCap = capacity.optDouble("io_write_bytes_per_sec", 0);
        double headroom = config.getIoSafetyHeadroomRatio(); // Default 15%
        JSONObject safety = new JSONObject();
        safety.put("io_read_keep_free", (long) (ioReadCap * headroom));
        safety.put("io_write_keep_free", (long) (ioWriteCap * headroom));
        response.put("safety_headroom", safety);

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

    /**
     * Calculates error ratio: (actual - reserved) / reserved
     * Returns 0.0 if reserved is 0 (no prediction to compare against)
     */
    private double calculateErrorRatio(double actual, long reserved) {
        if (reserved <= 0) {
            return 0.0;
        }
        return (actual - reserved) / (double) reserved;
    }
}
