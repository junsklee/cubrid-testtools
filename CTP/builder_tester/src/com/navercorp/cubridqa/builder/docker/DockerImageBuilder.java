/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 */
package com.navercorp.cubridqa.builder.docker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import com.navercorp.cubridqa.builder.BuilderConfig;

/**
 * DockerImageBuilder - Builds and manages Docker images with pre-installed CUBRID builds
 * 
 * This class creates custom Docker images for each CUBRID build to eliminate
 * repeated extraction and setup overhead during test execution.
 * Works with both local and remote tester nodes.
 */
public class DockerImageBuilder {
    private static final Logger logger = Logger.getLogger(DockerImageBuilder.class.getName());
    
    private final BuilderConfig config;
    private final Path workDir;
    private final Map<String, String> imageCache = new ConcurrentHashMap<>();
    private final Map<String, Long> imageBuildTime = new ConcurrentHashMap<>();
    private final int maxCachedImages;
    private final Object buildLock = new Object();
    private final ThreadLocal<Boolean> lastOperationCacheHit = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // The base image id only changes when something pulls the tag, so it is re-read at most this
    // often rather than with a docker subprocess per call.
    private static final long BASE_IMAGE_ID_TTL_MS = 30_000L;
    private volatile String baseImageId = "";
    private volatile long baseImageIdResolvedAt;
    private final Object pullLock = new Object();
    // The image hasImage last found on this thread. An executor skips the package download when
    // hasImage says yes, so getOrBuildImage must be able to return that image even if the base
    // id moved in between and the current key names nothing.
    private final ThreadLocal<String> lastFoundImage = new ThreadLocal<>();

    public DockerImageBuilder(BuilderConfig config) {
        this.config = config;
        this.workDir = Paths.get(config.getWorkDir()).resolve("docker_images");
        this.maxCachedImages = config.getBuildCacheSize() > 0 ? config.getBuildCacheSize() : 20;
        
        try {
            Files.createDirectories(workDir);
            loadExistingImages();
        } catch (IOException e) {
            logger.warning("Failed to create docker images work directory: " + e.getMessage());
        }
    }
    
