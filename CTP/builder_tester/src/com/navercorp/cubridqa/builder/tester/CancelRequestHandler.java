package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.docker.DockerUtils;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.http.HttpUtils;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles POST /cancel-request to force-stop tester containers for a request.
 */
public class CancelRequestHandler implements HttpHandler {
    private static final Logger logger = Logger.getLogger(CancelRequestHandler.class.getName());
    private final HttpResponseWriter responseWriter = new HttpResponseWriter();
    private final ShellTcSync shellTcSync;

    public CancelRequestHandler(ShellTcSync shellTcSync) {
        this.shellTcSync = shellTcSync;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            HttpUtils.sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String requestBody = HttpUtils.readRequestBody(exchange);
            JSONObject request = new JSONObject(requestBody);
            String requestId = request.optString("requestId", "").trim();
            boolean killAll = request.optBoolean("killAll", false);

            if (requestId.isEmpty() && !killAll) {
                responseWriter.sendJson(exchange, 400, new JSONObject()
                    .put("status", "error")
                    .put("message", "Missing requestId"));
                return;
            }

            if (!requestId.isEmpty()) {
                CancelledRequests.cancel(requestId);
                logger.info("Marked request as cancelled: " + requestId);
                deleteRequestLogs(requestId);
                if (shellTcSync != null) {
                    shellTcSync.cleanupRequestWorkspace(logger, requestId);
                }
            }

            if (!DockerUtils.isDockerAvailable()) {
                responseWriter.sendJson(exchange, 200, new JSONObject()
                    .put("status", "skipped")
                    .put("reason", "docker_unavailable")
                    .put("requestId", requestId));
                return;
            }

            String safeRequestId = requestId.replaceAll("[^a-zA-Z0-9_.-]", "_");
            List<String> ids = new ArrayList<>();
            if (killAll) {
                ids.addAll(listContainers("tester_"));
            } else if (!safeRequestId.isEmpty()) {
                ids.addAll(listContainers(safeRequestId));
            }

            int killed = 0;
            if (!ids.isEmpty()) {
                List<String> runningIds = new ArrayList<>();
                if (killAll) {
                    runningIds.addAll(listRunningContainers("tester_"));
                } else if (!safeRequestId.isEmpty()) {
                    runningIds.addAll(listRunningContainers(safeRequestId));
                }
                killRunningContainers(runningIds);
                killed = removeContainers(ids);
            }

            JSONObject response = new JSONObject()
                .put("status", "success")
                .put("requestId", requestId)
                .put("killAll", killAll)
                .put("killed", killed);
            responseWriter.sendJson(exchange, 200, response);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error cancelling request", e);
            JSONObject response = new JSONObject()
                .put("status", "error")
                .put("message", e.getMessage());
            responseWriter.sendJson(exchange, 500, response);
        }
    }

    private List<String> listContainers(String nameFilter) throws IOException, InterruptedException {
        CommandResult result = runCommand(
            Arrays.asList("docker", "ps", "-aq", "--filter", "name=" + nameFilter),
            "list containers: " + nameFilter
        );
        return parseContainerIds(result);
    }

    private List<String> listRunningContainers(String nameFilter) throws IOException, InterruptedException {
        CommandResult result = runCommand(
            Arrays.asList("docker", "ps", "-q", "--filter", "name=" + nameFilter),
            "list running containers: " + nameFilter
        );
        return parseContainerIds(result);
    }

    private int removeContainers(List<String> ids) throws IOException, InterruptedException {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("rm");
        cmd.add("-f");
        cmd.addAll(ids);
        runCommand(cmd, "remove containers");
        return ids.size();
    }

    private void deleteRequestLogs(String requestId) {
        try {
            Path logDir = RequestLogManager.getInstance().getRequestLogDirectory(requestId);
            if (logDir != null) {
                SafeIo.deleteDirectoryWithPrivileges(logDir.toFile(), logger);
            }
        } catch (Exception e) {
            logger.warning("Failed to delete request logs for " + requestId + ": " + e.getMessage());
        }
    }

    private void killRunningContainers(List<String> ids) throws IOException, InterruptedException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("kill");
        cmd.addAll(ids);
        runCommand(cmd, "kill containers");
    }

    private List<String> parseContainerIds(CommandResult result) {
        List<String> ids = new ArrayList<>();
        if (result.exitCode != 0 || result.output == null || result.output.trim().isEmpty()) {
            return ids;
        }
        String[] lines = result.output.split("\\R");
        for (String line : lines) {
            String id = line.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private CommandResult runCommand(List<String> cmd, String action) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            logger.warning("Docker command failed (" + action + "), exit=" + exitCode + ", output=" + output.toString().trim());
        }
        return new CommandResult(exitCode, output.toString());
    }

    private static class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
