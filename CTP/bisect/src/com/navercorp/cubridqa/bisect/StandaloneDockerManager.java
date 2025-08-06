/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.bisect;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.concurrent.TimeUnit;

/**
 * StandaloneDockerManager - Manages combined build and test Docker environment
 * 
 * This class creates and manages a Docker environment that can both build CUBRID
 * and execute tests in a single container, eliminating the need for separate
 * producer and consumer nodes.
 * 
 * The container is based on the CentOS 6 build image but enhanced with test
 * execution capabilities including SSH, test tools, and proper environment setup.
 */
public class StandaloneDockerManager {
    private static final Logger logger = Logger.getLogger(StandaloneDockerManager.class.getName());
    
    private static final String STANDALONE_IMAGE = "cubrid-bisect-standalone:latest";
    private static final String BASE_BUILD_IMAGE = "cubrid-bisect-builder:latest";
    
    private final BisectConfig config;
    private boolean dockerAvailable;
    private boolean imageReady;
    
    public StandaloneDockerManager(BisectConfig config) {
        this.config = config;
        this.dockerAvailable = DockerUtils.isDockerAvailable();
        this.imageReady = false;
        
        if (!dockerAvailable) {
            logger.warning("Docker is not available. Cannot use standalone mode.");
        }
    }
    
    /**
     * Initialize standalone Docker environment
     */
    public void initialize() throws IOException, InterruptedException {
        if (!dockerAvailable) {
            throw new IllegalStateException("Docker is required for standalone mode");
        }
        
        logger.info("Initializing standalone Docker environment...");
        
        // Ensure cubridci repository is available
        ensureCubridCIRepository();
        
        // Build base builder image first (from develop branch)
        buildBaseBuilderImage();
        
        // Build enhanced standalone image
        buildStandaloneImage();
        
        imageReady = true;
        logger.info("Standalone Docker environment ready");
    }
    
    /**
     * Ensure cubridci repository is cloned and available
     */
    private void ensureCubridCIRepository() throws IOException, InterruptedException {
        File cubridciDir = new File(System.getProperty("user.home"), "cubridci");
        
        if (!cubridciDir.exists()) {
            logger.info("Cloning cubridci repository...");
            ProcessBuilder pb = new ProcessBuilder(
                "git", "clone",
                "https://github.com/CUBRID/cubridci.git",
                cubridciDir.getAbsolutePath()
            );
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to clone cubridci repository");
            }
        }
        
        // Fetch develop branch for build environment
        logger.info("Fetching develop branch...");
        ProcessBuilder pb = new ProcessBuilder("git", "fetch", "origin", "develop:develop");
        pb.directory(cubridciDir);
        Process process = pb.start();
        process.waitFor();
        
