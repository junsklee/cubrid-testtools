/**
 * Copyright (c) 2025, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import com.sun.net.httpserver.*;
import org.json.JSONObject;

/**
 * Helper class for handling multipart/form-data HTTP requests and responses
 */
public class MultipartHelper {
    private static final Logger logger = Logger.getLogger(MultipartHelper.class.getName());
    private static final String BOUNDARY_PREFIX = "----FormBoundary";
    
    /**
     * Generate a random boundary string for multipart content
     */
    public static String generateBoundary() {
        return BOUNDARY_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * Send a multipart response with JSON and file attachments
     * 
     * @param exchange The HTTP exchange object
     * @param statusCode HTTP status code
     * @param jsonResponse The JSON response object
     * @param files Map of field names to file paths to attach
     */
    public static void sendMultipartResponse(HttpExchange exchange, int statusCode, 
                                            JSONObject jsonResponse, Map<String, Path> files) throws IOException {
        String boundary = generateBoundary();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // Write JSON part
        writeMultipartField(baos, boundary, "response", "application/json", null, 
                          jsonResponse.toString().getBytes("UTF-8"));
        
        // Write file parts
        if (files != null) {
            for (Map.Entry<String, Path> entry : files.entrySet()) {
                String fieldName = entry.getKey();
                Path filePath = entry.getValue();
                
                if (Files.exists(filePath)) {
                    try {
                        byte[] fileContent = Files.readAllBytes(filePath);
                        String fileName = filePath.getFileName().toString();
                        writeMultipartField(baos, boundary, fieldName, "text/plain", fileName, fileContent);
                    } catch (IOException e) {
                        logger.warning("Failed to read file for multipart: " + filePath + " - " + e.getMessage());
                    }
                }
            }
        }
        
        // Write final boundary
        baos.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        
        byte[] responseBytes = baos.toByteArray();
        
        // Send response
        exchange.getResponseHeaders().set("Content-Type", "multipart/form-data; boundary=" + boundary);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
    
    /**
     * Write a single multipart field
     */
    private static void writeMultipartField(ByteArrayOutputStream baos, String boundary,
                                           String fieldName, String contentType, String fileName,
                                           byte[] content) throws IOException {
        // Boundary
        baos.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        
        // Content-Disposition header
        String disposition = "Content-Disposition: form-data; name=\"" + fieldName + "\"";
        if (fileName != null) {
            disposition += "; filename=\"" + fileName + "\"";
        }
        baos.write((disposition + "\r\n").getBytes("UTF-8"));
        
        // Content-Type header
        if (contentType != null) {
            baos.write(("Content-Type: " + contentType + "\r\n").getBytes("UTF-8"));
        }
        
        // Empty line before content
        baos.write("\r\n".getBytes("UTF-8"));
        
        // Content
        baos.write(content);
        
        // Line break after content
        baos.write("\r\n".getBytes("UTF-8"));
    }
    
    /**
     * Parse multipart request and extract parts
     * 
     * @param exchange The HTTP exchange
     * @return A map containing the parsed parts
     */
    public static MultipartRequest parseMultipartRequest(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            return null;
        }
        
        // Extract boundary from content type
        String boundary = null;
        String[] parts = contentType.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                boundary = part.substring(9);
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                break;
            }
        }
        
        if (boundary == null) {
            throw new IOException("No boundary found in multipart request");
        }
        
        // Read request body
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        }
        
        byte[] requestBytes = baos.toByteArray();
        return parseMultipartData(requestBytes, boundary);
    }
    
    /**
     * Parse multipart data
     */
    private static MultipartRequest parseMultipartData(byte[] data, String boundary) throws IOException {
        MultipartRequest request = new MultipartRequest();
        String boundaryDelimiter = "--" + boundary;
        String finalBoundary = "--" + boundary + "--";
        
        // Convert to string for easier parsing (assuming UTF-8 for headers)
        String dataStr = new String(data, "ISO-8859-1");
        String[] parts = dataStr.split(boundaryDelimiter);
        
        for (String part : parts) {
            if (part.isEmpty() || part.equals("--\r\n") || part.equals("--")) {
                continue;
            }
            
            // Find the double CRLF that separates headers from content
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd == -1) {
                continue;
            }
            
            String headers = part.substring(0, headerEnd);
            String contentStr = part.substring(headerEnd + 4);
            
            // Remove trailing CRLF
            if (contentStr.endsWith("\r\n")) {
                contentStr = contentStr.substring(0, contentStr.length() - 2);
            }
            
            // Parse headers
            String fieldName = null;
            String fileName = null;
            String contentType = null;
            
            String[] headerLines = headers.split("\r\n");
            for (String header : headerLines) {
                if (header.toLowerCase().startsWith("content-disposition:")) {
                    // Parse Content-Disposition header
                    String[] dispositionParts = header.split(";");
                    for (String disPart : dispositionParts) {
                        disPart = disPart.trim();
                        if (disPart.startsWith("name=")) {
                            fieldName = extractQuotedValue(disPart.substring(5));
                        } else if (disPart.startsWith("filename=")) {
                            fileName = extractQuotedValue(disPart.substring(9));
                        }
                    }
                } else if (header.toLowerCase().startsWith("content-type:")) {
                    contentType = header.substring(13).trim();
                }
            }
            
            if (fieldName != null) {
                if (fileName != null) {
                    // It's a file
                    request.addFile(fieldName, fileName, contentStr.getBytes("ISO-8859-1"));
                } else {
                    // It's a regular field
                    request.addField(fieldName, contentStr);
                }
            }
        }
        
        return request;
    }
    
    /**
     * Extract value from quoted string
     */
    private static String extractQuotedValue(String value) {
        value = value.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
    
    /**
     * Container class for multipart request data
     */
    public static class MultipartRequest {
        private Map<String, String> fields = new HashMap<>();
        private Map<String, FileData> files = new HashMap<>();
        
        public void addField(String name, String value) {
            fields.put(name, value);
        }
        
        public void addFile(String fieldName, String fileName, byte[] content) {
            files.put(fieldName, new FileData(fileName, content));
        }
        
        public String getField(String name) {
            return fields.get(name);
        }
        
        public FileData getFile(String name) {
            return files.get(name);
        }
        
        public Map<String, String> getFields() {
            return fields;
        }
        
        public Map<String, FileData> getFiles() {
            return files;
        }
        
        public static class FileData {
            public final String fileName;
            public final byte[] content;
            
            public FileData(String fileName, byte[] content) {
                this.fileName = fileName;
                this.content = content;
            }
        }
    }
}
