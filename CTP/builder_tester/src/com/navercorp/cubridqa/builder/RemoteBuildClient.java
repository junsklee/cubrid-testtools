package com.navercorp.cubridqa.builder;

import java.io.*;
import java.net.*;
import java.util.logging.*;
import org.json.JSONObject;

/**
 * RemoteBuildClient - Client for triggering builds on remote builder nodes
 */
public class RemoteBuildClient {
    private static final Logger logger = Logger.getLogger(RemoteBuildClient.class.getName());
    private static final int DEFAULT_TIMEOUT_MS = 180 * 60 * 1000; // 3 hours

    /**
     * Trigger a build on a remote builder node
     *
     * @param nodeId The node identifier (e.g., "192.168.1.10:8089")
     * @param commit The commit hash to build
     * @param buildType The build type (debug/release)
     * @param baselineCommit The baseline commit
     * @return JSONObject with build result (status, packagePath, etc.)
     * @throws IOException if the request fails
     */
    public static JSONObject triggerRemoteBuild(String nodeId, String commit, String buildType, String baselineCommit)
            throws IOException {

        // Parse host and port
        String host = nodeId;
        int port = 8089; // Default builder port

        if (nodeId.contains(":")) {
            String[] parts = nodeId.split(":");
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                logger.warning("Invalid port in nodeId: " + nodeId + ", using default 8089");
            }
        }

        // Build URL
        String urlStr = String.format("http://%s:%d/build-single", host, port);
        logger.info(String.format("Triggering remote build on %s for commit %s", nodeId, commit));

        // Prepare request body
        JSONObject requestBody = new JSONObject();
        requestBody.put("commit", commit);
        requestBody.put("buildType", buildType);
        if (baselineCommit != null) {
            requestBody.put("baselineCommit", baselineCommit);
        }

        // Send HTTP POST request
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000); // 30 seconds to connect
            conn.setReadTimeout(DEFAULT_TIMEOUT_MS); // Long timeout for build

            // Write request body
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Read response
            int responseCode = conn.getResponseCode();

            InputStream inputStream;
            if (responseCode >= 200 && responseCode < 300) {
                inputStream = conn.getInputStream();
            } else {
                inputStream = conn.getErrorStream();
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "utf-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            if (responseCode >= 200 && responseCode < 300) {
                JSONObject result = new JSONObject(response.toString());
                logger.info(String.format("Remote build succeeded on %s: %s", nodeId, result.optString("message", "OK")));
                return result;
            } else {
                String errorMsg = String.format("Remote build failed on %s with status %d: %s",
                    nodeId, responseCode, response.toString());
                logger.severe(errorMsg);
                throw new IOException(errorMsg);
            }

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Check if a remote builder node is reachable
     *
     * @param nodeId The node identifier
     * @return true if reachable
     */
    public static boolean isReachable(String nodeId) {
        String host = nodeId;
        int port = 8089;

        if (nodeId.contains(":")) {
            String[] parts = nodeId.split(":");
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                logger.warning("Invalid port in nodeId: " + nodeId);
                return false;
            }
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            return true;
        } catch (IOException e) {
            logger.warning("Node " + nodeId + " is not reachable: " + e.getMessage());
            return false;
        }
    }
}
