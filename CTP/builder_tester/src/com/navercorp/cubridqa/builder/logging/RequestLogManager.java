package com.navercorp.cubridqa.builder.logging;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.*;

/**
 * Central logging manager that handles request-scoped and system-level logging.
 * Provides structured logging with automatic organization by request ID.
 */
public class RequestLogManager {
    private static RequestLogManager instance;
    private final LogConfig config;
    private final Logger systemLogger;
    // Request loggers with an open FileHandler, keyed "<requestId>.<component>". Strong references keep
    // java.util.logging from collecting a logger (and leaking its handler) while it is attached.
    private final Map<String, Logger> requestLoggers = new HashMap<>();
    private final Map<String, Path> attachedLogPaths = new HashMap<>();
    
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
     * Get a logger for a specific component within a request context.
     * This creates a logger that writes ONLY to request-specific logs. Repeated calls for the same
     * request reuse the attached file handler; {@link #closeRequestLoggers} releases it.
     */
    public synchronized Logger getRequestLogger(String requestId, String component) throws IOException {
        if (!config.isRequestGroupingEnabled()) {
            return systemLogger;
        }

        Path requestDir = getRequestLogDirectory(requestId);
        Path logPath = requestDir.resolve(component + ".log");
        String key = requestId + "." + component;
        Logger logger = Logger.getLogger(key);
        Path attachedPath = attachedLogPaths.get(key);
        if (logPath.equals(attachedPath) && Files.exists(logPath)) {
            return logger;
        }

        Files.createDirectories(requestDir);
        logger.setUseParentHandlers(false);
        // The log was never attached here, or its directory was deleted (cancel): start fresh.
        closeHandlers(logger);

        // File handler for request-specific log (no rotation)
        FileHandler fileHandler = new FileHandler(logPath.toString(), 0, 1, true);
        fileHandler.setFormatter(new RequestLogFormatter(requestId));
        logger.addHandler(fileHandler);

        // Console handler for debugging
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new RequestLogFormatter(requestId));
        consoleHandler.setLevel(Level.INFO);
        logger.addHandler(consoleHandler);

        logger.setLevel(Level.ALL);
        attachedLogPaths.put(key, logPath);
        requestLoggers.put(key, logger);
        return logger;
    }

    /**
     * Close the file handlers of every component logger of a request (end of request, cancel,
     * finalize). A later {@link #getRequestLogger} call reopens the log in append mode.
     */
    public synchronized void closeRequestLoggers(String requestId) {
        if (requestId == null) {
            return;
        }
        String prefix = requestId + ".";
        Iterator<Map.Entry<String, Logger>> it = requestLoggers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Logger> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                closeHandlers(entry.getValue());
                attachedLogPaths.remove(entry.getKey());
                it.remove();
            }
        }
    }

    private static void closeHandlers(Logger logger) {
        for (Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
            h.close();
        }
    }
    
    /**
     * Create a subdirectory for specific log types within a request
     */
    public String createRequestSubdir(String requestId, String subdir) throws IOException {
        Path path = getRequestLogDirectory(requestId).resolve(subdir);
        Files.createDirectories(path);
        return path.toString();
    }
    
    /**
     * Get the system logger
     */
    public Logger getSystemLogger() {
        return systemLogger;
    }
    
    /**
     * Get the requests directory path
     */
    public String getRequestsDir() {
        return config.getRequestsDir();
    }
    
    /**
     * Get the system directory path
     */
    public String getSystemDir() {
        return config.getSystemDir();
    }
    
    /**
     * Get the request log directory path for a specific request ID
     */
    public Path getRequestLogDirectory(String requestId) {
        return RequestIds.resolveUnder(Paths.get(config.getRequestsDir()), requestId);
    }
    
    /**
     * Check if request grouping is enabled
     */
    public boolean isRequestGroupingEnabled() {
        return config.isRequestGroupingEnabled();
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
