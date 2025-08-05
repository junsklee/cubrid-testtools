/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * BisectProducer - Receives bisect requests and coordinates the bisect process
 * 
 * This service receives HTTP requests to find the first failing commit for shell tests
 * using git bisect. It builds CUBRID at each bisect step and sends tests to consumer nodes.
 */
public class BisectProducer {
    private static final Logger logger = Logger.getLogger(BisectProducer.class.getName());
    
    private final BisectConfig config;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, BisectTask> activeTasks;
    
    public BisectProducer(BisectConfig config) throws IOException {
        this.config = config;
        this.executor = Executors.newFixedThreadPool(config.getMaxConcurrentBisects());
        this.activeTasks = new ConcurrentHashMap<>();
        
        // Create HTTP server
        this.server = HttpServer.create(new InetSocketAddress(config.getListenPort()), 0);
        this.server.createContext("/bisect", new BisectRequestHandler());
        this.server.setExecutor(null); // creates a default executor
    }
    
    public void start() {
        server.start();
        logger.info("BisectProducer started on port " + config.getListenPort());
        logger.info("CUBRID source: " + config.getCubridSrcDir());
        logger.info("Shell TC dir: " + config.getShellTcDir());
    }
    
    public void stop() {
        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
    
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
                
                // Log request
                logger.info(String.format("Received bisect request: %s...%s (suspected commit range)",
                    request.getString("suspectedStartCommit"),
                    request.getString("suspectedEndCommit")));
                
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
                BisectTask task = new BisectTask(taskId, request, config);
                activeTasks.put(taskId, task);
                
                CompletableFuture.runAsync(() -> {
                    try {
                        task.run();
                    } finally {
                        activeTasks.remove(taskId);
                    }
                }, executor);
                
                // Send accepted response
                JSONObject response = new JSONObject()
                    .put("status", "accepted")
                    .put("taskId", taskId)
                    .put("message", "Bisect request received and processing");
                
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
    
    private void validateRequest(JSONObject request) throws IllegalArgumentException {
        // Required fields
        if (!request.has("suspectedStartCommit") || !request.has("suspectedEndCommit")) {
            throw new IllegalArgumentException("Missing required fields: suspectedStartCommit, suspectedEndCommit");
        }
        
        if (!request.has("tests") || request.getJSONArray("tests").length() == 0) {
            throw new IllegalArgumentException("No tests specified");
        }
        
        if (!request.has("callbackUrl")) {
            throw new IllegalArgumentException("Missing callbackUrl");
        }
    }
    
    private String generateTaskId(JSONObject request) {
        String data = request.getString("suspectedStartCommit") + "_" +
                     request.getString("suspectedEndCommit") + "_" +
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
            String configFile = "conf/bisect_producer.conf";
            if (args.length > 0) {
                configFile = args[0];
            }
            
            BisectConfig config = new BisectConfig(configFile);
            
            // Create and start producer
            BisectProducer producer = new BisectProducer(config);
            producer.start();
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                producer.stop();
            }));
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start BisectProducer", e);
            System.exit(1);
        }
    }
}
