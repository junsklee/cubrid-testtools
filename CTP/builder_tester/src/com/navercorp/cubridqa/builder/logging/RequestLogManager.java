package com.navercorp.cubridqa.builder.logging;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.*;

/**
 * Central logging manager that handles request-scoped and system-level logging.
 * Provides structured logging with automatic organization by request ID.
 */
public class RequestLogManager {
    private static RequestLogManager instance;
    private final LogConfig config;
    private final Logger systemLogger;
    private FileHandler systemFileHandler;
    
    private RequestLogManager(LogConfig config) throws IOException {
        this.config = config;
        this.systemLogger = Logger.getLogger("System");
        initializeDirectories();
        setupSystemLogger();
    }
    
    /**
     * Initialize the RequestLogManager with configuration
     */
    public static synchronized void initialize(LogConfig config) throws IOException {
        if (instance == null) {
            instance = new RequestLogManager(config);
        }
    }
    
    /**
     * Get the singleton instance
     */
    public static RequestLogManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("RequestLogManager not initialized. Call initialize() first.");
        }
        return instance;
    }
    
    /**
     * Initialize directory structure
     */
    private void initializeDirectories() throws IOException {
        Files.createDirectories(Paths.get(config.getRequestsDir()));
        Files.createDirectories(Paths.get(config.getSystemDir()));
    }
    
    /**
     * Setup system-level logger
     */
    private void setupSystemLogger() throws IOException {
        systemLogger.setUseParentHandlers(false);
        
        // Console handler
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        consoleHandler.setFormatter(new SimpleFormatter());
        systemLogger.addHandler(consoleHandler);
    }
    
    /**
     * Get a logger for a specific component within a request context
     * This creates a dual logger that writes to both system and request-specific logs
     */
    public Logger getRequestLogger(String requestId, String component) throws IOException {
        if (!config.isRequestGroupingEnabled()) {
            return systemLogger;
        }
        
        String requestDir = config.getRequestDir(requestId);
        Files.createDirectories(Paths.get(requestDir));
        
        String logPath = requestDir + "/" + component + ".log";
        Logger logger = Logger.getLogger(requestId + "." + component);
        logger.setUseParentHandlers(false);
        
        // File handler for request-specific log
        FileHandler fileHandler = new FileHandler(logPath, true);
        fileHandler.setFormatter(new RequestLogFormatter(requestId));
        logger.addHandler(fileHandler);
        
        // Also log to system log file
        String systemLogPath = config.getSystemDir() + "/" + component + ".log";
        FileHandler systemFileHandler = new FileHandler(systemLogPath, true);
        systemFileHandler.setFormatter(new RequestLogFormatter(requestId));
        logger.addHandler(systemFileHandler);
        
        // Also log to console with request ID prefix
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new RequestLogFormatter(requestId));
        logger.addHandler(consoleHandler);
        
        logger.setLevel(Level.ALL);
        return logger;
    }
    
    /**
     * Create a subdirectory for specific log types within a request
     */
    public String createRequestSubdir(String requestId, String subdir) throws IOException {
        String path = config.getRequestDir(requestId) + "/" + subdir;
        Files.createDirectories(Paths.get(path));
        return path;
    }
    
    /**
     * Get the system logger
     */
    public Logger getSystemLogger() {
        return systemLogger;
    }
    
    /**
     * Custom formatter that includes request ID
     */
    private static class RequestLogFormatter extends Formatter {
        private final String requestId;
        private final SimpleFormatter delegate = new SimpleFormatter();
        
        public RequestLogFormatter(String requestId) {
            this.requestId = requestId;
        }
        
        @Override
        public String format(LogRecord record) {
            String original = delegate.format(record);
            return "[" + requestId + "] " + original;
        }
    }
}
