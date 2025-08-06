/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.net.*;
import java.util.logging.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;

/**
 * StandaloneBisectService - HTTP service for standalone bisect execution
 * 
 * This service provides an HTTP endpoint for executing bisect operations
 * in standalone mode, where both build and test are performed in a single
 * Docker container.
 */
public class StandaloneBisectService {
    private static final Logger logger = Logger.getLogger(StandaloneBisectService.class.getName());
    
    private final BisectConfig config;
    private final StandaloneBisectExecutor executor;
    private final HttpServer server;
    
    public StandaloneBisectService(BisectConfig config) throws IOException {
        this.config = config;
        this.executor = new StandaloneBisectExecutor(config);
        
        // Create HTTP server
        this.server = HttpServer.create(new InetSocketAddress(config.getListenPort()), 0);
        this.server.createContext("/bisect", new BisectRequestHandler());
        this.server.createContext("/health", new HealthCheckHandler());
        this.server.setExecutor(null); // creates a default executor
    }
    
    /**
     * Start the service
     */
    public void start() throws Exception {
        // Initialize executor
        executor.initialize();
        
        // Start HTTP server
        server.start();
        
        logger.info("=========================================");
        logger.info("Standalone Bisect Service Started");
        logger.info("Port: " + config.getListenPort());
        logger.info("Mode: STANDALONE (Build + Test in single container)");
        logger.info("CUBRID source: " + config.getCubridSrcDir());
        logger.info("Shell TC dir: " + config.getShellTcDir());
        logger.info("=========================================");
    }
    
    /**
     * Stop the service
     */
    public void stop() {
        server.stop(0);
        executor.shutdown();
        logger.info("Standalone Bisect Service stopped");
    }
    
    /**
     * Handler for bisect requests
     */
    private class BisectRequestHandler implements HttpHandler {
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
                
                // Validate request
                validateRequest(request);
                
                logger.info(String.format("Received bisect request: %s...%s",
                    request.getString("suspectedStartCommit"),
                    request.getString("suspectedEndCommit")));
                
                // Execute bisect asynchronously
                executor.executeBisect(request).thenAccept(result -> {
                    try {
                        // Send callback if provided
                        if (request.has("callbackUrl")) {
                            sendCallback(request.getString("callbackUrl"), result);
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to send callback", e);
                    }
                });
                
                // Send accepted response
                JSONObject response = new JSONObject()
                    .put("status", "accepted")
                    .put("mode", "standalone")
                    .put("message", "Bisect request accepted for standalone execution");
                
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
    
    /**
     * Handler for health check
     */
    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JSONObject health = new JSONObject()
                .put("status", "healthy")
                .put("service", "StandaloneBisectService")
                .put("mode", "standalone")
                .put("timestamp", System.currentTimeMillis());
            
            sendJsonResponse(exchange, 200, health);
        }
    }
    
    /**
     * Validate bisect request
     */
    private void validateRequest(JSONObject request) {
        if (!request.has("suspectedStartCommit") || !request.has("suspectedEndCommit")) {
            throw new IllegalArgumentException("Missing required fields: suspectedStartCommit, suspectedEndCommit");
        }
        
        if (!request.has("tests") || request.getJSONArray("tests").length() == 0) {
            throw new IllegalArgumentException("No tests specified");
        }
    }
    
    /**
     * Send callback with results
     */
    private void sendCallback(String callbackUrl, JSONObject result) throws IOException {
        URL url = new URL(callbackUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(result.toString().getBytes("UTF-8"));
            }
            
            int responseCode = conn.getResponseCode();
            logger.info("Callback sent to " + callbackUrl + ", response: " + responseCode);
            
        } finally {
            conn.disconnect();
        }
    }
    
    /**
     * Read request body
     */
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
    
    /**
     * Send response
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String response) 
            throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    /**
     * Send JSON response
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, JSONObject response)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, statusCode, response.toString());
    }
    
    /**
     * Main method to start standalone service
     */
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
            String configFile = "conf/bisect_standalone.conf";
            if (args.length > 0) {
                configFile = args[0];
            }
            
            BisectConfig config = new BisectConfig(configFile);
            
            // Create and start service
            StandaloneBisectService service = new StandaloneBisectService(config);
            service.start();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                service.stop();
            }));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start Standalone Bisect Service", e);
            System.exit(1);
        }
    }
}
