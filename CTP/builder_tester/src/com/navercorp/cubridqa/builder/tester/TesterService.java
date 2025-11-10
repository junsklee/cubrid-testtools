package com.navercorp.cubridqa.builder.tester;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.docker.DockerTesterManager;
import com.navercorp.cubridqa.builder.docker.DockerImageBuilder;
import com.navercorp.cubridqa.builder.logging.LogConfig;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.logs.LogLocator;
import java.io.IOException;
import java.io.File;
import java.util.logging.Logger;

public class TesterService {
    private static final Logger logger = Logger.getLogger(TesterService.class.getName());
    
    private final BuilderConfig config;
    private final ApiServer apiServer;
    private final DockerTesterManager dockerManager;
    private final boolean useDocker;
    private final DockerImageBuilder imageBuilder;
    private final HttpResponseWriter responseWriter;
    private final LogLocator logLocator;
    
    public TesterService(BuilderConfig config) throws IOException {
        this.config = config;
        this.useDocker = config.useDockerForTester();
        
        // Initialize logging infrastructure
        LogConfig logConfig = new LogConfig(
            config.getMaxRequestLogs(),
            10, // Tester doesn't manage tar files, but we need a value
            System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/log",
            config.isRequestGroupingEnabled()
        );
        try {
            RequestLogManager.initialize(logConfig);
        } catch (IOException e) {
            logger.warning("Failed to initialize RequestLogManager: " + e.getMessage());
        }
        
        this.dockerManager = useDocker ? new DockerTesterManager(config) : null;
        this.imageBuilder = useDocker ? new DockerImageBuilder(config) : null;
        
        // Initialize utilities
        this.responseWriter = new HttpResponseWriter();
        this.logLocator = new LogLocator();
        
        // Initialize API server
        this.apiServer = new ApiServer(config);
        
        // Register real handlers
        this.apiServer.registerHandler("/test", new PlaceholderTestHandler());
        // NOTE: TesterService is legacy - HealthHandler requires NodeCapacity and TestOrchestrator
        // For now, provide dummy values. Use Tester.java instead for full functionality.
        NodeCapacity dummyCapacity = NodeCapacity.measure(config.getWorkDir());
        TestOrchestrator dummyOrchestrator = null; // TesterService doesn't have orchestrator
        this.apiServer.registerHandler("/health", new HealthHandler(config, responseWriter, dummyCapacity, dummyOrchestrator));
        this.apiServer.registerHandler("/log/", new LogStreamHandler(logLocator, responseWriter));
        
        // Create work directory if it doesn't exist
        File workDir = new File(config.getWorkDir());
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        
        // Initialize Docker if enabled
        if (useDocker && dockerManager != null) {
            try {
                logger.info("Initializing Docker tester environment...");
                dockerManager.initialize();
                logger.info("Docker tester environment ready");
            } catch (Exception e) {
                logger.warning("Docker initialization failed: " + e.getMessage());
            }
        }
        
        // Add shutdown hook to clean up temp files
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown hook: cleaning up temp files...");
            // Cleanup logic will be implemented in later batches
        }));
    }
    
    public void start() {
        apiServer.start();
        logger.info("Tester started on port " + config.getTesterPort());
        logger.info("Work directory: " + config.getWorkDir());
        logger.info("Docker mode: " + (useDocker ? "ENABLED" : "DISABLED"));
        if (useDocker) {
            logger.info("Docker test image: " + config.getDockerTestImage());
        }
    }
    
    public void stop() {
        apiServer.stop();
        logger.info("Tester stopped");
    }
    
    public BuilderConfig getConfig() {
        return config;
    }
    
    public DockerTesterManager getDockerManager() {
        return dockerManager;
    }
    
    public DockerImageBuilder getImageBuilder() {
        return imageBuilder;
    }
    
    public boolean isUseDocker() {
        return useDocker;
    }
    
    public HttpResponseWriter getResponseWriter() {
        return responseWriter;
    }
    
    public LogLocator getLogLocator() {
        return logLocator;
    }
    
    public static void main(String[] args) {
        try {
            // Setup logging
            System.setProperty("java.util.logging.config.file", 
                System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/conf/logging.properties");
            
            // Load configuration
            String configPath = System.getProperty("user.home") + "/cubrid-testtools/CTP/builder_tester/conf/tester.conf";
            BuilderConfig config = new BuilderConfig(configPath);
            
            // Start service
            TesterService service = new TesterService(config);
            service.start();
            
            logger.info("Tester service started successfully");
            
            // Keep running
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.severe("Failed to start Tester service: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    // Placeholder for test handler - will be replaced in Batch 8
    private static class PlaceholderTestHandler implements com.sun.net.httpserver.HttpHandler {
        public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(501, -1);
        }
    }
}