    /**
     * The image variant for a test request: baseline, build type and the Builder build directory
     * the package came from (build_&lt;commit&gt;_&lt;id&gt;, the same for a local path and a download URL).
     * The commit alone does not identify a binary: release and debug builds of one commit, or one
     * commit cherry-picked onto different baselines, must not share an image.
     */
    public static String imageVariant(String baselineShort, String buildType, String buildPackageRef) {
        String type = buildType == null || buildType.trim().isEmpty() ? "release" : buildType.trim();
        StringBuilder variant = new StringBuilder();
        variant.append(baselineShort == null || baselineShort.isEmpty() ? "unknown" : baselineShort)
               .append('_').append(type);
        String buildDir = buildDirectoryOf(buildPackageRef);
        if (buildDir != null) {
            variant.append('_').append(shortHash(buildDir));
        }
        return variant.toString().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Name of the build_* directory in a package path or URL, or null. */
    public static String buildDirectoryOf(String buildPackageRef) {
        if (buildPackageRef == null) {
            return null;
        }
        String path = buildPackageRef.replace('\\', '/');
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        String[] parts = path.split("/");
        for (int i = parts.length - 2; i >= 0; i--) {
            if (parts[i].startsWith("build_")) {
                return parts[i];
            }
        }
        return null;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * Shell lines that install ncurses 5 (ncurses-compat-libs) when the build at cubridHome needs
     * it and the image has none. 11.0 and older builds link libncurses.so.5 / libtinfo.so.5 (11.5's
     * cubrid.sh links those names to ncurses 6 itself); neither test image ships ncurses 5, so their
     * cubrid_rel could not start and every test was an execution error. Regression VMs have the
     * package. The install takes about a minute, once per per-commit image.
     */
    public static String ncurses5Snippet(String cubridHome, String indent) {
        String ldd = "LD_LIBRARY_PATH=" + cubridHome + "/lib:" + cubridHome + "/cci/lib:${LD_LIBRARY_PATH:-} ldd "
            + cubridHome + "/bin/cubrid_rel 2>/dev/null";
        return indent + "if " + ldd + " | grep -Eq 'lib(ncurses|tinfo|form)\\.so\\.5 => not found'; then\n"
            + indent + "  echo \"This build needs ncurses 5; installing ncurses-compat-libs\"\n"
            + indent + "  (dnf install -y -q ncurses-compat-libs || yum install -y -q ncurses-compat-libs) >/dev/null 2>&1 \\\n"
            + indent + "    || echo \"WARNING: ncurses-compat-libs could not be installed\"\n"
            + indent + "fi\n";
    }

    /**
     * Docker tag of a per-commit image: commit, variant, and the base image it was provisioned
     * from. docker_test_image is a moving tag - cubridci republishes it, and pointing the setting at
     * another image moves it altogether - so an image provisioned on one base must never be found
     * under a name that another base also produces.
     */
    public static String imageKey(String commitHash, String variant, String baseImageId) {
        String base = baseImageId == null ? "" : baseImageId.trim();
        int colon = base.indexOf(':');
        if (colon >= 0) {
            base = base.substring(colon + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9]", "");
        if (base.isEmpty()) {
            throw new IllegalArgumentException("base image id is required");
        }
        return commitHash + "_" + variant + "_" + base.substring(0, Math.min(12, base.length()));
    }

    /**
     * Id of docker_test_image as Docker resolves the tag now, or "" when it is not present
     * locally. Reused for {@link #BASE_IMAGE_ID_TTL_MS}.
     */
    private String resolveBaseImageId() {
        long now = System.currentTimeMillis();
        String cached = baseImageId;
        if (!cached.isEmpty() && now - baseImageIdResolvedAt < BASE_IMAGE_ID_TTL_MS) {
            return cached;
        }
        String id = "";
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}",
                                           config.getDockerTestImage()).redirectErrorStream(true).start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                line = r.readLine();
                while (r.readLine() != null) {
                    // drain
                }
            }
            if (p.waitFor() == 0 && line != null && line.trim().startsWith("sha256:")) {
                id = line.trim();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.warning("Could not resolve test image id: " + e.getMessage());
        }
        if (!id.isEmpty()) {
            if (!id.equals(cached)) {
                logger.info("Test image " + config.getDockerTestImage() + " is " + id);
            }
            baseImageId = id;
            baseImageIdResolvedAt = now;
        }
        return id;
    }

