package com.navercorp.cubridqa.builder.logs;

import com.navercorp.cubridqa.builder.logging.RequestContext;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.logging.Logger;

public class LogLocator {
    private static final Logger logger = Logger.getLogger(LogLocator.class.getName());
    
    public Path findLogFile(String filename) {
        logger.info("Searching for log file: " + filename);
        
        // Try to get current request context first
        String requestId = RequestContext.getRequestId();
        if (requestId != null) {
            logger.info("Current RequestContext ID: " + requestId);
            
            try {
                RequestLogManager logManager = RequestLogManager.getInstance();
                Path requestLogDir = logManager.getRequestLogDirectory(requestId);
                if (requestLogDir != null) {
                    // Check in tests subdirectory
                    Path testsDir = requestLogDir.resolve("tests");
                    Path logFile = testsDir.resolve(filename);
                    if (Files.exists(logFile)) {
                        logger.info("Found log file at: " + logFile.toString());
                        return logFile;
                    }
                    
                    // Check in request root directory
                    logFile = requestLogDir.resolve(filename);
                    if (Files.exists(logFile)) {
                        logger.info("Found log file at: " + logFile.toString());
                        return logFile;
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to use RequestLogManager for log lookup: " + e.getMessage());
            }
        }
        
        // Fallback: search in common log locations
        String[] searchPaths = {
            System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log/requests",
        };
        
        for (String searchPath : searchPaths) {
            try {
                Path basePath = Paths.get(searchPath);
                if (Files.exists(basePath)) {
                    // Search recursively for the file
                    Path foundFile = Files.walk(basePath)
                        .filter(path -> path.getFileName().toString().equals(filename))
                        .findFirst()
                        .orElse(null);
                    
                    if (foundFile != null) {
                        logger.info("Found log file at: " + foundFile.toString());
                        return foundFile;
                    }
                }
            } catch (Exception e) {
                logger.warning("Failed to search in path " + searchPath + ": " + e.getMessage());
            }
        }
        
        logger.warning("Log file not found: " + filename);
        return null;
    }
    
    public String generateDockerOptLogFileName(String commitShort, String testName, int attemptNumber) {
        if (attemptNumber > 1) {
            return "docker_opt_" + commitShort + "_" + testName + "." + attemptNumber + ".log";
        } else {
            return "docker_opt_" + commitShort + "_" + testName + ".log";
        }
    }
}