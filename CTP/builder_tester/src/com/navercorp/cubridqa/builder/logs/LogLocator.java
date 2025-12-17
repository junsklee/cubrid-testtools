package com.navercorp.cubridqa.builder.logs;

import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LogLocator {
    private static final Logger logger = Logger.getLogger(LogLocator.class.getName());
    private static final int CACHE_MAX_ENTRIES = 2048;
    private static final int RECENT_REQUESTS_SCAN_LIMIT = 60;
    private static final ConcurrentHashMap<String, Path> CACHE = new ConcurrentHashMap<>();
    
    public Path findLogFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return null;
        }

        Path cached = CACHE.get(filename);
        if (cached != null) {
            if (Files.exists(cached)) {
                return cached;
            }
            CACHE.remove(filename, cached);
        }
        
        // Try to get current request context first
        String requestId = RequestContext.getRequestId();
        if (requestId != null) {
            try {
                RequestLogManager logManager = RequestLogManager.getInstance();
                Path requestLogDir = logManager.getRequestLogDirectory(requestId);
                if (requestLogDir != null) {
                    // Check in tests subdirectory
                    Path testsDir = requestLogDir.resolve("tests");
                    Path logFile = testsDir.resolve(filename);
                    if (Files.exists(logFile)) {
                        cachePut(filename, logFile);
                        return logFile;
                    }
                    
                    // Check in request root directory
                    logFile = requestLogDir.resolve(filename);
                    if (Files.exists(logFile)) {
                        cachePut(filename, logFile);
                        return logFile;
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to use RequestLogManager for log lookup: " + e.getMessage());
            }
        }
        
        // Fallback: search in common log locations
        List<Path> searchPaths = new ArrayList<>();
        String projectRoot = System.getProperty("tester.project.root");
        if (projectRoot != null && !projectRoot.trim().isEmpty()) {
            searchPaths.add(Paths.get(projectRoot, "log", "requests"));
        }
        searchPaths.add(Paths.get(System.getProperty("user.home"), "cubrid-testtools", "CTP", "builder_tester", "log", "requests"));

        for (Path basePath : searchPaths) {
            try {
                if (Files.exists(basePath)) {
                    // Fast path: scan most recent request directories first.
                    Path recentMatch = scanRecentRequests(basePath, filename);
                    if (recentMatch != null) {
                        cachePut(filename, recentMatch);
                        return recentMatch;
                    }

                    // Bounded fallback: shallow find under requests root.
                    Path found = findNewestUnder(basePath, filename);
                    if (found != null) {
                        cachePut(filename, found);
                        return found;
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to search in path " + basePath + ": " + e.getMessage());
            }
        }
        
        return null;
    }

    private static void cachePut(String filename, Path path) {
        if (filename == null || filename.trim().isEmpty() || path == null) {
            return;
        }
        CACHE.put(filename, path);
        if (CACHE.size() > CACHE_MAX_ENTRIES) {
            CACHE.clear();
        }
    }

    private static Path scanRecentRequests(Path requestsRoot, String filename) {
        List<Path> requestDirs = new ArrayList<>();
        try (Stream<Path> list = Files.list(requestsRoot)) {
            requestDirs = list
                .filter(Files::isDirectory)
                .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception ignored) {
            return null;
        }

        requestDirs.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());

        int limit = Math.min(RECENT_REQUESTS_SCAN_LIMIT, requestDirs.size());
        for (int i = 0; i < limit; i++) {
            Path reqDir = requestDirs.get(i);
            Path testsDir = reqDir.resolve("tests");
            Path candidate = testsDir.resolve(filename);
            if (Files.exists(candidate)) {
                return candidate;
            }
            candidate = reqDir.resolve(filename);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path findNewestUnder(Path requestsRoot, String filename) {
        List<Path> matchedFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.find(
            requestsRoot,
            4,
            (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().equals(filename))) {
            matchedFiles = walk.collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception ignored) {
            return null;
        }

        if (matchedFiles.isEmpty()) {
            return null;
        }

        return matchedFiles.stream()
            .max(Comparator.comparingLong((Path p) -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (Exception e) {
                    return Long.MIN_VALUE;
                }
            }))
            .orElse(matchedFiles.get(0));
    }
    
    public String generateDockerOptLogFileName(String commitShort, String testName, int attemptNumber) {
        if (attemptNumber > 1) {
            return "docker_opt_" + commitShort + "_" + testName + "." + attemptNumber + ".log";
        } else {
            return "docker_opt_" + commitShort + "_" + testName + ".log";
        }
    }
}
