package com.navercorp.cubridqa.builder;

import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.tester.TestHandler;
import com.navercorp.cubridqa.builder.tester.TestOrchestrator;
import com.navercorp.cubridqa.builder.tester.HealthHandler;
import com.navercorp.cubridqa.builder.tester.LogStreamHandler;
import com.navercorp.cubridqa.builder.tester.HttpResponseWriter;
import com.navercorp.cubridqa.builder.logs.LogLocator;
import com.navercorp.cubridqa.builder.exec.DirectExecutor;
import com.navercorp.cubridqa.builder.exec.StandardDockerExecutor;
import com.navercorp.cubridqa.builder.exec.OptimizedDockerExecutor;
import com.navercorp.cubridqa.builder.exec.CubridInstaller;
import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.logging.LogConfig;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.DockerTesterManager;
import com.navercorp.cubridqa.builder.DockerImageBuilder;
import com.navercorp.cubridqa.builder.DockerUtils;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Refactored Tester that assembles all extracted components while maintaining 100% behavior parity.
 * This class serves as the main entry point and dependency injector for the modularized tester architecture.
 */
public class TesterRefactored {
    private final Config config;
    private final Logger logger;
    private final boolean useDocker;
    private final HttpServer server;
    
    // Core components
    private final BuildCache buildCache;
    private final ShellTcSync shellTcSync;
    private final CubridInstaller cubridInstaller;
    
    // Execution strategies
    private final DirectExecutor directExecutor;
    private final StandardDockerExecutor standardDockerExecutor;
    private final OptimizedDockerExecutor optimizedDockerExecutor;
    
    // Orchestration
    private final TestOrchestrator testOrchestrator;
    
    // HTTP handlers
    private final TestHandler testHandler;
    private final HealthHandler healthHandler;
    private final LogStreamHandler logStreamHandler;
    
    // Docker components (may be null if Docker disabled)
    private final Object dockerManager; // DockerTesterManager
    private final Object imageBuilder; // DockerImageBuilder

    public TesterRefactored(Config config) throws IOException {
        this.config = config;
        this.logger = Logger.getLogger(TesterRefactored.class.getName());
        
        // Initialize request logging
        try {
            LogConfig logConfig = new LogConfig(
                config.getMaxRequestLogs(),
                5, // maxTarFiles 
                "log", // logRootDir
                config.isRequestGroupingEnabled()
            );
            RequestLogManager.initialize(logConfig);
        } catch (IOException e) {
            logger.warning("Failed to initialize RequestLogManager: " + e.getMessage());
        }
        
        this.useDocker = config.useDockerForTester();
        this.dockerManager = useDocker ? new DockerTesterManager(config) : null;
        
        // Initialize Docker image builder for optimized test execution
        this.imageBuilder = useDocker ? new DockerImageBuilder(config) : null;
        
        // Create core components
        this.buildCache = new BuildCache(Paths.get(config.getWorkDir()));
        this.shellTcSync = new ShellTcSync(config);
        this.cubridInstaller = new CubridInstaller();
        
        // Create execution strategies
        this.directExecutor = new DirectExecutor(config, buildCache, shellTcSync, cubridInstaller);
        this.standardDockerExecutor = new StandardDockerExecutor(config, buildCache, shellTcSync);
        this.optimizedDockerExecutor = new OptimizedDockerExecutor(config, buildCache, shellTcSync, imageBuilder);
        
        // Create orchestrator
        this.testOrchestrator = new TestOrchestrator(
            config, 
            directExecutor, 
            standardDockerExecutor, 
            optimizedDockerExecutor,
            useDocker, 
            dockerManager, 
            new DockerUtils()
        );
        
        // Create HTTP handlers
        this.testHandler = new TestHandler(config, testOrchestrator, logger);
        this.healthHandler = new HealthHandler(new HttpResponseWriter());
        this.logStreamHandler = new LogStreamHandler(new LogLocator(), new HttpResponseWriter());
        
        // Build cache is ready for use
        
        // Background cleanup can be implemented later if needed
        logger.info("Tester initialized successfully");
        
        // Create work directory if it doesn't exist
        File workDir = new File(config.getWorkDir());
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        
        // Initialize Docker if enabled
        if (useDocker && dockerManager != null) {
            try {
                logger.info("Initializing Docker tester environment...");
                // Use reflection to call initialize method
                dockerManager.getClass().getMethod("initialize").invoke(dockerManager);
                logger.info("Docker tester environment ready");
            } catch (Exception e) {
                logger.warning("Docker initialization failed: " + e.getMessage());
            }
        }
        
        // Create HTTP server
        this.server = HttpServer.create(new InetSocketAddress(config.getTesterPort()), 0);
        this.server.createContext("/test", testHandler);
        this.server.createContext("/health", healthHandler);
        this.server.createContext("/log/", logStreamHandler);
        int maxThreads = Math.max(1, config.getMaxConcurrentTests());
        this.server.setExecutor(Executors.newFixedThreadPool(maxThreads));
    }
    
    public void start() {
        server.start();
        logger.info("Tester started on port " + config.getTesterPort());
        logger.info("Work directory: " + config.getWorkDir());
        logger.info("Docker mode: " + (useDocker ? "ENABLED" : "DISABLED"));
        if (useDocker) {
            logger.info("Docker test image: " + config.getDockerTestImage());
        }
    }
    
    public void stop() {
        server.stop(0);
        logger.info("Tester stopped");
    }
    
    // Main method for standalone execution (maintains original behavior)
    public static void main(String[] args) {
        try {
            String configFile = "conf/tester.conf";
            Config config = new Config(configFile);
            TesterRefactored tester = new TesterRefactored(config);
            tester.start();
        } catch (Exception e) {
            Logger.getLogger(TesterRefactored.class.getName()).severe("Failed to start tester: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}