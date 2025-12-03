/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder;

import java.io.*;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.Locale;

/**
 * BuilderConfig - Configuration for builder/tester services
 */
public class BuilderConfig {
    private final Properties properties;
    
    // Configuration keys
    private static final String LISTEN_PORT = "listen_port";
    private static final String CUBRID_SRC_DIR = "cubrid_src_dir";
    private static final String SHELL_TC_DIR = "shell_tc_dir";
    private static final String SHELL_TC_OVERLAY_MODE = "shell_tc_overlay_mode";
    private static final String SHELL_TC_OVERLAY_DIR = "shell_tc_overlay_dir";
    private static final String SHELL_TC_BRANCH = "shell_tc_branch";
    private static final String SHELL_TC_PREFERRED_REMOTE = "shell_tc_preferred_remote";
    private static final String BUILD_ARG = "build_arg";
    private static final String BUILD_DIR = "build_dir";
    private static final String WORK_DIR = "work_dir";
    private static final String TESTER_PORT = "tester_port";
    private static final String MAX_CONCURRENT_BUILDS = "max_concurrent_builds";
    private static final String MAX_CONCURRENT_TESTS = "max_concurrent_tests"; // Only valid in tester.conf
    private static final String MAX_CONCURRENT_TESTS_HEAVY_QUEUE = "max_concurrent_tests_heavy_queue";
    private static final String MAX_CONCURRENT_TESTS_POST_HEAVY = "max_concurrent_tests_post_heavy";
    private static final String USE_DOCKER = "use_docker";
    private static final String USE_PREBUILT_DOCKER_IMAGES = "use_prebuilt_docker_images";
    private static final String DOCKER_BUILD_IMAGE = "docker_build_image";
    private static final String DOCKER_TEST_IMAGE = "docker_test_image";
    private static final String BUILD_CACHE_SIZE = "build_cache_size";
    private static final String MAX_REQUEST_LOGS = "max_request_logs";
    private static final String MAX_TAR_FILES = "max_tar_files";
    private static final String ENABLE_REQUEST_GROUPING = "enable_request_grouping";
    private static final String RETRY_COUNT = "retry_count"; // DEPRECATED v1: number of retries/repeats (kept for migration)
    private static final String RUN_MODE = "run_mode"; // Builder: test execution mode (until-pass, until-fail, fixed-runs)
    // v2 unified run semantics
    private static final String MIN_RUNS = "min_runs";
    private static final String MAX_RUNS = "max_runs";
    private static final String TIME_BUDGET_MS = "time_budget_ms";
    private static final String TEST_READ_TIMEOUT_MINUTES = "test_read_timeout_minutes"; // Tester: HTTP read timeout for /test
    private static final String LOG_FETCH_CONNECT_TIMEOUT_SECONDS = "log_fetch_connect_timeout_seconds";
    private static final String LOG_FETCH_READ_TIMEOUT_SECONDS = "log_fetch_read_timeout_seconds";
    private static final String LOG_FILE_VERIFICATION_TIMEOUT_SECONDS = "log_file_verification_timeout_seconds";
    private static final String OPTIMIZED_DOCKER_ENABLED = "optimized_docker_enabled"; // Enable Docker image caching
    private static final String DOCKER_ENFORCE_MEMORY_LIMITS = "docker_enforce_memory_limits"; // Enforce Docker memory limits from predictions (default false)
    private static final String DOCKER_ENFORCE_CPU_LIMITS = "docker_enforce_cpu_limits"; // Enforce Docker CPU limits from predictions (default false)
    // Resource limit override values (null = use predicted demand)
    private static final String DOCKER_CPU_LIMIT_MILLICORES = "docker_cpu_limit_millicores";
    private static final String DOCKER_MEMORY_LIMIT_MB = "docker_memory_limit_mb";
    private static final String DOCKER_IO_READ_LIMIT_MBPS = "docker_io_read_limit_mbps";
    private static final String DOCKER_IO_WRITE_LIMIT_MBPS = "docker_io_write_limit_mbps";
    private static final String DOCKER_IO_DEVICE = "docker_io_device"; // Block device for I/O limits (auto-detect if not set)
    private static final String CCACHE_ENABLED = "ccache_enabled";
    private static final String CCACHE_DIR = "ccache_dir";
    private static final String CCACHE_MAX_SIZE = "ccache_max_size";
    private static final String CCACHE_COMPILERCHECK = "ccache_compilercheck";
    private static final String CCACHE_HARDLINK = "ccache_hardlink";
    private static final String CCACHE_READONLY_DIRECT = "ccache_readonly_direct";
    private static final String CCACHE_STATS = "ccache_stats";
    private static final String CCACHE_NAMESPACE = "ccache_namespace";
    private static final String CCACHE_SLOPPINESS = "ccache_sloppiness";
    private static final String PARALLEL_JOBS = "parallel_jobs";
    private static final String SHELL_TC_SYNC_INTERVAL_SECONDS = "shell_tc_sync_interval_seconds";

