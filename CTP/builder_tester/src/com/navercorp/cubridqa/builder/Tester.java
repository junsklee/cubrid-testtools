package com.navercorp.cubridqa.builder;

import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.tester.TestHandler;
import com.navercorp.cubridqa.builder.tester.TestOrchestrator;
import com.navercorp.cubridqa.builder.tester.HealthHandler;
import com.navercorp.cubridqa.builder.tester.ScoreHandler;
import com.navercorp.cubridqa.builder.tester.LogStreamHandler;
import com.navercorp.cubridqa.builder.tester.HttpResponseWriter;
import com.navercorp.cubridqa.builder.tester.NodeCapacity;
import com.navercorp.cubridqa.builder.logs.LogLocator;
import com.navercorp.cubridqa.builder.exec.DirectExecutor;
import com.navercorp.cubridqa.builder.exec.StandardDockerExecutor;
import com.navercorp.cubridqa.builder.exec.OptimizedDockerExecutor;
import com.navercorp.cubridqa.builder.exec.CubridInstaller;
import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.logging.LogConfig;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.docker.DockerTesterManager;
import com.navercorp.cubridqa.builder.docker.DockerImageBuilder;
import com.navercorp.cubridqa.builder.docker.DockerUtils;
import com.navercorp.cubridqa.builder.tester.stats.TestObservationWriter;
import com.navercorp.cubridqa.builder.tester.stats.TestStatsStore;
import com.navercorp.cubridqa.builder.tester.stats.WALManifest;
import com.navercorp.cubridqa.builder.tester.stats.WALSegmentWriter;
import com.navercorp.cubridqa.builder.tester.stats.RequestJournal;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Tester - Modular tester implementation with dependency injection
 * 
 * This is the main entry point for the tester service with a clean, modular 
 * architecture that provides comprehensive test execution capabilities.
 * 
 * Key architectural features:
 * - Dependency injection pattern for loose coupling
 * - Strategy pattern for execution methods (Direct/Docker/Optimized)
 * - Builder pattern for DTOs (TestRequest/TestResult)
 * - Clean separation of concerns across focused components
 * 
 * Provides comprehensive test execution with retry logic, flaky detection,
 * build caching, git synchronization, and multi-level logging.
 */
public class Tester {
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
    private final ScoreHandler scoreHandler;
    private final LogStreamHandler logStreamHandler;
    
    // Docker components (may be null if Docker disabled)
    private final Object dockerManager; // DockerTesterManager
    private final Object imageBuilder; // DockerImageBuilder

    // Statistics store
    private final TestStatsStore testStatsStore;
    private final WALSegmentWriter walWriter;

    // Request journal
    private final RequestJournal requestJournal;
    private final String requestId;

