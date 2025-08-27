package com.navercorp.cubridqa.builder.tester;

import com.sun.net.httpserver.HttpExchange;
import com.navercorp.cubridqa.builder.MultipartHelper;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

public class HttpResponseWriter {
    private static final Logger logger = Logger.getLogger(HttpResponseWriter.class.getName());
    
    public void sendJson(HttpExchange exchange, int statusCode, JSONObject response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendText(exchange, statusCode, response.toString());
    }
    
    public void sendText(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] responseBytes = response.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
    
    public void sendMultipart(HttpExchange exchange, int statusCode, JSONObject jsonResponse, List<Path> logFiles) throws IOException {
        // Prepare files map for multipart sending
        Map<String, Path> files = new HashMap<>();
        for (int i = 0; i < logFiles.size(); i++) {
            Path logFile = logFiles.get(i);
            String fieldName = "log_attempt_" + (i + 1);
            files.put(fieldName, logFile);
        }
        
        // Send multipart response with JSON and log files
        MultipartHelper.sendMultipartResponse(exchange, statusCode, jsonResponse, files);
        logger.info("Sent multipart response with " + files.size() + " log files: " + jsonResponse.toString());
    }
}