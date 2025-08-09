/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Builder - Receives build requests and builds CUBRID at specified commits
 * 
 * This service receives HTTP requests to build CUBRID at multiple commits
 * concurrently using Docker containers. Built packages are then sent to the
 * Tester service for testing.
 */
public class Builder {
    private static final Logger logger = Logger.getLogger(Builder.class.getName());
    
    private final BuilderConfig config;
    private final HttpServer server;
    private final ExecutorService buildExecutor;
    private final Map<String, BuilderTask> activeTasks;
    private final DockerBuildManager dockerManager;
    
    public Builder(BuilderConfig config) throws IOException {
        this.config = config;
        this.buildExecutor = Executors.newFixedThreadPool(config.getMaxConcurrentBuilds());
        this.activeTasks = new ConcurrentHashMap<>();
        this.dockerManager = new DockerBuildManager(config);
        
        // Create HTTP server
        this.server = HttpServer.create(new InetSocketAddress(config.getListenPort()), 0);
        this.server.createContext("/build", new BuildRequestHandler());
        this.server.createContext("/status", new StatusHandler());
        this.server.createContext("/health", new HealthCheckHandler());
        this.server.setExecutor(null); // creates a default executor
    }
    
    public void start() {
        // Ensure work directory exists
        File workDir = new File(config.getWorkDir());
        if (!workDir.exists()) {
            workDir.mkdirs();
        }

        // Initialize Docker environment if enabled
        if (config.useDocker()) {
            try {
                dockerManager.initialize();
                logger.info("Docker build environment initialized");
            } catch (Exception e) {
                logger.warning("Failed to initialize Docker: " + e.getMessage());
                logger.warning("Will use direct build method");
            }
        }
        
        server.start();
        logger.info("Builder service started on port " + config.getListenPort());
        logger.info("CUBRID source: " + config.getCubridSrcDir());
        logger.info("Work directory: " + config.getWorkDir());
        logger.info("Max concurrent builds: " + config.getMaxConcurrentBuilds());
        logger.info("Docker enabled: " + config.useDocker());
    }
    
