/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder.report;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * ReportHandler - Handles test result callbacks and generates interactive HTML reports
 * 
 * This handler receives test results from the Builder service, saves them to the
 * log directory, and generates interactive HTML reports for visualization.
 */
public class ReportHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(ReportHandler.class.getName());
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    
    private final String logBaseDir;
    private final String reportTemplate;
    
    public ReportHandler(String logBaseDir) throws IOException {
        this.logBaseDir = logBaseDir;
        
        // Ensure log directories exist
        Files.createDirectories(Paths.get(logBaseDir, "requests"));
        
        // Load the report template
        this.reportTemplate = loadReportTemplate();
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        
        if ("POST".equals(method)) {
            handleCallback(exchange);
        } else if ("GET".equals(method)) {
            handleReportView(exchange);
        } else {
            sendResponse(exchange, 405, "Method not allowed");
        }
    }
    
    private void handleCallback(HttpExchange exchange) throws IOException {
        try {
            // Read the request body
            String requestBody = readRequestBody(exchange);
            JSONObject data = new JSONObject(requestBody);
            
            // Prefer incoming request/task ID so reports land in the same request directory
            String requestId = data.optString("requestId", data.optString("taskId", ""));
            if (requestId == null || requestId.trim().isEmpty()) {
                requestId = "req_" + System.currentTimeMillis() + "_" +
                            UUID.randomUUID().toString().substring(0, 8);
            }
            Path requestDir = Paths.get(logBaseDir, "requests", requestId);
            Files.createDirectories(requestDir);
            
            // Save the raw JSON data
            Path jsonPath = requestDir.resolve("results.json");
            Files.write(jsonPath, requestBody.getBytes());
            logger.info("Saved results to: " + jsonPath);
            
            // Generate the HTML report
            String reportHtml = generateReport(data, requestId);
            Path reportPath = requestDir.resolve("report.html");
            Files.write(reportPath, reportHtml.getBytes());
            logger.info("Generated report at: " + reportPath);
            
            // Return the HTML report as response
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            sendResponse(exchange, 200, reportHtml);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error handling callback", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }
    
    private void handleReportView(HttpExchange exchange) throws IOException {
        try {
            // Parse query parameters to get request ID
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.startsWith("id=")) {
                // List available reports
                sendReportList(exchange);
                return;
            }
            
            String requestId = query.substring(3);
            Path reportPath = Paths.get(logBaseDir, "requests", requestId, "report.html");
            
            if (Files.exists(reportPath)) {
                String reportHtml = new String(Files.readAllBytes(reportPath));
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                sendResponse(exchange, 200, reportHtml);
            } else {
                sendErrorResponse(exchange, 404, "Report not found: " + requestId);
            }
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error viewing report", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }
    
    private void sendReportList(HttpExchange exchange) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<title>Test Reports</title>");
        html.append("<style>");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; ");
        html.append("background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
        html.append("color: white; padding: 2rem; min-height: 100vh; margin: 0; }");
        html.append(".container { max-width: 1200px; margin: 0 auto; }");
        html.append("h1 { font-size: 2.5rem; margin-bottom: 2rem; }");
        html.append(".report-list { background: rgba(255,255,255,0.1); ");
        html.append("backdrop-filter: blur(10px); border-radius: 1rem; padding: 2rem; }");
        html.append(".report-item { display: flex; justify-content: space-between; ");
        html.append("align-items: center; padding: 1rem; margin-bottom: 1rem; ");
        html.append("background: rgba(255,255,255,0.05); border-radius: 0.5rem; ");
        html.append("transition: all 0.3s ease; }");
        html.append(".report-item:hover { background: rgba(255,255,255,0.15); transform: translateX(10px); }");
        html.append("a { color: white; text-decoration: none; font-weight: 500; }");
        html.append(".timestamp { opacity: 0.8; font-size: 0.9rem; }");
        html.append("</style></head><body>");
        html.append("<div class='container'>");
        html.append("<h1>Test Report Viewer</h1>");
        html.append("<div class='report-list'>");
        
        // List all report directories
        Path requestsDir = Paths.get(logBaseDir, "requests");
        if (Files.exists(requestsDir)) {
            Files.list(requestsDir)
                .filter(Files::isDirectory)
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                .limit(50)
                .forEach(dir -> {
                    String dirName = dir.getFileName().toString();
                    Path reportFile = dir.resolve("report.html");
                    if (Files.exists(reportFile)) {
                        html.append("<div class='report-item'>");
                        html.append("<a href='/report?id=").append(dirName).append("'>");
                        html.append("📁 ").append(dirName).append("</a>");
                        try {
                            html.append("<span class='timestamp'>")
                                .append(Files.getLastModifiedTime(reportFile).toString())
                                .append("</span>");
                        } catch (IOException e) {
                            // Ignore
                        }
                        html.append("</div>");
                    }
                });
        }
        
        html.append("</div></div></body></html>");
        
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        sendResponse(exchange, 200, html.toString());
    }
    
    private String generateReport(JSONObject data, String requestId) {
        String template = reportTemplate;
        
        // Replace placeholders in template
        template = template.replace("{{REQUEST_ID}}", requestId);
        template = template.replace("{{TIMESTAMP}}", dateFormat.format(new Date()));
        template = template.replace("{{RESULTS_JSON}}", data.toString());
        
        return template;
    }
    
    private String loadReportTemplate() throws IOException {
        // Prefer loading from the classpath (packaged resource)
        try {
            return ReportTemplate.loadFromClasspath();
        } catch (Exception ignore) {
            // Fallback to external resources directory if present
            Path templatePath = Paths.get(System.getProperty("user.dir"),
                                          "resources", "report-template.html");
            if (Files.exists(templatePath)) {
                return new String(Files.readAllBytes(templatePath));
            }
            throw new IOException("Report template not found in classpath or resources directory");
        }
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
    
    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private void sendErrorResponse(HttpExchange exchange, int code, String message) throws IOException {
        JSONObject error = new JSONObject();
        error.put("error", message);
        error.put("code", code);
        
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, code, error.toString());
    }
}
