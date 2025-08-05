/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.*;
import org.json.JSONObject;

/**
 * BisectConsumer - Receives test requests from producer and executes tests
 * 
 * This service runs on test nodes and executes shell tests with provided CUBRID builds.
 * It extracts the build, sets up the environment, runs the test, and returns pass/fail status.
 */
public class BisectConsumer {
    private static final Logger logger = Logger.getLogger(BisectConsumer.class.getName());
    
    private final BisectConfig config;
    private final HttpServer server;
    
    public BisectConsumer(BisectConfig config) throws IOException {
        this.config = config;
        
        // Create work directory if it doesn't exist
        File workDir = new File(config.getWorkDir());
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        
        // Create HTTP server
        this.server = HttpServer.create(new InetSocketAddress(config.getConsumerPort()), 0);
        this.server.createContext("/test", new TestRequestHandler());
        this.server.createContext("/health", new HealthCheckHandler());
        this.server.setExecutor(null); // creates a default executor
    }
    
    public void start() {
        server.start();
        logger.info("BisectConsumer started on port " + config.getConsumerPort());
        logger.info("Work directory: " + config.getWorkDir());
    }
    
    public void stop() {
        server.stop(0);
        logger.info("BisectConsumer stopped");
    }
    
    private class TestRequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                // Read request body
                String requestBody = readRequestBody(exchange);
                JSONObject request = new JSONObject(requestBody);
                
                logger.info("Received test request for: " + request.getString("testPath"));
                
                // Run test
                JSONObject result = runTest(request);
                
                // Send response
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 200, result.toString());
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Error processing test request", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 500, error.toString());
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
                    .put("service", "BisectConsumer")
                    .put("timestamp", System.currentTimeMillis())
                    .put("workDir", config.getWorkDir());
                
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 200, healthResponse.toString());
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in health check", e);
                JSONObject error = new JSONObject()
                    .put("status", "error")
                    .put("message", e.getMessage());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 500, error.toString());
            }
        }
    }
    
    private JSONObject runTest(JSONObject request) throws Exception {
        // Create work directory for this test
        Path workDir = Files.createTempDirectory(Paths.get(config.getWorkDir()), "test_");
        logger.info("Working directory: " + workDir);
        
        try {
            // Extract request parameters
            String buildPackage = request.getString("buildPackage");
            String testDir = request.getString("testDir");
            String testScript = request.getString("testScript");
            String testName = request.getString("testName");
            
            // Download build package if it's a URL
            if (buildPackage.startsWith("http://") || buildPackage.startsWith("https://")) {
                Path localPackage = workDir.resolve("cubrid.tar.gz");
                logger.info("Downloading build from: " + buildPackage);
                downloadFile(buildPackage, localPackage);
                buildPackage = localPackage.toString();
            }
            
            // Extract and install CUBRID
            Path installDir = workDir.resolve("cubrid");
            Files.createDirectory(installDir);
            logger.info("Extracting CUBRID build to: " + installDir);
            extractTarGz(buildPackage, installDir);
            
            // Set environment for CUBRID
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("CUBRID", installDir.toString());
            env.put("CUBRID_DATABASES", installDir.resolve("databases").toString());
            env.put("PATH", installDir.resolve("bin") + ":" + System.getenv("PATH"));
            env.put("LD_LIBRARY_PATH", installDir.resolve("lib") + ":" + 
                    System.getenv().getOrDefault("LD_LIBRARY_PATH", ""));
            
            // Create databases directory
            Files.createDirectories(installDir.resolve("databases"));
            
            // Run the test
            String resultFile = testName + ".result";
            logger.info("Running test: " + testScript + " in directory: " + testDir);
            
            // Change to test directory
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(new File(testDir));
            pb.environment().clear();
            pb.environment().putAll(env);
            
            // Remove old result file if exists
            File resultFileObj = new File(testDir, resultFile);
            if (resultFileObj.exists()) {
                resultFileObj.delete();
            }
            
            // Run test script
            pb.command("bash", testScript);
            Process process = pb.start();
            
            // Capture output for debugging
            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "OUTPUT");
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), "ERROR");
            outputGobbler.start();
            errorGobbler.start();
            
            // Wait for completion
            boolean completed = process.waitFor(30, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                throw new RuntimeException("Test timeout after 30 minutes");
            }
            
            int exitCode = process.exitValue();
            logger.info("Test script exited with code: " + exitCode);
            
            // Check result file
            if (resultFileObj.exists()) {
                String resultContent = new String(Files.readAllBytes(resultFileObj.toPath()));
                logger.info("Result file content: " + resultContent.trim());
                
                if (resultContent.contains("NOK")) {
                    logger.info("Test " + testName + " FAILED");
                    return new JSONObject()
                        .put("status", "fail")
                        .put("test", testName);
                } else {
                    logger.info("Test " + testName + " PASSED");
                    return new JSONObject()
                        .put("status", "pass")
                        .put("test", testName);
                }
            } else {
                logger.warning("No result file generated for test " + testName);
                return new JSONObject()
                    .put("status", "error")
                    .put("message", "No result file generated");
            }
            
        } finally {
            // Cleanup
            deleteDirectory(workDir.toFile());
        }
    }
    
    private void downloadFile(String urlString, Path destination) throws IOException {
        URL url = new URL(urlString);
        try (InputStream in = url.openStream();
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }
    
    private void extractTarGz(String tarGzFile, Path destination) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "tar", "xzf", tarGzFile, "-C", destination.toString()
        );
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to extract tar.gz file, exit code: " + exitCode);
        }
    }
    
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), "UTF-8"))) {
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
    
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
    
    /**
     * Stream gobbler to consume process output
     */
    private static class StreamGobbler extends Thread {
        private final InputStream is;
        private final String type;
        
        StreamGobbler(InputStream is, String type) {
            this.is = is;
            this.type = type;
        }
        
        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logger.fine(type + ": " + line);
                }
            } catch (IOException e) {
                logger.warning("Error reading " + type + " stream: " + e.getMessage());
            }
        }
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
            rootLogger.setLevel(Level.INFO);
            
            // Load configuration
            String configFile = "conf/bisect_consumer.conf";
            if (args.length > 0) {
                configFile = args[0];
            }
            
            // Consumer uses same config class but different file
            BisectConfig config = new BisectConfig(configFile);
            
            // Create and start consumer
            BisectConsumer consumer = new BisectConsumer(config);
            consumer.start();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                consumer.stop();
            }));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start BisectConsumer", e);
            System.exit(1);
        }
    }
}
