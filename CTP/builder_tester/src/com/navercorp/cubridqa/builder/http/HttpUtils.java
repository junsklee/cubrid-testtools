package com.navercorp.cubridqa.builder.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class HttpUtils {
    
    public static String readRequestBody(HttpExchange exchange) throws IOException {
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
    
    /**
     * Determines whether an IOException likely indicates the client disconnected
     * (e.g., broken pipe, connection reset) while we were writing the response.
     * Such cases should be logged but not treated as test execution failures.
     */
    public static boolean isClientAbort(IOException ioe) {
        Throwable t = ioe;
        while (t != null) {
            if (t instanceof java.net.SocketException) {
                String m = t.getMessage();
                if (m != null && (m.contains("Broken pipe") || m.contains("Connection reset") || m.contains("reset by peer"))) {
                    return true;
                }
            }
            if (t instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }
            t = t.getCause();
        }
        String msg = ioe.getMessage();
        return msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset") || msg.contains("reset by peer"));
    }
    
    public static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.getBytes("UTF-8").length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes("UTF-8"));
        }
    }
}