package com.navercorp.cubridqa.builder.ramdisk;

import com.navercorp.cubridqa.builder.BuilderConfig;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Resolves and monitors ramdisk-backed paths for the tester process.
 */
public class RamdiskManager {

    public static class RamdiskStatus {
        private final boolean enabled;
        private final boolean active;
        private final String root;
        private final long freeBytes;
        private final long totalBytes;
        private final String reason;

        public RamdiskStatus(boolean enabled, boolean active, String root, long freeBytes, long totalBytes, String reason) {
            this.enabled = enabled;
            this.active = active;
            this.root = root;
            this.freeBytes = freeBytes;
            this.totalBytes = totalBytes;
            this.reason = reason;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isActive() {
            return active;
        }

        public String getRoot() {
            return root;
        }

        public long getFreeBytes() {
            return freeBytes;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public String getReason() {
            return reason;
        }
    }

    public static class Resolution {
        private final Path workDir;
        private final Path logRoot;
        private final Path profilesDir;
        private final RamdiskStatus status;
        private final boolean workOnRamdisk;
        private final boolean logsOnRamdisk;
        private final boolean profilesOnRamdisk;

        public Resolution(Path workDir, Path logRoot, Path profilesDir, RamdiskStatus status,
                          boolean workOnRamdisk, boolean logsOnRamdisk, boolean profilesOnRamdisk) {
            this.workDir = workDir;
            this.logRoot = logRoot;
            this.profilesDir = profilesDir;
            this.status = status;
            this.workOnRamdisk = workOnRamdisk;
            this.logsOnRamdisk = logsOnRamdisk;
            this.profilesOnRamdisk = profilesOnRamdisk;
        }

        public Path getWorkDir() {
            return workDir;
        }

        public Path getLogRoot() {
            return logRoot;
        }

        public Path getProfilesDir() {
            return profilesDir;
        }

        public RamdiskStatus getStatus() {
            return status;
        }

        public boolean isWorkOnRamdisk() {
            return workOnRamdisk;
        }

        public boolean isLogsOnRamdisk() {
            return logsOnRamdisk;
        }

        public boolean isProfilesOnRamdisk() {
            return profilesOnRamdisk;
        }
    }

    private final BuilderConfig config;
    private final Logger logger;
    private Path activeRootPath;
    private RamdiskStatus cachedStatus;

    public RamdiskManager(BuilderConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public Resolution resolveForTester(String defaultWorkDir, String defaultLogRoot, Path defaultProfilesDir) {
        Path defaultWorkPath = Paths.get(defaultWorkDir);
        Path defaultLogPath = Paths.get(defaultLogRoot);
        Path defaultProfilesPath = defaultProfilesDir;

        if (!config.isRamdiskEnabled()) {
            RamdiskStatus status = new RamdiskStatus(false, false, config.getRamdiskRoot(), 0, 0, "disabled");
            this.cachedStatus = status;
            this.activeRootPath = null;
            return new Resolution(defaultWorkPath, defaultLogPath, defaultProfilesPath, status, false, false, false);
        }

        Path ramdiskRoot = Paths.get(config.getRamdiskRoot());
        RamdiskStatus status = evaluateRoot(ramdiskRoot);
        this.cachedStatus = status;
        this.activeRootPath = status.isActive() ? ramdiskRoot : null;

        Path fallbackRoot = null;
        String fallbackRaw = config.getRamdiskFallbackRoot();
        if (fallbackRaw != null && !fallbackRaw.isEmpty()) {
            fallbackRoot = Paths.get(fallbackRaw);
        }

        Path resolvedWork = defaultWorkPath;
        boolean workOnRamdisk = false;
        if (status.isActive() && config.isRamdiskTesterWorkEnabled()) {
            resolvedWork = ramdiskRoot.resolve("tester_work");
            workOnRamdisk = true;
        }
        resolvedWork = ensureDir(resolvedWork, defaultWorkPath, "tester work dir");

        Path resolvedLogRoot = defaultLogPath;
        boolean logsOnRamdisk = false;
        if (config.isRamdiskLogsEnabled()) {
            if (status.isActive()) {
                resolvedLogRoot = ramdiskRoot.resolve("tester_logs");
                logsOnRamdisk = true;
            } else if (fallbackRoot != null) {
                resolvedLogRoot = fallbackRoot.resolve("tester_logs");
            }
        }
        resolvedLogRoot = ensureDir(resolvedLogRoot, defaultLogPath, "tester log dir");

        Path resolvedProfiles = defaultProfilesPath;
        boolean profilesOnRamdisk = false;
        if (config.isRamdiskProfilesEnabled()) {
            if (status.isActive()) {
                resolvedProfiles = ramdiskRoot.resolve("tester_profiles");
                profilesOnRamdisk = true;
            } else if (fallbackRoot != null) {
                resolvedProfiles = fallbackRoot.resolve("tester_profiles");
            }
        }
        resolvedProfiles = ensureDir(resolvedProfiles, defaultProfilesPath, "tester profiles dir");

        return new Resolution(resolvedWork, resolvedLogRoot, resolvedProfiles, status,
                workOnRamdisk, logsOnRamdisk, profilesOnRamdisk);
    }

    /**
     * Refresh free/total space for the active ramdisk root.
     */
    public synchronized RamdiskStatus sampleStatus() {
        if (cachedStatus == null) {
            return new RamdiskStatus(false, false, config.getRamdiskRoot(), 0, 0, "uninitialized");
        }
        if (!cachedStatus.isActive() || activeRootPath == null) {
            return cachedStatus;
        }
        RamdiskStatus updated = evaluateRoot(activeRootPath);
        this.cachedStatus = new RamdiskStatus(
                cachedStatus.isEnabled(),
                updated.isActive(),
                cachedStatus.getRoot(),
                updated.getFreeBytes(),
                updated.getTotalBytes(),
                updated.getReason()
        );
        return cachedStatus;
    }

    private RamdiskStatus evaluateRoot(Path rootPath) {
        try {
            Files.createDirectories(rootPath);
        } catch (IOException e) {
            return new RamdiskStatus(true, false, rootPath.toString(), 0, 0, "mkdir_failed:" + e.getMessage());
        }

        try {
            FileStore store = Files.getFileStore(rootPath);
            String type = store.type();
            boolean isTmpfs = type != null && type.toLowerCase(Locale.ROOT).contains("tmpfs");
            if (!isTmpfs) {
                return new RamdiskStatus(true, false, rootPath.toString(), 0, 0, "not_tmpfs:" + type);
            }
            long free = store.getUsableSpace();
            long total = store.getTotalSpace();
            if (free < config.getRamdiskMinFreeBytes()) {
                return new RamdiskStatus(true, false, rootPath.toString(), free, total, "low_space");
            }
            return new RamdiskStatus(true, true, rootPath.toString(), free, total, "ok");
        } catch (IOException e) {
            return new RamdiskStatus(true, false, rootPath.toString(), 0, 0, "stat_failed:" + e.getMessage());
        }
    }

    private Path ensureDir(Path candidate, Path fallback, String label) {
        try {
            Files.createDirectories(candidate);
            return candidate;
        } catch (Exception e) {
            logger.warning("Failed to prepare " + label + " at " + candidate + ": " + e.getMessage());
            if (fallback != null) {
                try {
                    Files.createDirectories(fallback);
                    return fallback;
                } catch (Exception inner) {
                    logger.warning("Fallback for " + label + " failed at " + fallback + ": " + inner.getMessage());
                }
            }
            return candidate;
        }
    }
}
