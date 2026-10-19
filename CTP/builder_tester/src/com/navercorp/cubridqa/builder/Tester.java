package com.navercorp.cubridqa.builder;

import com.navercorp.cubridqa.builder.config.Config;
import com.navercorp.cubridqa.builder.tester.TestHandler;
import com.navercorp.cubridqa.builder.tester.TestOrchestrator;
import com.navercorp.cubridqa.builder.tester.HealthHandler;
import com.navercorp.cubridqa.builder.tester.ScoreHandler;
import com.navercorp.cubridqa.builder.tester.LogStreamHandler;
import com.navercorp.cubridqa.builder.tester.FinalizeRequestHandler;
import com.navercorp.cubridqa.builder.tester.CancelRequestHandler;
import com.navercorp.cubridqa.builder.tester.HttpResponseWriter;
import com.navercorp.cubridqa.builder.tester.NodeCapacity;
import com.navercorp.cubridqa.builder.tester.ActualSampler;
import com.navercorp.cubridqa.builder.logs.LogLocator;
import com.navercorp.cubridqa.builder.exec.DirectExecutor;
import com.navercorp.cubridqa.builder.exec.StandardDockerExecutor;
import com.navercorp.cubridqa.builder.exec.OptimizedDockerExecutor;
import com.navercorp.cubridqa.builder.exec.VmShellExecutor;
import com.navercorp.cubridqa.builder.exec.SqlDockerExecutor;
import com.navercorp.cubridqa.builder.exec.SqlAgentPool;
import com.navercorp.cubridqa.builder.exec.CubridInstaller;
import com.navercorp.cubridqa.builder.cache.BuildCache;
import com.navercorp.cubridqa.builder.ctp.CtpProvisioner;
import com.navercorp.cubridqa.builder.git.ShellTcSync;
import com.navercorp.cubridqa.builder.git.SqlTcSync;
import com.navercorp.cubridqa.builder.logging.LogConfig;
import com.navercorp.cubridqa.builder.logging.LogRotationManager;
import com.navercorp.cubridqa.builder.tester.SafeIo;
import com.navercorp.cubridqa.builder.logging.RequestLogManager;
import com.navercorp.cubridqa.builder.docker.DockerTesterManager;
import com.navercorp.cubridqa.builder.docker.DockerImageBuilder;
import com.navercorp.cubridqa.builder.docker.DockerUtils;
import com.navercorp.cubridqa.builder.tester.stats.TestObservationWriter;
import com.navercorp.cubridqa.builder.tester.stats.TestStatsStore;
import com.navercorp.cubridqa.builder.tester.stats.WALManifest;
import com.navercorp.cubridqa.builder.tester.stats.WALSegmentWriter;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final SqlTcSync sqlTcSync;
    private final CtpProvisioner ctpProvisioner;
    private final SqlAgentPool sqlAgentPool;
    private final CubridInstaller cubridInstaller;

    // Execution strategies
    private final DirectExecutor directExecutor;
    private final StandardDockerExecutor standardDockerExecutor;
    private final OptimizedDockerExecutor optimizedDockerExecutor;
    private final SqlDockerExecutor sqlDockerExecutor;
    
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
            LogRotationManager rotation = new LogRotationManager(logConfig);
            ScheduledExecutorService rotationTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tester-log-rotation");
                t.setDaemon(true);
                return t;
            });
            rotationTimer.scheduleWithFixedDelay(rotation::cleanupTesterRequestLogs, 1, 60, TimeUnit.MINUTES);
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
        this.sqlTcSync = new SqlTcSync(config);
        this.ctpProvisioner = new CtpProvisioner(config);
        this.sqlAgentPool = new SqlAgentPool(config);
        this.cubridInstaller = new CubridInstaller();
        this.shellTcSync.cleanupStaleRequestWorkspaces(logger);
        this.sqlTcSync.cleanupStaleRequestWorkspaces(logger);
        sweepStaleAttemptWorkDirs(Paths.get(config.getWorkDir()));

        // Create execution strategies
        this.directExecutor = new DirectExecutor(config, buildCache, shellTcSync, cubridInstaller);
        this.standardDockerExecutor = new StandardDockerExecutor(config, buildCache, shellTcSync);
        this.optimizedDockerExecutor = new OptimizedDockerExecutor(config, buildCache, shellTcSync, imageBuilder);
        this.sqlDockerExecutor = useDocker
            ? new SqlDockerExecutor(config, buildCache, sqlTcSync, imageBuilder, ctpProvisioner, sqlAgentPool)
            : null;
        
        Path profilesDir = Paths.get(config.getWorkDir()).resolve("profiles");
        Path observationWalPath = profilesDir.resolve("test_stats.jl.gz");
        TestObservationWriter observationWriter = new TestObservationWriter(
            observationWalPath, logger, config.isWalCollectionEnabled());

        // Initialize WAL components for robust crash-safe persistence
        Path walDir = profilesDir.resolve("wal");
        WALManifest manifest = new WALManifest(profilesDir);
        this.walWriter = new WALSegmentWriter(profilesDir, manifest);
        
        // Initialize TestStatsStore for prediction and scheduling
        // stats_snapshot_interval_seconds is the documented key; stats.snapshot_interval_seconds was
        // the only one read before
        long snapshotIntervalSeconds = config.getLongOrDefault("stats_snapshot_interval_seconds",
            config.getLongOrDefault("stats.snapshot_interval_seconds", 300L));
        this.testStatsStore = new TestStatsStore(profilesDir, snapshotIntervalSeconds, walWriter, manifest, walDir, config);

        // Measure node capacity for health endpoint (with config for customizable IOPS)
        // Moved up before TestOrchestrator so it can be passed in
        NodeCapacity nodeCapacity = NodeCapacity.measure(config.getWorkDir(), config);

        // Set node hardware for latest.json.gz export
        testStatsStore.setNodeHardwareJson(nodeCapacity.toJSON());

        // Create actual resource sampler for health metrics (starts background thread)
        ActualSampler actualSampler = new ActualSampler(config);

        // Create orchestrator
        this.testOrchestrator = new TestOrchestrator(
            config,
            directExecutor,
            standardDockerExecutor,
            optimizedDockerExecutor,
            sqlDockerExecutor,
            useDocker,
            dockerManager,
            new DockerUtils(),
            observationWriter,
            testStatsStore,
            nodeCapacity,
            actualSampler
        );
        if (config.isVmMode()) {
            testOrchestrator.setVmShellExecutor(new VmShellExecutor(config, buildCache, shellTcSync));
            logger.info("Execution mode: vm (one shell case at a time, CUBRID=" + config.getVmCubridHome()
                + ", case timeout " + config.getVmCaseTimeoutSeconds() + "s)");
        }

        // Create HTTP handlers
        this.testHandler = new TestHandler(config, testOrchestrator, logger);
        this.healthHandler = new HealthHandler(config, new HttpResponseWriter(), nodeCapacity, testOrchestrator, actualSampler);
        this.scoreHandler = new ScoreHandler(config, new HttpResponseWriter(), testStatsStore, nodeCapacity);
        this.logStreamHandler = new LogStreamHandler(new LogLocator(), new HttpResponseWriter());
        FinalizeRequestHandler finalizeRequestHandler = new FinalizeRequestHandler(testStatsStore, shellTcSync, sqlTcSync, sqlAgentPool);
        
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
        String bindAddress = config.getTesterBindAddress();
        this.server = HttpServer.create(bindAddress.isEmpty()
            ? new InetSocketAddress(config.getTesterPort())
            : new InetSocketAddress(bindAddress, config.getTesterPort()), 0);
        this.server.createContext("/test", testHandler);
        this.server.createContext("/health", healthHandler);
        this.server.createContext("/score", scoreHandler);
        this.server.createContext("/log/", logStreamHandler);
        this.server.createContext("/finalize-request", finalizeRequestHandler);
        this.server.createContext("/cancel-request", new CancelRequestHandler(shellTcSync, sqlTcSync, sqlAgentPool));
        // /test calls hold a thread for the whole case; keep threads free for /health, /log,
        // /finalize-request and /cancel-request, or the builder sees a busy node as stale.
        int maxThreads = Math.max(1, config.getMaxConcurrentTests()) + 8;
        this.server.setExecutor(Executors.newFixedThreadPool(maxThreads));
    }
    
    /**
     * Removes per-attempt work directories (work_dir/test_*) left by a tester that was killed or
     * restarted mid-test: they are only deleted in the attempt's finally block, so each one stayed
     * forever (80 directories, 15GB here). Only directories older than a day are removed, so a
     * second tester sharing work_dir keeps its running attempts.
     */
    private void sweepStaleAttemptWorkDirs(Path workRoot) {
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        File[] stale = workRoot.toFile().listFiles(f -> f.isDirectory() && f.getName().startsWith("test_")
            && f.lastModified() < cutoff);
        if (stale == null || stale.length == 0) {
            return;
        }
        logger.info("Removing " + stale.length + " stale attempt work directories under " + workRoot);
        for (File dir : stale) {
            SafeIo.deleteDirectory(dir);
            if (dir.exists()) {
                SafeIo.deleteDirectoryWithPrivileges(dir, logger);
            }
        }
    }

    public void start() {
        if (config.isStatsEnabled()) {
            // Start TestStatsStore first: it loads MANIFEST and the snapshot and replays the WAL
            // segments. Starting the writer first made it persist an empty manifest over the real
            // one, so the observations after the last snapshot were never replayed.
            testStatsStore.start();
            logger.info("TestStatsStore started");

            if (config.isWalCollectionEnabled()) {
                // Then the WAL writer (acquires lock, appends to the manifest's open segment)
                try {
                    walWriter.start();
                    logger.info("WALSegmentWriter started");
                } catch (IOException e) {
                    logger.severe("Failed to start WALSegmentWriter: " + e.getMessage());
                    throw new RuntimeException("WAL initialization failed", e);
                }
            } else {
                logger.info("WAL collection disabled (wal_collection_enabled=false); skipping WALSegmentWriter");
            }
        } else {
            logger.info("Stats disabled (stats_enabled=false); skipping WAL/TestStatsStore startup");
        }

        server.start();
        logger.info("Tester started on port " + config.getTesterPort());
        logger.info("Work directory: " + config.getWorkDir());
        logger.info("Docker mode: " + (useDocker ? "ENABLED" : "DISABLED"));
        if (useDocker) {
            logger.info("Docker test image: " + config.getDockerTestImage());
        }
    }
    
    public void stop() {
        if (config.isStatsEnabled()) {
            // Stop TestStatsStore (writes final snapshot and flushes request journals)
            testStatsStore.stop();
            logger.info("TestStatsStore stopped");

            if (config.isWalCollectionEnabled()) {
                // Stop WAL writer (releases lock, closes segment)
                walWriter.stop();
                logger.info("WALSegmentWriter stopped");
            } else {
                logger.info("WAL collection disabled (wal_collection_enabled=false); skipping WALSegmentWriter shutdown");
            }
        }

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
                File configFileObj = new File(configFile).getAbsoluteFile();
                File projectRoot = configFileObj.getParentFile().getParentFile(); // ../.. from conf/tester.conf
                System.setProperty("tester.project.root", projectRoot.getAbsolutePath());
            }
            
            Config config = new Config(configFile);
            Tester tester = new Tester(config);
            tester.start();
            // stop_tester.sh sends SIGTERM: write the final stats snapshot and drain the WAL
            Runtime.getRuntime().addShutdownHook(new Thread(tester::stop, "tester-shutdown"));
        } catch (Exception e) {
            Logger.getLogger(Tester.class.getName()).severe("Failed to start tester: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