    // Smart scheduling configuration
    private static final String SMART_SCHEDULING_ENABLED = "smart_scheduling_enabled";
    private static final String SCHEDULING_MICE_THRESHOLD_MS = "scheduling_mice_threshold_ms";
    private static final String SCHEDULING_ELEPHANT_WEIGHT = "scheduling_elephant_weight";
    private static final String SCHEDULING_ELEPHANT_LOAD_PENALTY = "scheduling_elephant_load_penalty";
    private static final String SCHEDULING_WEIGHT_PRESSURE = "scheduling_weight_pressure";
    private static final String SCHEDULING_WEIGHT_DURATION = "scheduling_weight_duration";
    private static final String SCHEDULING_WEIGHT_IMAGE_CACHE = "scheduling_weight_image_cache";
    private static final String SCHEDULING_WEIGHT_PACKAGE_CACHE = "scheduling_weight_package_cache";
    private static final String SCHEDULING_WEIGHT_AGE_BOOST = "scheduling_weight_age_boost";
    private static final String SCHEDULING_NODE_POLL_INTERVAL_SECONDS = "scheduling_node_poll_interval_seconds";
    private static final String SCHEDULING_NODE_STALE_THRESHOLD_SECONDS = "scheduling_node_stale_threshold_seconds";
    private static final String SCHEDULING_IO_LIGHT_THRESHOLD = "scheduling_io_light_threshold";
    private static final String SCHEDULING_IO_HEAVY_THRESHOLD = "scheduling_io_heavy_threshold";
    private static final String SCHEDULING_MIX_LONG_FRACTION = "scheduling_mix_long_fraction";
    private static final String SCHEDULING_MIX_MEDIUM_FRACTION = "scheduling_mix_medium_fraction";
    private static final String SCHEDULING_MIX_SHORT_FRACTION = "scheduling_mix_short_fraction";
    private static final String PULL_SCHEDULING_ENABLED = "pull_scheduling_enabled";

    // Dimension-specific safety margins for resource headroom checks
    private static final String SCHEDULING_MARGIN_CPU_BASE = "scheduling_margin_cpu_base";
    private static final String SCHEDULING_MARGIN_MEM_BASE = "scheduling_margin_mem_base";
    private static final String SCHEDULING_MARGIN_IO_BASE = "scheduling_margin_io_base";
    private static final String SCHEDULING_MARGIN_NET_BASE = "scheduling_margin_net_base";
    private static final String SCHEDULING_MARGIN_IOPS_BASE = "scheduling_margin_iops_base";
    private static final String SCHEDULING_MARGIN_CONFIDENCE_FACTOR = "scheduling_margin_confidence_factor";
    private static final String USE_IOPS_PREDICTIONS = "use_iops_predictions";

    // Elastic Overcommit Configuration
    private static final String SCHEDULING_OVERCOMMIT_CPU = "scheduling_overcommit_cpu";
    private static final String SCHEDULING_OVERCOMMIT_MEM = "scheduling_overcommit_mem";
    private static final String SCHEDULING_CIRCUIT_BREAKER_CPU = "scheduling_circuit_breaker_cpu";
    private static final String SCHEDULING_CIRCUIT_BREAKER_MEM = "scheduling_circuit_breaker_mem";

    // Heavy Test Scheduling Configuration
    private static final String TEST_PROFILES_PATH = "test_profiles_path";
    private static final String ELEPHANT_MIN_MS = "elephant_min_ms";
    private static final String SCHEDULING_WEIGHT_HEAVY = "scheduling_weight_heavy";
    private static final String HEAVY_CPU_FACTOR = "heavy_cpu_factor";
    private static final String HEAVY_MEM_FACTOR = "heavy_mem_factor";
    private static final String HEAVY_IO_FACTOR = "heavy_io_factor";
    private static final String EXTREME_IO_CAPACITY_FRACTION = "extreme_io_capacity_fraction";

    private enum ShellTcOverlayMode { AUTO, ENABLED, DISABLED }

    private static final Object SHELL_TC_OVERLAY_LOCK = new Object();

    private Path shellTcSourceDir;
    private Path shellTcEffectiveDir;
    private Path shellTcOverlayDir;
    private ShellTcOverlayMode shellTcOverlayMode;

    public BuilderConfig(String configFile) throws IOException {
        this.properties = new Properties();
        loadConfiguration(configFile);
        validateConfiguration();
        initializeShellTestcaseWorkspace();
    }
    
    private void loadConfiguration(String configFile) throws IOException {
        File file = new File(configFile);
        if (!file.exists()) {
            throw new FileNotFoundException("Configuration file not found: " + configFile);
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            properties.load(fis);
        }
        
        // Expand environment variables in property values
        expandEnvironmentVariables();
    }
    