    public void stop() {
        server.stop(0);
        buildExecutor.shutdown();
        try {
            if (!buildExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                buildExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            buildExecutor.shutdownNow();
        }
        logger.info("Builder service stopped");
    }
    
    private class BuildRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                // Clean up stale containers from previous runs before starting new task
                try {
                    cleanupStaleContainers();
                } catch (Exception cleanupEx) {
                    logger.warning("Container cleanup skipped due to error: " + cleanupEx.getMessage());
                }
                
                // Read request body
                String requestBody = readRequestBody(exchange);
                JSONObject request = new JSONObject(requestBody);
                
                // Validate request
                validateRequest(request);
                
                // Extract parameters
                JSONArray commits = request.getJSONArray("commits");
                JSONArray tests = request.getJSONArray("tests");
                String callbackUrl = request.getString("callbackUrl");
                String workerIp = request.optString("workerIp", "localhost");
                String buildType = request.optString("buildType", "debug");
                
                // Check tester reachability
                validateTesterReachability(workerIp);
                
                // Log request
                logger.info(String.format("Received build request for %d commits and %d tests",
                    commits.length(), tests.length()));
                
                // Create task ID
                String taskId = generateTaskId(request);
                
                // Check if already running
                if (activeTasks.containsKey(taskId)) {
                    JSONObject response = new JSONObject()
                        .put("status", "already_running")
                        .put("taskId", taskId);
                    sendJsonResponse(exchange, 200, response);
                    return;
                }
                
                // Create and submit task
                BuilderTask task = new BuilderTask(taskId, request, config, dockerManager);
                activeTasks.put(taskId, task);
                
                CompletableFuture.runAsync(() -> {
                    try {
                        task.run();
                    } finally {
                        activeTasks.remove(taskId);
                    }
                }, buildExecutor);
                
                // Send accepted response
                JSONObject response = new JSONObject()
                    .put("status", "accepted")
                    .put("taskId", taskId)
                    .put("message", "Build request received and processing");
                
                sendJsonResponse(exchange, 202, response);
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error handling request", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                sendJsonResponse(exchange, 400, error);
            }
        }
    }
    
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                // Get task ID from query parameter
                String query = exchange.getRequestURI().getQuery();
                String taskId = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if (pair.length == 2 && "taskId".equals(pair[0])) {
                            taskId = pair[1];
                            break;
                        }
                    }
                }
                
                JSONObject response = new JSONObject();
                
                if (taskId != null) {
                    BuilderTask task = activeTasks.get(taskId);
                    if (task != null) {
                        response.put("status", "running")
                                .put("taskId", taskId)
                                .put("progress", task.getProgress());
                    } else {
                        response.put("status", "not_found")
                                .put("taskId", taskId);
                    }
                } else {
                    // Return all active tasks
                    JSONArray tasks = new JSONArray();
                    for (Map.Entry<String, BuilderTask> entry : activeTasks.entrySet()) {
                        tasks.put(new JSONObject()
                            .put("taskId", entry.getKey())
                            .put("progress", entry.getValue().getProgress()));
                    }
                    response.put("activeTasks", tasks);
                }
                
                sendJsonResponse(exchange, 200, response);
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in status handler", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                sendJsonResponse(exchange, 500, error);
            }
        }
    }
    
    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                JSONObject healthResponse = new JSONObject()
                    .put("status", "healthy")
                    .put("service", "Builder")
                    .put("timestamp", System.currentTimeMillis())
                    .put("activeTasks", activeTasks.size())
                    .put("workDir", config.getWorkDir())
                    .put("dockerEnabled", config.useDocker())
                    .put("maxConcurrentBuilds", config.getMaxConcurrentBuilds());
                
                sendJsonResponse(exchange, 200, healthResponse);
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in health check", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                sendJsonResponse(exchange, 500, error);
            }
        }
    }
    
    private void validateRequest(JSONObject request) throws IllegalArgumentException {
        if (!request.has("commits") || request.getJSONArray("commits").length() == 0) {
            throw new IllegalArgumentException("Request must contain non-empty 'commits' array");
        }
        if (!request.has("tests") || request.getJSONArray("tests").length() == 0) {
            throw new IllegalArgumentException("Request must contain non-empty 'tests' array");
        }
        if (!request.has("workerIp") || request.getString("workerIp").trim().isEmpty()) {
            throw new IllegalArgumentException("Request must contain non-empty 'workerIp'");
        }
    }
    
    private void validateTesterReachability(String workerIp) throws Exception {
        logger.info("Checking tester reachability at " + workerIp + ":" + config.getTesterPort());
        
        try {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(workerIp, config.getTesterPort()), 5000);
                logger.info("Tester connectivity test passed");
                return;
            }
        } catch (Exception e) {
            String errorMsg = "Tester at " + workerIp + ":" + config.getTesterPort() + 
                            " is not reachable: " + e.getMessage();
            logger.severe(errorMsg);
            throw new IllegalArgumentException(errorMsg, e);
        }
    }
    
    private String generateTaskId(JSONObject request) {
        String data = request.getJSONArray("commits").toString() + "_" +
                     request.getJSONArray("tests").toString();
        return Integer.toHexString(data.hashCode());
    }
    
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody()))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        }
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) 
            throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private void sendJsonResponse(HttpExchange exchange, int statusCode, JSONObject response)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, statusCode, response.toString());
    }

    // Remove containers left from prior runs. Per requirement, perform from Builder only.
    private void cleanupStaleContainers() throws IOException, InterruptedException {
        if (!DockerUtils.isDockerAvailable()) {
            logger.info("Docker not available; skipping container cleanup");
            return;
        }
        // 1) Remove tester debug containers kept alive from previous runs
        removeByNamePrefix("tester_debug_");
        // 2) Prune exited containers for our known images (safe)
        removeExitedByAncestor(config.getDockerBuildImage());
        removeExitedByAncestor(config.getDockerTestImage());
    }

    private void removeByNamePrefix(String prefix) throws IOException, InterruptedException {
        String listCmd = "docker ps -aq --filter name=" + prefix;
        List<String> ids = readCommandOutput(new String[]{"bash", "-lc", listCmd});
        if (!ids.isEmpty()) {
            logger.info("Removing stale tester containers by name prefix '" + prefix + "': " + String.join(",", ids));
            List<String> cmd = new ArrayList<>();
            cmd.add("bash"); cmd.add("-lc");
            cmd.add("docker rm -f " + String.join(" ", ids));
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        }
    }

    private void removeExitedByAncestor(String image) throws IOException, InterruptedException {
        if (image == null || image.trim().isEmpty()) return;
        String listCmd = "docker ps -aq --filter status=exited --filter ancestor=" + image;
        List<String> ids = readCommandOutput(new String[]{"bash", "-lc", listCmd});
        if (!ids.isEmpty()) {
            logger.info("Pruning exited containers for image '" + image + "': " + String.join(",", ids));
            List<String> cmd = new ArrayList<>();
            cmd.add("bash"); cmd.add("-lc");
            cmd.add("docker rm " + String.join(" ", ids));
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        }
    }

    private List<String> readCommandOutput(String[] cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line; while ((line = r.readLine()) != null) { if (!line.trim().isEmpty()) lines.add(line.trim()); }
        }
        p.waitFor();
        return lines;
    }
    
    public static void main(String[] args) {
        try {
            // Setup logging
            LogManager.getLogManager().reset();
            Logger rootLogger = Logger.getLogger("");
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(consoleHandler);
            // File handler for detailed logging
            String logDir = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log";
            new File(logDir).mkdirs();
            FileHandler fileHandler = new FileHandler(logDir + "/builder.log", true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new SimpleFormatter());
            rootLogger.addHandler(fileHandler);

            rootLogger.setLevel(Level.INFO);
            
            // Load configuration
            String configFile = "conf/builder.conf";
            if (args.length > 0) {
                configFile = args[0];
            }
            
            BuilderConfig config = new BuilderConfig(configFile);
            
            // Create and start builder
            Builder builder = new Builder(config);
            builder.start();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                builder.stop();
            }));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start Builder", e);
            System.exit(1);
        }
    }
}