        logger.info("CubridCI repository ready at: " + cubridciDir.getAbsolutePath());
    }
    
    /**
     * Build base builder image from cubridci develop branch
     */
    private void buildBaseBuilderImage() throws IOException, InterruptedException {
        File cubridciDir = new File(System.getProperty("user.home"), "cubridci");
        
        // Switch to develop branch
        logger.info("Switching to develop branch for base build environment...");
        ProcessBuilder pb = new ProcessBuilder("git", "checkout", "develop");
        pb.directory(cubridciDir);
        Process process = pb.start();
        process.waitFor();
        
        String dockerfilePath = cubridciDir.getAbsolutePath() + "/docker/ci/Dockerfile";
        String contextPath = cubridciDir.getAbsolutePath() + "/docker/ci";
        
        // Check if Dockerfile exists
        if (!new File(dockerfilePath).exists()) {
            throw new IOException("Dockerfile not found at: " + dockerfilePath);
        }
        
        logger.info("Building base Docker image: " + BASE_BUILD_IMAGE);
        DockerUtils.buildImage(dockerfilePath, BASE_BUILD_IMAGE, contextPath);
    }
    
    /**
     * Build enhanced standalone image with test capabilities
     */
    private void buildStandaloneImage() throws IOException, InterruptedException {
        logger.info("Building standalone Docker image with test capabilities...");
        
        // Create temporary directory for standalone Dockerfile
        Path tempDir = Files.createTempDirectory("standalone_docker_");
        
        try {
            // Create enhanced Dockerfile for standalone mode
            File dockerFile = new File(tempDir.toFile(), "Dockerfile.standalone");
            createStandaloneDockerfile(dockerFile);
            
            // Create entrypoint script
            File entrypointFile = new File(tempDir.toFile(), "docker-entrypoint-standalone.sh");
            createStandaloneEntrypoint(entrypointFile);
            
            // Build the standalone image
            logger.info("Building Docker image: " + STANDALONE_IMAGE);
            DockerUtils.buildImage(
                dockerFile.getAbsolutePath(),
                STANDALONE_IMAGE,
                tempDir.toString()
            );
            
            logger.info("Successfully built standalone image: " + STANDALONE_IMAGE);
            
        } finally {
            // Cleanup temporary directory
            deleteDirectory(tempDir.toFile());
        }
    }
    
    /**
     * Create Dockerfile for standalone mode
     */
    private void createStandaloneDockerfile(File dockerFile) throws IOException {
        String dockerfile = 
            "# Standalone CUBRID Bisect Environment\n" +
            "# Based on CentOS 6 builder image, enhanced with test capabilities\n" +
            "FROM " + BASE_BUILD_IMAGE + "\n" +
            "\n" +
            "LABEL Description=\"Standalone build and test environment for CUBRID bisect\"\n" +
            "\n" +
            "# Install additional packages needed for testing\n" +
            "RUN yum install -y \\\n" +
            "    openssh-server \\\n" +
            "    openssh-clients \\\n" +
            "    java-1.8.0-openjdk-devel \\\n" +
            "    libxslt \\\n" +
            "    dos2unix \\\n" +
            "    lcov \\\n" +
            "    bc \\\n" +
            "    jq \\\n" +
            "    expect \\\n" +
            "    && yum clean all\n" +
            "\n" +
            "# SSH setup and user configuration\n" +
            "RUN ssh-keygen -A && \\\n" +
            "    sed -i 's/^account.*pam_nologin.so/#&/' /etc/pam.d/sshd && \\\n" +
            "    mkdir -p /var/run/sshd && \\\n" +
            "    mkdir -p /home/do_not_delete_core /home/ERROR_BACKUP && \\\n" +
            "    ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \\\n" +
            "    echo \"Asia/Seoul\" > /etc/timezone\n" +
            "\n" +
            "# CircleCI environment variables\n" +
            "ENV TEST_REPORT=/tmp/tests\n" +
            "ENV BASELINE=none\n" +
            "ENV BRANCH_TESTTOOLS=develop\n" +
            "ENV BRANCH_TESTCASES=develop\n" +
            "ENV WORKDIR=/home\n" +
            "ENV USER=root\n" +
            "\n" +
            "# TestTools environment\n" +
            "ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0\n" +
            "ENV CTP_BRANCH_NAME=$BRANCH_TESTTOOLS\n" +
            "ENV CTP_SKIP_UPDATE=0\n" +
            "ENV CTP_HOME=$WORKDIR/cubrid-testtools/CTP\n" +
            "ENV init_path=$CTP_HOME/shell/init_path\n" +
            "ENV PATH=$CTP_HOME/bin:$CTP_HOME/common/script:$PATH\n" +
            "\n" +
            "# CUBRID environment\n" +
            "ENV CUBRID=$WORKDIR/CUBRID\n" +
            "ENV CUBRID_DATABASES=$CUBRID/databases\n" +
            "ENV LD_LIBRARY_PATH=$CUBRID/lib:$CUBRID/cci/lib:$LD_LIBRARY_PATH\n" +
            "ENV SHLIB_PATH=$LD_LIBRARY_PATH\n" +
            "ENV LIBPATH=$LD_LIBRARY_PATH\n" +
            "ENV PATH=$CUBRID/bin:/usr/sbin:$PATH\n" +
            "\n" +
            "# Copy entrypoint script\n" +
            "COPY docker-entrypoint-standalone.sh /entrypoint.sh\n" +
            "RUN chmod +x /entrypoint.sh\n" +
            "\n" +
            "# Set permissions\n" +
            "RUN chmod 777 $WORKDIR\n" +
            "\n" +
            "WORKDIR $WORKDIR\n" +
            "\n" +
            "ENTRYPOINT [\"/entrypoint.sh\"]\n";
        
        Files.write(dockerFile.toPath(), dockerfile.getBytes());
    }
    
    /**
     * Create entrypoint script for standalone mode
     */
    private void createStandaloneEntrypoint(File entrypointFile) throws IOException {
        String entrypoint = 
            "#!/bin/bash\n" +
            "# Standalone Docker entrypoint\n" +
            "set -e\n" +
            "\n" +
            "# Function to setup environment\n" +
            "setup_environment() {\n" +
            "    echo \"Setting up standalone environment...\"\n" +
            "    \n" +
            "    # Ensure required directories exist\n" +
            "    mkdir -p /home/cubrid-testtools\n" +
            "    mkdir -p /home/cubrid-testcases\n" +
            "    mkdir -p /home/CUBRID\n" +
            "    mkdir -p /home/CUBRID/databases\n" +
            "    mkdir -p /tmp/tests\n" +
            "    \n" +
            "    # Setup SSH if needed\n" +
            "    if [ ! -f /etc/ssh/ssh_host_rsa_key ]; then\n" +
            "        ssh-keygen -A\n" +
            "    fi\n" +
            "    \n" +
            "    # Start SSH service if needed\n" +
            "    if [ -x /usr/sbin/sshd ]; then\n" +
            "        /usr/sbin/sshd -D &\n" +
            "    fi\n" +
            "    \n" +
            "    echo \"Environment setup complete\"\n" +
            "}\n" +
            "\n" +
            "# Setup environment on startup\n" +
            "setup_environment\n" +
            "\n" +
            "# Execute the command passed to docker run\n" +
            "exec \"$@\"\n";
        
        Files.write(entrypointFile.toPath(), entrypoint.getBytes());
        entrypointFile.setExecutable(true);
    }
    
    /**
     * Execute build and test in standalone container
     */
    public BisectResult executeBuildAndTest(String commitHash, String testPath, String buildType) 
            throws IOException, InterruptedException {
        
        if (!imageReady) {
            throw new IllegalStateException("Standalone Docker environment not initialized");
        }
        
        logger.info(String.format("Executing standalone build and test for commit %s, test %s", 
            commitHash, testPath));
        
        // Create temporary work directory
        Path workDir = Files.createTempDirectory("standalone_work_");
        
        try {
            // Create execution script
            File executionScript = createStandaloneExecutionScript(
                workDir.toFile(), commitHash, testPath, buildType
            );
            
            // Prepare volumes
            List<String> volumes = new ArrayList<>();
            volumes.add(config.getCubridSrcDir() + ":/cubrid-src:ro");
            volumes.add(config.getShellTcDir() + ":/test-cases:ro");
            volumes.add(workDir.toString() + ":/workspace:rw");
            volumes.add(executionScript.getAbsolutePath() + ":/execute.sh:ro");
            
            // Environment variables
            Map<String, String> envVars = new HashMap<>();
            envVars.put("COMMIT_HASH", commitHash);
            envVars.put("TEST_PATH", testPath);
            envVars.put("BUILD_TYPE", buildType);
            
            // Execute in standalone container
            logger.info("Starting standalone container for build and test...");
            DockerUtils.CommandResult result = DockerUtils.executeInContainer(
                STANDALONE_IMAGE, volumes, envVars, 
                "bash", "/execute.sh"
            );
            
            // Parse result
            BisectResult bisectResult = parseStandaloneResult(
                result, workDir.toFile(), commitHash, testPath
            );
            
            logger.info(String.format("Standalone execution completed: %s", 
                bisectResult.testPassed ? "PASS" : "FAIL"));
            
            return bisectResult;
            
        } finally {
            // Cleanup work directory
            deleteDirectory(workDir.toFile());
        }
    }
    
    /**
     * Create execution script for standalone mode
     */
    private File createStandaloneExecutionScript(File workDir, String commitHash, 
                                                 String testPath, String buildType) 
            throws IOException {
        
        File script = new File(workDir, "standalone_execute.sh");
        
        String scriptContent = 
            "#!/bin/bash\n" +
            "# Standalone execution script for commit " + commitHash + "\n" +
            "set -e\n" +
            "\n" +
            "echo \"========================================\"\n" +
            "echo \"STANDALONE BISECT EXECUTION\"\n" +
            "echo \"Commit: ${COMMIT_HASH}\"\n" +
            "echo \"Test: ${TEST_PATH}\"\n" +
            "echo \"Build Type: ${BUILD_TYPE}\"\n" +
            "echo \"========================================\"\n" +
            "\n" +
            "# Phase 1: Build CUBRID\n" +
            "echo \"Phase 1: Building CUBRID...\"\n" +
            "cp -r /cubrid-src /tmp/cubrid-build\n" +
            "cd /tmp/cubrid-build\n" +
            "\n" +
            "# Checkout specific commit\n" +
            "git checkout ${COMMIT_HASH}\n" +
            "git submodule update --init --recursive\n" +
            "\n" +
            "# Clean previous builds\n" +
            "rm -rf build_x86_64_*\n" +
            "\n" +
            "# Build CUBRID\n" +
            "./build.sh " + config.getBuildArg() + "\n" +
            "\n" +
            "# Install CUBRID\n" +
            "echo \"Installing CUBRID...\"\n" +
            "rm -rf /home/CUBRID\n" +
            "mkdir -p /home/CUBRID\n" +
            "cd " + config.getBuildDir() + "\n" +
            "cp -r * /home/CUBRID/\n" +
            "\n" +
            "# Set up CUBRID environment\n" +
            "export CUBRID=/home/CUBRID\n" +
            "export CUBRID_DATABASES=$CUBRID/databases\n" +
            "export PATH=$CUBRID/bin:$PATH\n" +
            "export LD_LIBRARY_PATH=$CUBRID/lib:$LD_LIBRARY_PATH\n" +
            "\n" +
            "# Verify installation\n" +
            "echo \"Verifying CUBRID installation...\"\n" +
            "cubrid_rel || true\n" +
            "\n" +
            "# Phase 2: Run Test\n" +
            "echo \"Phase 2: Running test...\"\n" +
            "\n" +
            "# Extract test directory and script from path\n" +
            "TEST_DIR=$(dirname /test-cases/${TEST_PATH})\n" +
            "TEST_SCRIPT=$(basename ${TEST_PATH})\n" +
            "\n" +
            "# Create test work directory\n" +
            "mkdir -p /workspace/test_work\n" +
            "cp -r $TEST_DIR/* /workspace/test_work/\n" +
            "cd /workspace/test_work\n" +
            "\n" +
            "# Run the test\n" +
            "echo \"Executing test script: $TEST_SCRIPT\"\n" +
            "bash $TEST_SCRIPT\n" +
            "TEST_EXIT_CODE=$?\n" +
            "\n" +
            "# Check for result file\n" +
            "RESULT_FILE=\"${TEST_SCRIPT%.sh}.result\"\n" +
            "if [ -f \"$RESULT_FILE\" ]; then\n" +
            "    echo \"Test result file found: $RESULT_FILE\"\n" +
            "    cat $RESULT_FILE\n" +
            "    cp $RESULT_FILE /workspace/test.result\n" +
            "    \n" +
            "    # Determine test status\n" +
            "    if grep -q \"NOK\\|FAIL\" $RESULT_FILE; then\n" +
            "        echo \"TEST_STATUS=FAIL\" > /workspace/status.txt\n" +
            "        exit 1\n" +
            "    elif grep -q \"OK\\|PASS\" $RESULT_FILE; then\n" +
            "        echo \"TEST_STATUS=PASS\" > /workspace/status.txt\n" +
            "        exit 0\n" +
            "    else\n" +
            "        echo \"TEST_STATUS=UNKNOWN\" > /workspace/status.txt\n" +
            "        exit 2\n" +
            "    fi\n" +
            "else\n" +
            "    echo \"No result file generated\"\n" +
            "    if [ $TEST_EXIT_CODE -eq 0 ]; then\n" +
            "        echo \"TEST_STATUS=PASS\" > /workspace/status.txt\n" +
            "        exit 0\n" +
            "    else\n" +
            "        echo \"TEST_STATUS=FAIL\" > /workspace/status.txt\n" +
            "        exit 1\n" +
            "    fi\n" +
            "fi\n";
        
        Files.write(script.toPath(), scriptContent.getBytes());
        script.setExecutable(true);
        
        return script;
    }
    
    /**
     * Parse result from standalone execution
     */
    private BisectResult parseStandaloneResult(DockerUtils.CommandResult dockerResult, 
                                              File workDir, String commitHash, String testPath) {
        BisectResult result = new BisectResult();
        result.commitHash = commitHash;
        result.testPath = testPath;
        
        // Check status file
        File statusFile = new File(workDir, "status.txt");
        if (statusFile.exists()) {
            try {
                String status = new String(Files.readAllBytes(statusFile.toPath())).trim();
                result.testPassed = status.contains("PASS");
                result.message = "Test status: " + status;
            } catch (IOException e) {
                logger.warning("Failed to read status file: " + e.getMessage());
            }
        }
        
        // Check exit code as fallback
        if (result.message == null) {
            if (dockerResult.isSuccess()) {
                result.testPassed = true;
                result.message = "Test passed (exit code 0)";
            } else {
                result.testPassed = false;
                result.message = "Test failed (exit code " + dockerResult.exitCode + ")";
            }
        }
        
        // Add output for debugging
        result.output = dockerResult.stdout;
        result.error = dockerResult.stderr;
        
        return result;
    }
    
    /**
     * Check if Docker is available and image is ready
     */
    public boolean isReady() {
        return dockerAvailable && imageReady;
    }
    
    /**
     * Get the standalone image name
     */
    public String getImageName() {
        return STANDALONE_IMAGE;
    }
    
    /**
     * Delete directory recursively
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
    
    /**
     * Result of a standalone bisect execution
     */
    public static class BisectResult {
        public String commitHash;
        public String testPath;
        public boolean testPassed;
        public String message;
        public String output;
        public String error;
        
        @Override
        public String toString() {
            return String.format("BisectResult{commit=%s, test=%s, passed=%s, message=%s}",
                commitHash, testPath, testPassed, message);
        }
    }
}