    private void expandEnvironmentVariables() {
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            String expandedValue = expandEnvironmentVariables(value);
            properties.setProperty(key, expandedValue);
        }
    }
    
    private String expandEnvironmentVariables(String value) {
        if (value == null) {
            return null;
        }
        
        String result = value;
        
        // Expand tilde (~) to home directory
        if (result.startsWith("~/")) {
            String homeDir = System.getProperty("user.home");
            result = result.replace("~/", homeDir + "/");
        }
        
        // Expand environment variables for $VAR format
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            result = result.replace("$" + env.getKey(), env.getValue());
        }
        
        return result;
    }
    
    private void validateConfiguration() throws IllegalArgumentException {
        // Check required properties
        String[] required = {
            CUBRID_SRC_DIR, SHELL_TC_DIR
        };
        
        for (String key : required) {
            if (!properties.containsKey(key)) {
                throw new IllegalArgumentException("Missing required configuration: " + key);
            }
        }
        
        // Validate directories exist (CUBRID_SRC_DIR will be created/cloned automatically)
        validateDirectory(SHELL_TC_DIR);
    }
    
    private void validateDirectory(String key) {
        String path = properties.getProperty(key);
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException(
                "Directory does not exist: " + key + " = " + path);
        }
    }

    private void initializeShellTestcaseWorkspace() throws IOException {
        shellTcSourceDir = resolvePath(properties.getProperty(SHELL_TC_DIR));
        shellTcOverlayMode = parseShellTcOverlayMode(properties.getProperty(SHELL_TC_OVERLAY_MODE, "auto"));

        if (shellTcSourceDir == null || !Files.exists(shellTcSourceDir.resolve(".git"))) {
            shellTcEffectiveDir = shellTcSourceDir;
            shellTcOverlayDir = null;
            return;
        }

        boolean useOverlay = shouldUseOverlay(shellTcOverlayMode, shellTcSourceDir);
        if (!useOverlay) {
            shellTcEffectiveDir = shellTcSourceDir;
            shellTcOverlayDir = null;
            return;
        }

        String overlayDirProp = properties.getProperty(SHELL_TC_OVERLAY_DIR);
        Path candidateOverlayDir;
        if (overlayDirProp != null && !overlayDirProp.trim().isEmpty()) {
            candidateOverlayDir = resolvePath(overlayDirProp);
        } else {
            candidateOverlayDir = resolvePath(Paths.get(getWorkDir()).resolve("shell_tc_overlay").toString());
        }

        if (candidateOverlayDir.equals(shellTcSourceDir)) {
            shellTcEffectiveDir = shellTcSourceDir;
            shellTcOverlayDir = null;
            return;
        }

        shellTcOverlayDir = candidateOverlayDir;
        shellTcEffectiveDir = prepareShellTcOverlay(shellTcSourceDir, shellTcOverlayDir);
    }

    private Path resolvePath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return null;
        }
        return Paths.get(rawPath).toAbsolutePath().normalize();
    }

    private ShellTcOverlayMode parseShellTcOverlayMode(String raw) {
        if (raw == null) {
            return ShellTcOverlayMode.AUTO;
        }
        String normalized = raw.trim().toLowerCase(Locale.ENGLISH);
        switch (normalized) {
            case "enabled":
            case "enable":
            case "true":
                return ShellTcOverlayMode.ENABLED;
            case "disabled":
            case "disable":
            case "false":
                return ShellTcOverlayMode.DISABLED;
            case "auto":
            case "":
                return ShellTcOverlayMode.AUTO;
            default:
                throw new IllegalArgumentException("Invalid shell_tc_overlay_mode: " + raw);
        }
    }

    private boolean shouldUseOverlay(ShellTcOverlayMode mode, Path source) {
        if (mode == ShellTcOverlayMode.ENABLED) {
            return true;
        }
        if (mode == ShellTcOverlayMode.DISABLED) {
            return false;
        }
        try {
            return source == null || !Files.isWritable(source);
        } catch (SecurityException e) {
            return true;
        }
    }

    private Path prepareShellTcOverlay(Path source, Path overlay) throws IOException {
        if (overlay == null) {
            return source;
        }

        Path parent = overlay.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (source != null && overlay.startsWith(source)) {
            throw new IOException("shell_tc_overlay_dir must not reside inside shell_tc_dir: " + overlay);
        }

        synchronized (SHELL_TC_OVERLAY_LOCK) {
            if (Files.exists(overlay) && !Files.exists(overlay.resolve(".git"))) {
                deleteRecursively(overlay);
            }
            if (!Files.exists(overlay.resolve(".git"))) {
                cloneShellTestcases(source, overlay);
            }
            replicateGitRemotes(source, overlay);
        }
        return overlay;
    }

    private void cloneShellTestcases(Path source, Path overlay) throws IOException {
        Path parent = overlay.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        runCommand(parent, true,
            "git", "clone", "--no-hardlinks", source.toString(), overlay.toString());
    }

    private void replicateGitRemotes(Path source, Path overlay) throws IOException {
        CommandResult remoteList = runCommand(source, false, "git", "remote");
        if (!remoteList.isSuccess()) {
            return;
        }

        Set<String> remotes = new LinkedHashSet<>();
        for (String line : remoteList.output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                remotes.add(trimmed);
            }
        }
        if (remotes.isEmpty()) {
            remotes.add("origin");
        }

        for (String remote : remotes) {
            Optional<String> urlOpt = readGitRemoteUrl(source, remote);
            if (!urlOpt.isPresent()) {
                continue;
            }
            String url = urlOpt.get().trim();
            if (url.isEmpty()) {
                continue;
            }
            configureGitRemote(overlay, remote, url);
        }
    }

    private Optional<String> readGitRemoteUrl(Path repo, String remote) throws IOException {
        CommandResult result = runCommand(repo, false,
            "git", "config", "--get", "remote." + remote + ".url");
        if (!result.isSuccess()) {
            return Optional.empty();
        }
        String output = result.output.trim();
        return output.isEmpty() ? Optional.empty() : Optional.of(output);
    }

    private void configureGitRemote(Path repo, String remote, String url) throws IOException {
        CommandResult setResult = runCommand(repo, false,
            "git", "remote", "set-url", remote, url);
        if (setResult.isSuccess()) {
            return;
        }
        CommandResult addResult = runCommand(repo, false,
            "git", "remote", "add", remote, url);
        if (!addResult.isSuccess()) {
            throw new IOException("Failed to configure git remote '" + remote + "' for " + repo + ": " + addResult.output);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private CommandResult runCommand(Path workingDir, String... command) throws IOException {
        return runCommand(workingDir, true, command);
    }

    private CommandResult runCommand(Path workingDir, boolean failOnNonZero, String... command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running command: " + String.join(" ", command), e);
        }
        if (failOnNonZero && exitCode != 0) {
            throw new IOException("Command failed (" + exitCode + "): " + String.join(" ", command) +
                (output.length() > 0 ? System.lineSeparator() + output : ""));
        }
        return new CommandResult(exitCode, output.toString());
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        private boolean isSuccess() {
            return exitCode == 0;
        }
    }
    
    // Getters with defaults
    
    public int getListenPort() {
        return Integer.parseInt(properties.getProperty(LISTEN_PORT, "8089"));
    }
    
    public String getCubridSrcDir() {
        return properties.getProperty(CUBRID_SRC_DIR);
    }
    
    public String getShellTcDir() {
        if (shellTcEffectiveDir == null) {
            return properties.getProperty(SHELL_TC_DIR);
        }
        return shellTcEffectiveDir.toString();
    }

    public String getShellTcSourceDir() {
        return shellTcSourceDir != null ? shellTcSourceDir.toString() : properties.getProperty(SHELL_TC_DIR);
    }

    public boolean isShellTcOverlayActive() {
        return shellTcEffectiveDir != null && shellTcSourceDir != null && !shellTcEffectiveDir.equals(shellTcSourceDir);
    }

    public String getShellTcOverlayDir() {
        return shellTcOverlayDir != null ? shellTcOverlayDir.toString() : null;
    }

    public String getShellTcBranch() {
        return properties.getProperty(SHELL_TC_BRANCH, "develop");
    }

    public String getShellTcPreferredRemote() {
        return properties.getProperty(SHELL_TC_PREFERRED_REMOTE, "upstream");
    }
    
    public String getBuildArg() {
        return properties.getProperty(BUILD_ARG, "-g ninja -c -DENABLE_SYSTEMTAP=OFF build");
    }

    /**
     * Get build directory for a specific build type.
     * Build directory is constructed dynamically: build_x86_64_{buildType}
     *
     * @param buildType "debug" or "release"
     * @return build directory path
     */
    public String getBuildDir(String buildType) {
        if (buildType == null || buildType.trim().isEmpty()) {
            buildType = "debug";
        }
        String mode = buildType.trim().equalsIgnoreCase("release") ? "release" : "debug";
        return "build_x86_64_" + mode;
    }

    /**
     * Get build directory with default type (debug).
     * Kept for backward compatibility.
     */
    public String getBuildDir() {
        return getBuildDir("debug");
    }
    
    public String getWorkDir() {
        return properties.getProperty(WORK_DIR, "/tmp/builder_work");
    }
    
    public int getTesterPort() {
        return Integer.parseInt(properties.getProperty(TESTER_PORT, "8090"));
    }
    
    public int getMaxConcurrentBuilds() {
        return Integer.parseInt(properties.getProperty(MAX_CONCURRENT_BUILDS, "4"));
    }

    public int getMaxConcurrentTests() {
        // Represents the peak concurrency (post-heavy). Falls back to heavy-mode limit if not specified.
        return Math.max(getMaxConcurrentTestsWhileHeavy(), getMaxConcurrentTestsAfterHeavy());
    }

    public int getMaxConcurrentTestsWhileHeavy() {
        String raw = properties.getProperty(MAX_CONCURRENT_TESTS_HEAVY_QUEUE);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                return Math.max(1, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                System.err.println("Invalid integer for " + MAX_CONCURRENT_TESTS_HEAVY_QUEUE + ": " + raw
                        + ". Falling back to legacy max_concurrent_tests.");
            }
        }
        return Math.max(1, Integer.parseInt(properties.getProperty(MAX_CONCURRENT_TESTS, "4")));
    }

    public int getMaxConcurrentTestsAfterHeavy() {
        String raw = properties.getProperty(MAX_CONCURRENT_TESTS_POST_HEAVY);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                return Math.max(1, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                System.err.println("Invalid integer for " + MAX_CONCURRENT_TESTS_POST_HEAVY + ": " + raw
                        + ". Falling back to heavy-queue limit.");
            }
        }
        return getMaxConcurrentTestsWhileHeavy();
    }
    
    public boolean useDocker() {
        return Boolean.parseBoolean(properties.getProperty(USE_DOCKER, "true"));
    }

    public long getShellTcSyncIntervalSeconds() {
        return Long.parseLong(properties.getProperty(SHELL_TC_SYNC_INTERVAL_SECONDS, "300"));
    }

    public boolean usePrebuiltDockerImages() {
        return Boolean.parseBoolean(properties.getProperty(USE_PREBUILT_DOCKER_IMAGES, "true"));
    }
    
    public boolean useDockerForTester() {
        return Boolean.parseBoolean(properties.getProperty("use_docker_tester", "true"));
    }
    
    public String getDockerBuildImage() {
        return properties.getProperty(DOCKER_BUILD_IMAGE, "cubridci/cubridci:develop");
    }
    
    public String getDockerTestImage() {
        return properties.getProperty(DOCKER_TEST_IMAGE, "cubridci/cubridci:test_shell");
    }
    
    public int getBuildCacheSize() {
        return Integer.parseInt(properties.getProperty(BUILD_CACHE_SIZE, "10"));
    }
    
    public boolean getKeepFailedContainers() {
        return Boolean.parseBoolean(properties.getProperty("keep_failed_containers", "true"));
    }

    public int getBuildTimeoutMinutes() {
        return Integer.parseInt(properties.getProperty("build_timeout_minutes", "180"));
    }
    
    public String getDockerHostRoot() {
        return properties.getProperty("docker_host_root", System.getProperty("user.home") + "/docker-work");
    }
    
    public int getMaxRequestLogs() {
        return Integer.parseInt(properties.getProperty(MAX_REQUEST_LOGS, "5"));
    }
    
    public int getMaxTarFiles() {
        return Integer.parseInt(properties.getProperty(MAX_TAR_FILES, "5"));
    }
    
    public boolean isRequestGroupingEnabled() {
        return Boolean.parseBoolean(properties.getProperty(ENABLE_REQUEST_GROUPING, "true"));
    }
    
    /**
     * Check if optimized Docker execution with pre-built images is enabled.
     * Default is true when Docker is enabled.
     */
    public boolean isOptimizedDockerEnabled() {
        return Boolean.parseBoolean(properties.getProperty(OPTIMIZED_DOCKER_ENABLED, "true"));
    }
    
    /**
     * Whether to enforce Docker memory limits based on configured or predicted demand.
     * Default is true with configured limits providing safe resource constraints.
     * When enabled, applies --memory and --memory-swap flags to Docker containers.
     * Set to false to disable memory limits entirely.
     */
    public boolean isDockerEnforceMemoryLimits() {
        return Boolean.parseBoolean(properties.getProperty(DOCKER_ENFORCE_MEMORY_LIMITS, "true"));
    }

    /**
     * Whether to enforce Docker CPU limits based on configured or predicted demand.
     * Default is true with configured limits providing safe resource constraints.
     * When enabled, applies --cpus flag to Docker containers.
     * Set to false to disable CPU limits entirely.
     */
    public boolean isDockerEnforceCpuLimits() {
        return Boolean.parseBoolean(properties.getProperty(DOCKER_ENFORCE_CPU_LIMITS, "true"));
    }

    /**
     * Get the configured CPU limit in millicores for Docker containers.
     * If set, this overrides the predicted demand value.
     * @return CPU limit in millicores, or null to use predicted demand
     */
    public Integer getDockerCpuLimitMillicores() {
        String value = properties.getProperty(DOCKER_CPU_LIMIT_MILLICORES);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int millicores = Integer.parseInt(value.trim());
            return millicores > 0 ? millicores : null;
        } catch (NumberFormatException e) {
            System.err.println("Invalid docker_cpu_limit_millicores value: " + value + ". Using predicted demand.");
            return null;
        }
    }

    /**
     * Get the configured memory limit in MB for Docker containers.
     * If set, this overrides the predicted demand value.
     * @return Memory limit in MB, or null to use predicted demand
     */
    public Integer getDockerMemoryLimitMb() {
        String value = properties.getProperty(DOCKER_MEMORY_LIMIT_MB);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int mb = Integer.parseInt(value.trim());
            return mb > 0 ? mb : null;
        } catch (NumberFormatException e) {
            System.err.println("Invalid docker_memory_limit_mb value: " + value + ". Using predicted demand.");
            return null;
        }
    }

    /**
     * Get the configured I/O read limit in MB/s for Docker containers.
     * If set, this overrides the predicted demand value.
     * @return I/O read limit in MB/s, or null to use predicted demand
     */
    public Integer getDockerIoReadLimitMbps() {
        String value = properties.getProperty(DOCKER_IO_READ_LIMIT_MBPS);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int mbps = Integer.parseInt(value.trim());
            return mbps > 0 ? mbps : null;
        } catch (NumberFormatException e) {
            System.err.println("Invalid docker_io_read_limit_mbps value: " + value + ". Using predicted demand.");
            return null;
        }
    }

    /**
     * Get the configured I/O write limit in MB/s for Docker containers.
     * If set, this overrides the predicted demand value.
     * @return I/O write limit in MB/s, or null to use predicted demand
     */
    public Integer getDockerIoWriteLimitMbps() {
        String value = properties.getProperty(DOCKER_IO_WRITE_LIMIT_MBPS);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            int mbps = Integer.parseInt(value.trim());
            return mbps > 0 ? mbps : null;
        } catch (NumberFormatException e) {
            System.err.println("Invalid docker_io_write_limit_mbps value: " + value + ". Using predicted demand.");
            return null;
        }
    }

    /**
     * Get the configured block device for Docker I/O limits.
     * If not set, the system will attempt to auto-detect the root device.
     * @return Device path (e.g., "/dev/sda"), or null for auto-detection
     */
    public String getDockerIoDevice() {
        String value = properties.getProperty(DOCKER_IO_DEVICE);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    // ---- V2 unified config with migration from v1 (retry_count) ----
    public String getRunMode() {
        String mode = properties.getProperty(RUN_MODE, "until-pass").toLowerCase();
        if (!mode.equals("until-pass") && !mode.equals("until-fail") && !mode.equals("fixed-runs")) {
            System.err.println("Invalid run_mode '" + mode + "' in builder.conf. Using default 'until-pass'");
            return "until-pass";
        }
        return mode;
    }

    public int getMinRuns() {
        String mode = getRunMode();
        Integer configured = parsePositiveInt(properties.getProperty(MIN_RUNS, null));
        if (configured != null) {
            return Math.max(1, configured);
        }
        // Migrate from v1 retry_count
        int retry = getRetryCountForMigration();
        if ("fixed-runs".equals(mode)) {
            return Math.max(1, retry);
        }
        return 1; // until-pass / until-fail default lower bound
    }

    public int getMaxRuns() {
        String mode = getRunMode();
        Integer configured = parsePositiveInt(properties.getProperty(MAX_RUNS, null));
        if (configured != null) {
            return Math.max(1, configured);
        }
        // Migrate from v1 retry_count
        int retry = getRetryCountForMigration();
        if ("until-pass".equals(mode)) {
            return 1 + Math.max(0, retry);
        } else if ("until-fail".equals(mode)) {
            int mr = Math.max(1, retry);
            if (retry == 0) {
                System.err.println("[config] WARNING: retry_count=0 no longer means unlimited; capped at 1. Use time_budget_ms or a large max_runs.");
            }
            return mr;
        } else { // fixed-runs
            int mr = Math.max(1, retry);
            if (retry == 0) {
                System.err.println("[config] WARNING: fixed-runs requires ≥1 run; upgraded to 1.");
            }
            return mr;
        }
    }

    public Long getTimeBudgetMs() {
        String v = properties.getProperty(TIME_BUDGET_MS, null);
        if (v == null || v.trim().isEmpty() || v.trim().equalsIgnoreCase("null")) return null;
        try {
            long val = Long.parseLong(v.trim());
            return val >= 1 ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parsePositiveInt(String v) {
        if (v == null) return null;
        try {
            int i = Integer.parseInt(v.trim());
            return i >= 1 ? i : 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int getRetryCountForMigration() {
        try {
            return Math.max(0, Integer.parseInt(properties.getProperty(RETRY_COUNT, "0").trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Test execution mode that determines how tests are run.
     * - until-pass: Run up to retry_count attempts or until first success (default)
     * - until-fail: Run repeatedly until first failure (reproduce mode)
     * - fixed-runs: Run exactly retry_count times regardless of pass/fail
     * Configured in builder.conf; default is "until-pass" when not specified.
     */
    // Removed retry_count feature usage; callers must use min/max runs.

    /**
     * Tester-side preferred HTTP read timeout, in minutes, for Builder -> Tester /test calls.
     * Default is 60 minutes when not specified in tester.conf.
     */
    public int getTestReadTimeoutMinutes() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(TEST_READ_TIMEOUT_MINUTES, "60"));
        } catch (NumberFormatException e) {
            value = 60;
        }
        return Math.max(1, value);
    }
    
    /**
     * Get the HTTP connect timeout for fetching logs from remote testers (in seconds).
     * Default is 10 seconds.
     */
    public int getLogFetchConnectTimeoutSeconds() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(LOG_FETCH_CONNECT_TIMEOUT_SECONDS, "10"));
        } catch (NumberFormatException e) {
            value = 10;
        }
        return Math.max(1, value);
    }
    
    /**
     * Get the HTTP read timeout for fetching logs from remote testers (in seconds).
     * Default is 120 seconds (2 minutes) to handle large log files.
     */
    public int getLogFetchReadTimeoutSeconds() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(LOG_FETCH_READ_TIMEOUT_SECONDS, "120"));
        } catch (NumberFormatException e) {
            value = 120;
        }
        return Math.max(1, value);
    }
    
    /**
     * Get the timeout for verifying log file availability after write (in seconds).
     * This prevents timing issues where files are written but not yet accessible via HTTP.
     * Default is 5 seconds.
     */
    public int getLogFileVerificationTimeoutSeconds() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(LOG_FILE_VERIFICATION_TIMEOUT_SECONDS, "5"));
        } catch (NumberFormatException e) {
            value = 5;
        }
        return Math.max(1, value);
    }
    
    // Ccache configuration methods
    
    public boolean isCcacheEnabled() {
        return Boolean.parseBoolean(properties.getProperty(CCACHE_ENABLED, "true"));
    }
    
    public String getCcacheDir() {
        String dir = properties.getProperty(CCACHE_DIR, System.getProperty("user.home") + "/ccache");
        return expandEnvironmentVariables(dir);
    }
    
    public String getCcacheMaxSize() {
        return properties.getProperty(CCACHE_MAX_SIZE, "15G");
    }
    
    public String getCcacheCompilerCheck() {
        return properties.getProperty(CCACHE_COMPILERCHECK, "content");
    }
    
    public boolean getCcacheHardlink() {
        return Boolean.parseBoolean(properties.getProperty(CCACHE_HARDLINK, "true"));
    }
    
    public boolean getCcacheReadonlyDirect() {
        return Boolean.parseBoolean(properties.getProperty(CCACHE_READONLY_DIRECT, "false"));
    }
    
    public boolean getCcacheStatsEnabled() {
        return Boolean.parseBoolean(properties.getProperty(CCACHE_STATS, "true"));
    }
    
    public String getCcacheNamespace() {
        return properties.getProperty(CCACHE_NAMESPACE, "").trim();
    }
    
    public String getCcacheSloppiness() {
        return properties.getProperty(CCACHE_SLOPPINESS, "").trim();
    }
    
    public int getParallelJobs() {
        int value;
        try {
            value = Integer.parseInt(properties.getProperty(PARALLEL_JOBS, "0"));
        } catch (NumberFormatException e) {
            value = 0;
        }
        // 0 means auto-detect from CPU cores
        if (value == 0) {
            value = Runtime.getRuntime().availableProcessors();
        }
        return Math.max(1, value);
    }
    
    /**
     * Gets a long value from configuration with a default fallback.
     *
     * @param key The configuration key
     * @param defaultValue The default value if key is not found or invalid
     * @return The configured long value or default
     */
    public long getLongOrDefault(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid long value for key '" + key + "': " + value + ". Using default: " + defaultValue);
            return defaultValue;
        }
    }

    // Smart scheduling configuration getters

    public boolean isSmartSchedulingEnabled() {
        return Boolean.parseBoolean(properties.getProperty(SMART_SCHEDULING_ENABLED, "false"));
    }

    public boolean isPullSchedulingEnabled() {
        return Boolean.parseBoolean(properties.getProperty(PULL_SCHEDULING_ENABLED, "false"));
    }

    public long getSchedulingMiceThresholdMs() {
        return Long.parseLong(properties.getProperty(SCHEDULING_MICE_THRESHOLD_MS, "30000"));
    }

    public double getSchedulingElephantWeight() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_ELEPHANT_WEIGHT, "0.80"));
    }

    public double getSchedulingElephantLoadPenalty() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_ELEPHANT_LOAD_PENALTY, "0.30"));
    }

    public double getSchedulingWeightPressure() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_WEIGHT_PRESSURE, "0.45"));
    }

    public double getSchedulingWeightDuration() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_WEIGHT_DURATION, "0.25"));
    }

    public double getSchedulingWeightImageCache() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_WEIGHT_IMAGE_CACHE, "0.15"));
    }

    public double getSchedulingWeightPackageCache() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_WEIGHT_PACKAGE_CACHE, "0.05"));
    }

    public double getSchedulingWeightAgeBoost() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_WEIGHT_AGE_BOOST, "0.10"));
    }

    public long getSchedulingNodePollIntervalSeconds() {
        return Long.parseLong(properties.getProperty(SCHEDULING_NODE_POLL_INTERVAL_SECONDS, "5"));
    }

    public long getSchedulingNodeStaleThresholdSeconds() {
        return Long.parseLong(properties.getProperty(SCHEDULING_NODE_STALE_THRESHOLD_SECONDS, "30"));
    }

    public double getSchedulingIoLightThreshold() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_IO_LIGHT_THRESHOLD, "0.30"));
    }

    public double getSchedulingIoHeavyThreshold() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_IO_HEAVY_THRESHOLD, "20.0"));
    }

    public double getSchedulingMixLongFraction() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MIX_LONG_FRACTION, "0.40"));
    }

    public double getSchedulingMixMediumFraction() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MIX_MEDIUM_FRACTION, "0.30"));
    }

    public double getSchedulingMixShortFraction() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MIX_SHORT_FRACTION, "0.30"));
    }

    // Dimension-specific margin configuration getters

    public double getSchedulingMarginCpuBase() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MARGIN_CPU_BASE, "0.10"));
    }

    public double getSchedulingMarginMemBase() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MARGIN_MEM_BASE, "0.20"));
    }

    public double getSchedulingMarginIoBase() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MARGIN_IO_BASE, "0.30"));
    }

    public double getSchedulingMarginNetBase() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MARGIN_NET_BASE, "0.25"));
    }

    public double getSchedulingMarginIopsBase() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MARGIN_IOPS_BASE, "0.25"));
    }

    public double getSchedulingMarginConfidenceFactor() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_MARGIN_CONFIDENCE_FACTOR, "0.50"));
    }

    public boolean useIopsPredictions() {
        return Boolean.parseBoolean(properties.getProperty(USE_IOPS_PREDICTIONS, "false"));
    }

    // IO-first scheduling weights
    public double getSchedulingWeightIo() {
        return Double.parseDouble(properties.getProperty("scheduling_weight_io", "2.50"));
    }

    public double getSchedulingWeightCpu() {
        return Double.parseDouble(properties.getProperty("scheduling_weight_cpu", "1.00"));
    }

    public double getSchedulingWeightMem() {
        return Double.parseDouble(properties.getProperty("scheduling_weight_mem", "1.10"));
    }

    public double getSchedulingWeightNet() {
        return Double.parseDouble(properties.getProperty("scheduling_weight_net", "0.80"));
    }

    // IO margin configuration
    public double getSchedulingMarginIoReadBase() {
        return Double.parseDouble(properties.getProperty("scheduling_margin_io_read_base", "0.35"));
    }

    public double getSchedulingMarginIoWriteBase() {
        return Double.parseDouble(properties.getProperty("scheduling_margin_io_write_base", "0.35"));
    }

    public double getIoSafetyHeadroomRatio() {
        return Double.parseDouble(properties.getProperty("io_safety_headroom_ratio", "0.15"));
    }

    // Elastic Overcommit Getters
    public double getSchedulingOvercommitCpu() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_OVERCOMMIT_CPU, "1.0"));
    }

    public double getSchedulingOvercommitMem() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_OVERCOMMIT_MEM, "1.0"));
    }

    public double getSchedulingCircuitBreakerCpu() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_CIRCUIT_BREAKER_CPU, "90.0"));
    }

    public double getSchedulingCircuitBreakerMem() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_CIRCUIT_BREAKER_MEM, "90.0"));
    }

    // Heavy Test Scheduling Getters

    /**
     * Path to the pre-computed test profiles JSON file.
     * Default is "conf/test_profiles.json".
     */
    public String getTestProfilesPath() {
        return properties.getProperty(TEST_PROFILES_PATH, "conf/test_profiles.json");
    }

    /**
     * Minimum threshold in milliseconds for the elephant queue.
     * Tests shorter than this will go to the mice queue unless they are HEAVY/EXTREME.
     * Default is 60000 (60 seconds).
     */
    public long getElephantMinMs() {
        return Long.parseLong(properties.getProperty(ELEPHANT_MIN_MS, "60000"));
    }

    /**
     * Weight for heavy test penalty in the scoring function.
     * This is a soft preference for spreading heavy tests across nodes.
     * With a single tester, this has no effect since there's only one node.
     * Default is 0.05 (conservative - small nudge, not a blocker).
     */
    public double getSchedulingWeightHeavy() {
        return Double.parseDouble(properties.getProperty(SCHEDULING_WEIGHT_HEAVY, "0.05"));
    }

    /**
     * CPU inflation factor for HEAVY tests.
     * Default is 1.15 (15% inflation - conservative to avoid over-reserving).
     */
    public double getHeavyCpuFactor() {
        return Double.parseDouble(properties.getProperty(HEAVY_CPU_FACTOR, "1.15"));
    }

    /**
     * Memory inflation factor for HEAVY tests.
     * Default is 1.15 (15% inflation - conservative to avoid over-reserving).
     */
    public double getHeavyMemFactor() {
        return Double.parseDouble(properties.getProperty(HEAVY_MEM_FACTOR, "1.15"));
    }

    /**
     * I/O and IOPS inflation factor for HEAVY tests.
     * Default is 1.25 (25% inflation - conservative to avoid over-reserving).
     */
    public double getHeavyIoFactor() {
        return Double.parseDouble(properties.getProperty(HEAVY_IO_FACTOR, "1.25"));
    }

    /**
     * Fraction of node I/O capacity to claim for EXTREME tests.
     * Lower values allow more EXTREME tests to coexist on the same node.
     * Default is 0.5 (allows ~2 EXTREME tests per node).
     */
    public double getExtremeIoCapacityFraction() {
        return Double.parseDouble(properties.getProperty(EXTREME_IO_CAPACITY_FRACTION, "0.5"));
    }

    @Override
    public String toString() {
        return "BuilderConfig{" +
               "listenPort=" + getListenPort() +
               ", cubridSrcDir='" + getCubridSrcDir() + '\'' +
               ", shellTcDir='" + getShellTcDir() + '\'' +
               (isShellTcOverlayActive() ? ", shellTcSourceDir='" + getShellTcSourceDir() + '\'' : "") +
               ", buildArg='" + getBuildArg() + '\'' +
               ", buildDir='" + getBuildDir() + '\'' +
               ", workDir='" + getWorkDir() + '\'' +
               ", testerPort=" + getTesterPort() +
               ", maxConcurrentBuilds=" + getMaxConcurrentBuilds() +
               ", maxConcurrentTests=" + getMaxConcurrentTests() +
               ", useDocker=" + useDocker() +
               ", buildCacheSize=" + getBuildCacheSize() +
               '}';
    }
}
