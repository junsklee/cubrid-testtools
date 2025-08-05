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
            logger.info("Received HTTP " + exchange.getRequestMethod() + " request to " + exchange.getRequestURI());
            
            if (!"POST".equals(exchange.getRequestMethod())) {
                logger.warning("Rejected non-POST request: " + exchange.getRequestMethod());
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                // Read request body
                String requestBody = readRequestBody(exchange);
                logger.info("Request body: " + requestBody);
                
                JSONObject request = new JSONObject(requestBody);
                
                logger.info("Received test request for: " + request.getString("testPath"));
                logger.info("Build package: " + request.getString("buildPackage"));
                
                // Run test
                JSONObject result = runTest(request);
                
                // Send response
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 200, result.toString());
                logger.info("Sent response: " + result.toString());
                
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
            
            logger.info("Test parameters:");
            logger.info("  Build package: " + buildPackage);
            logger.info("  Test directory: " + testDir);
            logger.info("  Test script: " + testScript);
            logger.info("  Test name: " + testName);
            
            // Install CUBRID using the existing installation script
            logger.info("Installing CUBRID build using run_cubrid_install script");
            Path actualInstallDir = installCubridUsingScript(buildPackage, workDir);
            
            // Set environment for CUBRID (using the actual installation directory)
            Map<String, String> env = new HashMap<>(System.getenv());
            env.put("CUBRID", actualInstallDir.toString());
            env.put("CUBRID_DATABASES", actualInstallDir.resolve("databases").toString());
            env.put("PATH", actualInstallDir.resolve("bin") + ":" + System.getenv("PATH"));
            env.put("LD_LIBRARY_PATH", actualInstallDir.resolve("lib") + ":" + 
                    System.getenv().getOrDefault("LD_LIBRARY_PATH", ""));
            
            // Load CUBRID environment
            env.put("CUBRID_LANG", "en_US");
            
            // Ensure databases directory exists
            if (!Files.exists(actualInstallDir.resolve("databases"))) {
                Files.createDirectories(actualInstallDir.resolve("databases"));
            }
            
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
    
    /**
     * Install CUBRID using the existing run_cubrid_install script
     * @return Path to the actual CUBRID installation directory
     */
    private Path installCubridUsingScript(String buildPackage, Path workDir) throws IOException, InterruptedException {
        logger.info("=====Installing CUBRID using run_cubrid_install script=====");
        
        // Prepare environment for installation
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(workDir.toFile());
        
        // Build command: run_cubrid_install [url]
        pb.command("run_cubrid_install", buildPackage);
        
        // Set up environment variables
        Map<String, String> env = pb.environment();
        
        // Make sure CTP_HOME is set (the script depends on it)
        if (env.get("CTP_HOME") == null) {
            // Try to find CTP_HOME relative to the script location
            String ctpHome = findCTPHome();
            if (ctpHome != null) {
                env.put("CTP_HOME", ctpHome);
                logger.info("Set CTP_HOME to: " + ctpHome);
            }
        }
        
        // Ensure PATH includes the script directory
        String currentPath = env.get("PATH");
        String scriptPath = env.get("CTP_HOME") + "/common/script";
        if (currentPath != null && !currentPath.contains(scriptPath)) {
            env.put("PATH", scriptPath + ":" + currentPath);
        }
        
        logger.info("Executing: " + String.join(" ", pb.command()));
        logger.info("Working directory: " + workDir);
        
        // Start the installation process
        Process process = pb.start();
        
        // Capture and log output
        StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), "INSTALL");
        StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), "INSTALL-ERROR");
        outputGobbler.start();
        errorGobbler.start();
        
        // Wait for completion with timeout
        boolean completed = process.waitFor(15, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("CUBRID installation timeout after 15 minutes");
        }
        
        int exitCode = process.exitValue();
        logger.info("Installation script exited with code: " + exitCode);
        
        if (exitCode != 0) {
            throw new IOException("CUBRID installation failed with exit code: " + exitCode);
        }
        
        logger.info("CUBRID installation completed successfully");
        
        // Return the actual installation directory (script installs to $HOME/CUBRID by default)
        Path cubridHome = Paths.get(System.getProperty("user.home"), "CUBRID");
        if (!Files.exists(cubridHome)) {
            // Fallback: check if it was installed in the work directory
            cubridHome = workDir.resolve("CUBRID");
            if (!Files.exists(cubridHome)) {
                throw new IOException("Could not find CUBRID installation directory after script execution");
            }
        }
        
        logger.info("CUBRID installed at: " + cubridHome);
        return cubridHome;
    }
    
    /**
     * Find CTP_HOME directory by searching upward from current location
     */
    private String findCTPHome() {
        // Try environment variable first
        String ctpHome = System.getenv("CTP_HOME");
        if (ctpHome != null && new File(ctpHome).exists()) {
            return ctpHome;
        }
        
        // Try to find it relative to current working directory
        File currentDir = new File(".").getAbsoluteFile();
        while (currentDir != null) {
            File commonScript = new File(currentDir, "common/script/run_cubrid_install");
            if (commonScript.exists()) {
                return currentDir.getAbsolutePath();
            }
            currentDir = currentDir.getParentFile();
        }
        
        // Default fallback
        logger.warning("Could not find CTP_HOME, using default assumption");
        return "/home/qahome/cubrid-testtools/CTP";
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
