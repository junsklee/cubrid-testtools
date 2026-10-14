package com.navercorp.cubridqa.builder.tester;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.navercorp.cubridqa.builder.logs.LogLocator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.logging.Level;

public class LogStreamHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(LogStreamHandler.class.getName());
    
    private final LogLocator logLocator;
    private final HttpResponseWriter responseWriter;
    
    public LogStreamHandler(LogLocator logLocator, HttpResponseWriter responseWriter) {
        this.logLocator = logLocator;
        this.responseWriter = responseWriter;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        String filename = requestPath.substring(requestPath.lastIndexOf('/') + 1);
        
        logger.info("Searching for log file: " + filename);
        
        try {
            Path logFile = logLocator.findLogFile(filename, queryParam(exchange, "requestId"));
            if (logFile != null && Files.exists(logFile)) {
                logger.info("Found log file at: " + logFile.toString());
                
                long fileSize = Files.size(logFile);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(fileSize));
                exchange.sendResponseHeaders(200, fileSize);
                
                try (InputStream fileStream = Files.newInputStream(logFile);
                     OutputStream responseStream = exchange.getResponseBody()) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = fileStream.read(buffer)) != -1) {
                        responseStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    logger.info("Streamed log file: " + filename + " (" + totalBytes + " bytes)");
                }
            } else {
                responseWriter.sendText(exchange, 404, "Log file not found: " + filename);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error serving log file " + filename, e);
            responseWriter.sendText(exchange, 500, "Error serving log file: " + e.getMessage());
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
