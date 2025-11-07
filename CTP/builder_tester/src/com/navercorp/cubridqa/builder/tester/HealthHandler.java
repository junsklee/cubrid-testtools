package com.navercorp.cubridqa.builder.tester;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.navercorp.cubridqa.builder.BuilderConfig;
import org.json.JSONObject;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.logging.Level;

public class HealthHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(HealthHandler.class.getName());
    
    private final HttpResponseWriter responseWriter;
    private final BuilderConfig config;
    
    public HealthHandler(BuilderConfig config, HttpResponseWriter responseWriter) {
        this.config = config;
        this.responseWriter = responseWriter;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            JSONObject healthStatus = new JSONObject()
                .put("status", "healthy")
                .put("timestamp", System.currentTimeMillis())
                .put("maxConcurrentTests", Math.max(1, config.getMaxConcurrentTests()))
                .put("testerPort", config.getTesterPort());
                
            responseWriter.sendJson(exchange, 200, healthStatus);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in health check: " + e.getMessage(), e);
            responseWriter.sendJson(exchange, 500, 
                new JSONObject().put("status", "error").put("message", e.getMessage()));
        }
    }
}