    /**
     * Generates a unique request ID for this tester run.
     * Format: req_YYYYMMDD_HHMMSS_xxxx
     */
    private static String newRequestId() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"));
        String ts = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String suf = java.util.UUID.randomUUID().toString().substring(0, 4);
        return "req_" + ts + "_" + suf;
    }

    public Tester(Config config) throws IOException {
        this.config = config;
        this.logger = Logger.getLogger(Tester.class.getName());
        
        // Initialize request logging
        try {
            // Determine project root directory for logs
            String logRootDir = "log"; // Default fallback
            String projectRoot = System.getProperty("tester.project.root");
            if (projectRoot != null) {
                logRootDir = new File(projectRoot, "log").getAbsolutePath();
            } else {
                // Fallback: try to derive from typical project structure
                try {
                    String userHome = System.getProperty("user.home");
                    String defaultProjectRoot = userHome + "/cubrid-testtools/CTP/builder_tester";
                    File projectLogDir = new File(defaultProjectRoot, "log");
                    if (projectLogDir.getParentFile().exists()) {
                        logRootDir = projectLogDir.getAbsolutePath();
                    }
                } catch (Exception e) {
                    logger.warning("Could not determine project log directory, using relative path: " + e.getMessage());
                }
            }
            
            LogConfig logConfig = new LogConfig(
                config.getMaxRequestLogs(),
                5, // maxTarFiles 
                logRootDir,
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
        
        Path profilesDir = Paths.get(config.getWorkDir()).resolve("profiles");
        Path observationWalPath = profilesDir.resolve("test_stats.jl.gz");
        TestObservationWriter observationWriter = new TestObservationWriter(observationWalPath, logger);

        // Initialize WAL components for robust crash-safe persistence
        Path walDir = profilesDir.resolve("wal");
        WALManifest manifest = new WALManifest(profilesDir);
        this.walWriter = new WALSegmentWriter(profilesDir, manifest);
        
        // Initialize TestStatsStore for prediction and scheduling
        long snapshotIntervalSeconds = config.getLongOrDefault("stats.snapshot_interval_seconds", 300L);
        this.testStatsStore = new TestStatsStore(profilesDir, snapshotIntervalSeconds, walWriter, manifest, walDir);

        // Initialize request journal for per-request tracking
        this.requestId = newRequestId();
        this.requestJournal = new RequestJournal(profilesDir, requestId);
        testStatsStore.setRequestJournal(requestJournal);
        logger.info("Request journal initialized: " + requestId);

        // Create orchestrator
        this.testOrchestrator = new TestOrchestrator(
            config,
            directExecutor,
            standardDockerExecutor,
            optimizedDockerExecutor,
            useDocker,
            dockerManager,
            new DockerUtils(),
            observationWriter,
            testStatsStore
        );

        // Measure node capacity for health endpoint
        NodeCapacity nodeCapacity = NodeCapacity.measure(config.getWorkDir());

        // Set node hardware for latest.json.gz export
        testStatsStore.setNodeHardwareJson(nodeCapacity.toJSON());

        // Create HTTP handlers
        this.testHandler = new TestHandler(config, testOrchestrator, nodeCapacity, logger);
        this.healthHandler = new HealthHandler(config, new HttpResponseWriter(), nodeCapacity, testOrchestrator);
        this.scoreHandler = new ScoreHandler(config, new HttpResponseWriter(), testStatsStore, nodeCapacity);
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
        this.server.createContext("/score", scoreHandler);
        this.server.createContext("/log/", logStreamHandler);
        int maxThreads = Math.max(1, config.getMaxConcurrentTests());
        this.server.setExecutor(Executors.newFixedThreadPool(maxThreads));
    }
    
    public void start() {
        // Start WAL writer first (acquires lock, opens segment)
        try {
            walWriter.start();
            logger.info("WALSegmentWriter started");
        } catch (IOException e) {
            logger.severe("Failed to start WALSegmentWriter: " + e.getMessage());
            throw new RuntimeException("WAL initialization failed", e);
        }
        
        // Start TestStatsStore (loads snapshot, replays WAL, starts coordinator)
        testStatsStore.start();
        logger.info("TestStatsStore started");

        server.start();
        logger.info("Tester started on port " + config.getTesterPort());
        logger.info("Work directory: " + config.getWorkDir());
        logger.info("Docker mode: " + (useDocker ? "ENABLED" : "DISABLED"));
        if (useDocker) {
            logger.info("Docker test image: " + config.getDockerTestImage());
        }
    }
    
    public void stop() {
        // Stop TestStatsStore (writes final snapshot)
        testStatsStore.stop();
        logger.info("TestStatsStore stopped");

        // Flush and close request journal
        String nodeName = System.getenv("HOSTNAME");
        if (nodeName == null) {
            nodeName = "unknown";
        }
        requestJournal.flushAndClose(nodeName, null);
        logger.info("RequestJournal flushed: " + requestId);

        // Stop WAL writer (releases lock, closes segment)
        walWriter.stop();
        logger.info("WALSegmentWriter stopped");

        server.stop(0);
        logger.info("Tester stopped");
    }

    // Expose TestStatsStore for handlers (e.g., ScoreHandler)
    public TestStatsStore getTestStatsStore() {
        return testStatsStore;
    }
    
    // Main method for standalone execution (maintains original behavior)
    public static void main(String[] args) {
        try {
            String configFile = args.length > 0 ? args[0] : "conf/tester.conf";
            
            // Set system property for log directory based on config file location
            if (args.length > 0) {
                // Config file is absolute path from startup script
                File configFileObj = new File(configFile);
                File projectRoot = configFileObj.getParentFile().getParentFile(); // ../.. from conf/tester.conf
                System.setProperty("tester.project.root", projectRoot.getAbsolutePath());
            }
            
            Config config = new Config(configFile);
            Tester tester = new Tester(config);
            tester.start();
        } catch (Exception e) {
            Logger.getLogger(Tester.class.getName()).severe("Failed to start tester: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