    /**
     * As {@link #resolveBaseImageId()}, pulling the test image first if it is not present. One
     * pull at a time: tests that all find it missing wait for the first one's pull.
     */
    private String requireBaseImageId() throws IOException {
        String id = resolveBaseImageId();
        if (!id.isEmpty()) {
            return id;
        }
        synchronized (pullLock) {
            id = resolveBaseImageId();
            if (!id.isEmpty()) {
                return id;
            }
            try {
                DockerUtils.pullImage(config.getDockerTestImage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted pulling test image " + config.getDockerTestImage(), e);
            }
            id = resolveBaseImageId();
        }
        if (id.isEmpty()) {
            throw new IOException("Test image " + config.getDockerTestImage() + " is not available");
        }
        return id;
    }

    /**
     * Get or build a Docker image for the given commit built on the given baseline.
     * This method first extracts the build package, then builds a Docker image.
     * The image name includes commit, baseline and base image to prevent incorrect reuse.
     */
    public String getOrBuildImage(String commitHash, String baselineHash, Path buildPackage) throws IOException {
        String found = lastFoundImage.get();
        lastFoundImage.remove();
        String baseId = requireBaseImageId();
        String imageKey = imageKey(commitHash, baselineHash, baseId);
        String imageName = "cubrid-test:" + imageKey;
        lastOperationCacheHit.set(Boolean.FALSE);

        // The caller skipped the download because hasImage found an image; if the base id moved
        // since, run on that image rather than fail the test (the next one rebuilds on the new base).
        if (buildPackage == null && found != null && !found.equals(imageName)
                && found.startsWith("cubrid-test:" + commitHash + "_" + baselineHash + "_")
                && !imageExists(imageName) && imageExists(found)) {
            logger.warning("Test image changed after " + found + " was chosen; using it for this test");
            lastOperationCacheHit.set(Boolean.TRUE);
            return found;
        }

        // Check if image already exists
        if (imageExists(imageName)) {
            logger.info("Using existing Docker image: " + imageName);
            updateCacheEntry(imageKey, imageName);
            lastOperationCacheHit.set(Boolean.TRUE);
            return imageName;
        }

        // Build new image under lock to prevent concurrent builds of same image
        synchronized (buildLock) {
            // Double-check after acquiring lock
            if (imageExists(imageName)) {
                updateCacheEntry(imageKey, imageName);
                lastOperationCacheHit.set(Boolean.TRUE);
                return imageName;
            }

            // Only when the image hasImage found is gone as well (evicted) is there nothing to build from
            if (buildPackage == null) {
                throw new IOException("No Docker image " + imageName + " and no build package to build it from");
            }

            logger.info("Building Docker image for commit " + commitHash + " on baseline " + baselineHash);
            buildImageFromPackage(imageKey, buildPackage, baseId);
            lastOperationCacheHit.set(Boolean.FALSE);
            
            // Clean old images if needed
            if (imageCache.size() > maxCachedImages) {
                evictOldestImage();
            }
        }
        
        return imageName;
    }
    
    /**
     * Backward compatibility method - use with caution as it may reuse images incorrectly
     * @deprecated Use getOrBuildImage(String, String, Path) instead to specify baseline
     */
    @Deprecated
    public String getOrBuildImage(String commitHash, Path buildPackage) throws IOException {
        logger.warning("Using deprecated getOrBuildImage without baseline - this may cause incorrect image reuse");
        return getOrBuildImage(commitHash, "unknown", buildPackage);
    }
    
    /**
     * Build Docker image directly from the tar.gz package
     */
    private void buildImageFromPackage(String imageKey, Path buildPackage, String baseId) throws IOException {
        String imageName = "cubrid-test:" + imageKey;
        String sanitizedKey = sanitizeName(imageKey);
        Path stagingDir = workDir.resolve(sanitizedKey + "_setup");
        Files.createDirectories(stagingDir);
        Path setupScript = stagingDir.resolve("setup.sh");
        Files.write(setupScript, createSetupScript().getBytes(StandardCharsets.UTF_8));
        setupScript.toFile().setExecutable(true);
        
        String containerName = "cubrid_img_" + sanitizedKey + "_" + System.currentTimeMillis();
        List<String> runCommand = new ArrayList<>();
        runCommand.add("docker");
        runCommand.add("run");
        runCommand.add("--name");
        runCommand.add(containerName);
        runCommand.add("-v");
        runCommand.add(buildPackage.toAbsolutePath().toString() + ":/mnt/build.tar.gz:ro");
        runCommand.add("-v");
        runCommand.add(setupScript.toAbsolutePath().toString() + ":/mnt/setup.sh:ro");
        runCommand.add("--entrypoint");
        runCommand.add("/bin/bash");
        // By id, not tag: the image name records this id, so the tag moving now must not matter
        runCommand.add(baseId);
        runCommand.add("/mnt/setup.sh");

        List<String> commitCommand = new ArrayList<>();
        commitCommand.add("docker");
        commitCommand.add("commit");
        commitCommand.add("--change");
        commitCommand.add("LABEL " + DockerLabels.BASE_IMAGE + "=" + config.getDockerTestImage()
            + " " + DockerLabels.BASE_IMAGE_ID + "=" + baseId);
        commitCommand.add("--change");
        commitCommand.add("ENV CUBRID=/opt/cubrid");
        commitCommand.add("--change");
        commitCommand.add("ENV CUBRID_DATABASES=/opt/cubrid/databases");
        commitCommand.add("--change");
        commitCommand.add("ENV PATH=/opt/cubrid/bin:/home/cubrid-testtools/CTP/shell/init_path:$PATH");
        commitCommand.add("--change");
        commitCommand.add("ENV LD_LIBRARY_PATH=/opt/cubrid/lib:/opt/cubrid/cci/lib:$LD_LIBRARY_PATH");
        commitCommand.add("--change");
        commitCommand.add("ENV CUBRID_LANG=en_US");
        commitCommand.add("--change");
        commitCommand.add("ENV CUBRID_CHARSET=en_US");
        commitCommand.add("--change");
        commitCommand.add("WORKDIR /workspace");
        commitCommand.add(containerName);
        commitCommand.add(imageName);
        
        try {
            long start = System.currentTimeMillis();
            logger.info("Building Docker image by provisioning container: " + imageName);
            runCommand(runCommand, "[docker-run]");
            runCommand(commitCommand, "[docker-commit]");
            updateCacheEntry(imageKey, imageName);
            long elapsed = System.currentTimeMillis() - start;
            logger.info("Successfully built Docker image: " + imageName + " (" + (elapsed / 1000) + "s)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker image build interrupted", e);
        } catch (IOException e) {
            throw e;
        } finally {
            try {
                runCommand(Arrays.asList("docker", "rm", "-f", containerName), null);
            } catch (Exception ignore) { }
            try { deleteDirectory(stagingDir); } catch (Exception ignore) {}
        }
    }
    
    private String createSetupScript() {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n");
        script.append("set -euo pipefail\n");
        script.append("log() { echo \"[setup] $*\"; }\n");
        script.append("copy_tree() {\n");
        script.append("  local src=\"$1\"\n");
        script.append("  local dest=\"$2\"\n");
        script.append("  if [[ ! -d \"$src\" ]]; then\n");
        script.append("    return\n");
        script.append("  fi\n");
        script.append("  mkdir -p \"$dest\"\n");
        script.append("  if command -v rsync >/dev/null 2>&1; then\n");
        script.append("    rsync -a \"$src\"/ \"$dest\"/\n");
        script.append("  else\n");
        script.append("    cp -a \"$src\"/. \"$dest\"/\n");
        script.append("  fi\n");
        script.append("}\n");
        script.append("snapshot_tree() {\n");
        script.append("  local src=\"$1\"\n");
        script.append("  if [[ ! -d \"$src\" ]]; then\n");
        script.append("    echo \"\"\n");
        script.append("    return\n");
        script.append("  fi\n");
        script.append("  local snap\n");
        script.append("  snap=$(mktemp -d /tmp/cubrid_snapshot.XXXX)\n");
        script.append("  copy_tree \"$src\" \"$snap\"\n");
        script.append("  echo \"$snap\"\n");
        script.append("}\n");
        script.append("restore_tree() {\n");
        script.append("  local snap=\"$1\"\n");
        script.append("  local dest=\"$2\"\n");
        script.append("  local label=\"$3\"\n");
        script.append("  if [[ -z \"$snap\" || ! -d \"$snap\" ]]; then\n");
        script.append("    return\n");
        script.append("  fi\n");
        script.append("  log \"Restoring pristine $label files\"\n");
        script.append("  mkdir -p \"$dest\"\n");
        script.append("  if command -v rsync >/dev/null 2>&1; then\n");
        script.append("    rsync -a --delete \"$snap\"/ \"$dest\"/\n");
        script.append("  else\n");
        script.append("    rm -rf \"$dest\"\n");
        script.append("    mkdir -p \"$dest\"\n");
        script.append("    cp -a \"$snap\"/. \"$dest\"/\n");
        script.append("  fi\n");
        script.append("}\n");
        script.append("cleanup_snapshots() {\n");
        script.append("  [[ -n \"${CONF_SNAPSHOT:-}\" && -d \"$CONF_SNAPSHOT\" ]] && rm -rf \"$CONF_SNAPSHOT\"\n");
        script.append("  [[ -n \"${DB_SNAPSHOT:-}\" && -d \"$DB_SNAPSHOT\" ]] && rm -rf \"$DB_SNAPSHOT\"\n");
        script.append("}\n");
        script.append("ensure_sample_databases() {\n");
        script.append("  mkdir -p /opt/cubrid/databases\n");
        script.append("  touch /opt/cubrid/databases/databases.txt\n");
        script.append("  if [[ ! -f /opt/cubrid/databases/databases.txt.sample ]]; then\n");
        script.append("    cat <<'SAMPLE' > /opt/cubrid/databases/databases.txt.sample\n");
        script.append("# sample databases.txt for demodb\n");
        script.append("#\n");
        script.append("# db-name vol-path db-host log-path lob-base-path\n");
        script.append("demodb ${CUBRID}/demo localhost ${CUBRID}/demo file:${CUBRID}/demo/lob\n");
        script.append("SAMPLE\n");
        script.append("  fi\n");
        script.append("}\n");
        script.append("trap cleanup_snapshots EXIT\n");
        script.append("log \"Preparing CUBRID installation inside container\"\n");
        script.append("rm -rf /opt/cubrid\n");
        script.append("mkdir -p /opt/cubrid\n");
        // The first entry tells the layout: _install/CUBRID/ (a Builder package) or CUBRID/ (an
        // official build, CUBRID-<version>-Linux.x86_64.tar.gz). tar's status is ignored: head stops
        // reading after one line, and under pipefail tar's SIGPIPE fails the pipeline. That is why
        // the former `tar -tf | grep -m1 -q` test was false even on a match, and every Builder
        // package took the full-extract fallback below.
        script.append("FIRST_ENTRY=$({ tar -tf /mnt/build.tar.gz 2>/dev/null || true; } | head -1)\n");
        script.append("if [[ \"$FIRST_ENTRY\" == _install/CUBRID* ]]; then\n");
        script.append("  log \"Detected packaged _install/CUBRID layout\"\n");
        script.append("  tar -xzf /mnt/build.tar.gz -C /opt/cubrid --strip-components=2\n");
        script.append("  CUBRID_DIR=/opt/cubrid\n");
        script.append("elif [[ \"$FIRST_ENTRY\" == CUBRID/* ]]; then\n");
        script.append("  log \"Detected official CUBRID/ layout\"\n");
        script.append("  tar -xzf /mnt/build.tar.gz -C /opt/cubrid --strip-components=1\n");
        script.append("  CUBRID_DIR=/opt/cubrid\n");
        script.append("else\n");
        script.append("  log \"Extracting full archive\"\n");
        script.append("  tar -xzf /mnt/build.tar.gz -C /opt/cubrid\n");
        script.append("  CUBRID_DIR=$(find /opt/cubrid -path '*/_install/CUBRID' -type d | head -1)\n");
        script.append("  if [[ -z \"$CUBRID_DIR\" ]]; then\n");
        script.append("    CUBRID_DIR=$(find /opt/cubrid -name 'cubrid_rel' -type f | head -1 | xargs dirname | xargs dirname)\n");
        script.append("  fi\n");
        script.append("  log \"Found CUBRID directory: $CUBRID_DIR\"\n");
        script.append("  if [[ -n \"$CUBRID_DIR\" && \"$CUBRID_DIR\" != \"/opt/cubrid\" && -d \"$CUBRID_DIR\" ]]; then\n");
        script.append("    log \"Syncing CUBRID contents into /opt/cubrid\"\n");
        script.append("    cp -rf \"$CUBRID_DIR\"/* /opt/cubrid/\n");
        script.append("    rm -rf /opt/cubrid/_install\n");
        script.append("    CUBRID_DIR=/opt/cubrid\n");
        script.append("  fi\n");
        script.append("fi\n");
        script.append("CONF_SNAPSHOT=$(snapshot_tree /opt/cubrid/conf)\n");
        script.append("DB_SNAPSHOT=$(snapshot_tree /opt/cubrid/databases)\n");
        script.append("log \"Final CUBRID_DIR=$CUBRID_DIR\"\n");
        // CUBRID's cubrid.sh links lib{ncurses,form,tinfo}.so.5 to what `ldconfig -p` lists; a base
        // image whose cache still names removed files (test_rl8.10: libform.so, libtinfo.so of an
        // uninstalled ncurses-devel) would get links to nothing and a cubrid_rel that cannot start.
        script.append("ldconfig 2>/dev/null || true\n");
        script.append("if [[ -f /opt/cubrid/share/scripts/setup.sh ]]; then\n");
        script.append("  log \"Running share/scripts/setup.sh\"\n");
        script.append("  (cd /opt/cubrid && printf 'y\\n' | sh share/scripts/setup.sh /opt/cubrid) || true\n");
        script.append("elif [[ -f /opt/cubrid/setup.sh ]]; then\n");
        script.append("  log \"Running top-level setup.sh\"\n");
        script.append("  (cd /opt/cubrid && printf 'y\\n' | sh setup.sh /opt/cubrid) || true\n");
        script.append("fi\n");
        script.append("restore_tree \"$CONF_SNAPSHOT\" /opt/cubrid/conf conf\n");
        script.append("restore_tree \"$DB_SNAPSHOT\" /opt/cubrid/databases databases\n");
        script.append("ensure_sample_databases\n");
        script.append("for legacy_home in \"$HOME/CUBRID\" \"/root/CUBRID\" \"/home/cubrid-testtools/CUBRID\"; do\n");
        script.append("  if [ -n \"$legacy_home\" ] && [ ! -e \"$legacy_home\" ]; then\n");
        script.append("    legacy_parent=$(dirname \"$legacy_home\")\n");
        script.append("    mkdir -p \"$legacy_parent\" 2>/dev/null || true\n");
        script.append("    ln -s /opt/cubrid \"$legacy_home\" 2>/dev/null || true\n");
        script.append("  fi\n");
        script.append("done\n");
        script.append("HOSTS_CONF=/opt/cubrid/conf/cubrid_hosts.conf\n");
        script.append("if [[ -f \"$HOSTS_CONF\" ]] && ! grep -q \"0.0.0.0\\s\\+your-hostname\" \"$HOSTS_CONF\"; then\n");
        script.append("  printf '0.0.0.0\\t\\tyour-hostname\\n' >> \"$HOSTS_CONF\"\n");
        script.append("fi\n");
        script.append("ls -la /opt/cubrid/bin/ || true\n");
        script.append(ncurses5Snippet("/opt/cubrid", ""));
        // The base image's LD_LIBRARY_PATH names $CUBRID=/home/CUBRID, not this install. Every test
        // script stops when cubrid_rel fails, so an image where it does is never committed (cached).
        script.append("if ! LD_LIBRARY_PATH=/opt/cubrid/lib:/opt/cubrid/cci/lib:${LD_LIBRARY_PATH:-} /opt/cubrid/bin/cubrid_rel; then\n");
        script.append("  log \"ERROR: cubrid_rel does not run in this image; not committing it\"\n");
        script.append("  exit 1\n");
        script.append("fi\n");
        return script.toString();
    }
    
    private String sanitizeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
    
    // Image provisioning (extract + install inside a container, then commit) normally takes minutes
    private static final long COMMAND_TIMEOUT_MINUTES = 30;

    private void runCommand(List<String> command, String logPrefix) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Every image build runs under one lock, so a hung provisioning container used to stall all
        // image builds (and every test waiting for one) indefinitely. A watchdog stops it; killing
        // the docker client alone would leave the named container running, so it is removed too.
        int nameIdx = command.indexOf("--name");
        final String containerName = nameIdx >= 0 && nameIdx + 1 < command.size() ? command.get(nameIdx + 1) : null;
        final java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                if (!process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    timedOut.set(true);
                    logger.severe("Command exceeded " + COMMAND_TIMEOUT_MINUTES + " minutes; stopping: " + String.join(" ", command));
                    if (containerName != null) {
                        try {
                            new ProcessBuilder("docker", "rm", "-f", containerName).redirectErrorStream(true).start()
                                .waitFor(60, TimeUnit.SECONDS);
                        } catch (IOException ignore) {
                        }
                    }
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }, "image-build-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                if (logPrefix != null && !logPrefix.isEmpty()) {
                    logger.info(logPrefix + " " + line);
                }
            }
        }
        int exitCode = process.waitFor();
        watchdog.interrupt();
        if (timedOut.get()) {
            throw new IOException("Command timed out after " + COMMAND_TIMEOUT_MINUTES + " minutes: " + String.join(" ", command));
        }
        if (exitCode != 0) {
            throw new IOException("Command failed with exit code " + exitCode + ": " +
                    String.join(" ", command) + "\n" + output);
        }
    }
    
    /**
     * Check if Docker image exists
     */
    private boolean imageExists(String imageName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", imageName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Consume output to prevent blocking
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Just consume
                }
            }
            
            int exitCode = process.waitFor();
            return exitCode == 0;
            
        } catch (Exception e) {
            logger.warning("Failed to check if image exists: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Update cache entry with current timestamp
     */
    private void updateCacheEntry(String imageKey, String imageName) {
        imageCache.put(imageKey, imageName);
        imageBuildTime.put(imageKey, System.currentTimeMillis());
    }
    
    /**
     * Delete directory recursively
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * Load existing images from Docker
     */
    private void loadExistingImages() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "images", 
                "--filter", "reference=cubrid-test:*", 
                "--format", "{{.Repository}}:{{.Tag}}");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("cubrid-test:")) {
                        String imageKey = line.substring("cubrid-test:".length());
                        imageCache.put(imageKey, line);
                        imageBuildTime.put(imageKey, System.currentTimeMillis());
                    }
                }
            }
            
            process.waitFor();
            logger.info("Found " + imageCache.size() + " existing CUBRID test images");
            
        } catch (Exception e) {
            logger.warning("Failed to load existing images: " + e.getMessage());
        }
    }
    
    /**
     * Evict oldest Docker image based on build time
     */
    private void evictOldestImage() {
        if (imageCache.size() <= maxCachedImages) {
            return;
        }
        
        // Find oldest image
        String oldestImageKey = null;
        long oldestTime = Long.MAX_VALUE;
        
        for (Map.Entry<String, Long> entry : imageBuildTime.entrySet()) {
            if (entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                oldestImageKey = entry.getKey();
            }
        }
        
        if (oldestImageKey != null) {
            String imageName = imageCache.remove(oldestImageKey);
            imageBuildTime.remove(oldestImageKey);
            
            // Remove Docker image
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "rmi", "-f", imageName);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                // Consume output
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    while (reader.readLine() != null) {
                        // Just consume
                    }
                }
                
                process.waitFor();
                logger.info("Evicted old Docker image: " + imageName);
                
            } catch (Exception e) {
                logger.warning("Failed to remove evicted image: " + e.getMessage());
            }
        }
    }
    
    /**
     * Clear all cached images
     */
    public void clearCache() {
        for (String imageName : imageCache.values()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "rmi", "-f", imageName);
                pb.start().waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warning("Failed to remove image " + imageName + ": " + e.getMessage());
            }
        }
        
        imageCache.clear();
        imageBuildTime.clear();
        logger.info("Cleared Docker image cache");
    }
    
    /**
     * Get cache statistics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cached_images", imageCache.size());
        stats.put("max_cached_images", maxCachedImages);
        
        // Get total image size
        long totalSize = 0;
        for (String imageName : imageCache.values()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", 
                    imageName, "--format", "{{.Size}}");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null) {
                        totalSize += Long.parseLong(line.trim());
                    }
                }
                
                process.waitFor();
            } catch (Exception e) {
                // Ignore
            }
        }
        
        stats.put("total_image_size_mb", totalSize / (1024 * 1024));
        
        return stats;
    }

    /**
     * Check if a Docker image exists for the given commit and baseline.
     * This allows callers to avoid downloading build packages when the image is already cached.
     *
     * @param commitHash The commit hash
     * @param baselineHash The baseline hash
     * @return true if the image exists in Docker, false otherwise
     */
    public boolean hasImage(String commitHash, String baselineHash) {
        lastFoundImage.remove();
        String baseId = resolveBaseImageId();
        if (baseId.isEmpty()) {
            return false;
        }
        String imageName = "cubrid-test:" + imageKey(commitHash, baselineHash, baseId);
        if (!imageExists(imageName)) {
            return false;
        }
        lastFoundImage.set(imageName);
        return true;
    }

    public boolean wasLastOperationCacheHit() {
        return Boolean.TRUE.equals(lastOperationCacheHit.get());
    }
}
