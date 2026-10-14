package com.navercorp.cubridqa.builder.tester;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
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
}
