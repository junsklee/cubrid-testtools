package com.navercorp.cubridqa.builder.git;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.exec.ProcessIO;
import java.io.*;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ShellTcSync {
    private static final Object SHELL_TC_SYNC_LOCK = new Object();
    private static volatile long lastSuccessfulSyncMs = 0L;
    private static volatile String lastSyncedBranch = null;
    
    // Request-scoped sync tracking: maps requestId -> timestamp of successful sync
    private static final ConcurrentHashMap<String, Long> syncedRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TTL_MS = TimeUnit.HOURS.toMillis(1); // Clean up after 1 hour
    
    /**
     * Sync mode options for shell testcases repository.
     */
    public enum SyncMode {
        /** Sync once per unique request ID (default) */
        PER_REQUEST,
        /** Sync on every test execution, respecting interval */
        PER_TEST,
        /** Skip sync entirely */
        DISABLED
    }
    
    private final BuilderConfig config;
    
    public ShellTcSync(BuilderConfig config) {
        this.config = config;
    }
    
    /**
     * Sync shell testcases repository once per request ID.
     * If this request ID has already been synced, the sync is skipped.
     * 
     * @param log Logger for output
     * @param requestId The request ID to track (may be null for legacy calls)
     */
    public void syncOncePerRequest(Logger log, String requestId) throws IOException, InterruptedException {
        SyncMode mode = config.getShellTcSyncMode();
        
        // Handle disabled mode
        if (mode == SyncMode.DISABLED) {
            log.fine("Shell testcases sync is disabled (shell_tc_sync_mode=disabled)");
            return;
        }
        
        // Handle per_test mode - just delegate to the original sync method
        if (mode == SyncMode.PER_TEST) {
            sync(log);
            return;
        }
        
        // Handle per_request mode (default)
        // Clean up old entries to prevent memory leaks
        cleanupOldRequests();
        
        // If no request ID provided, fall back to interval-based sync
        if (requestId == null || requestId.isEmpty()) {
            log.fine("No request ID provided, falling back to interval-based sync");
            sync(log);
            return;
        }
        
        // Check if this request has already been synced
        if (syncedRequests.containsKey(requestId)) {
            log.fine("Shell testcases sync already done for request " + requestId + "; skipping");
            return;
        }
        
        // Perform sync and track that this request has been synced
        synchronized (SHELL_TC_SYNC_LOCK) {
            // Double-check after acquiring lock
            if (syncedRequests.containsKey(requestId)) {
                log.fine("Shell testcases sync already done for request " + requestId + " (after lock); skipping");
                return;
            }
            
            // Mark this request as synced BEFORE attempting sync
            // This ensures we only try once per request, even if sync fails
            // (the testcases are likely already in a usable state from a previous run)
            syncedRequests.put(requestId, System.currentTimeMillis());
            
            try {
                // Perform the actual sync
                doSync(log);
                log.info("Shell testcases sync completed for request " + requestId);
            } catch (Exception e) {
                // Log failure but don't rethrow - we've already marked this request as synced
                // to prevent repeated sync attempts. The testcases are likely usable.
                log.warning("Shell testcases sync failed for request " + requestId + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Clean up request entries older than TTL to prevent memory leaks.
     */
    private void cleanupOldRequests() {
        long cutoff = System.currentTimeMillis() - REQUEST_TTL_MS;
        Iterator<Map.Entry<String, Long>> it = syncedRequests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() < cutoff) {
                it.remove();
            }
        }
    }
    
    /**
     * Ensure the shell testcases repository at shell_tc_dir is checked out to the configured
     * branch using the preferred remote (upstream), falling back to origin when needed.
     * This only applies to the shell testcases repo and does not affect other repositories.
     * 
     * @deprecated Use {@link #syncOncePerRequest(Logger, String)} for request-scoped sync
     */
    public void sync(Logger log) throws IOException, InterruptedException {
        synchronized (SHELL_TC_SYNC_LOCK) {
            // Check sync mode - if disabled, skip entirely
            SyncMode mode = config.getShellTcSyncMode();
            if (mode == SyncMode.DISABLED) {
                log.fine("Shell testcases sync is disabled (shell_tc_sync_mode=disabled)");
                return;
            }
            
            // Apply interval-based skip for per_test mode
            String targetBranch = config.getShellTcBranch();
            long intervalSeconds = config.getShellTcSyncIntervalSeconds();
            if (intervalSeconds > 0) {
                long now = System.currentTimeMillis();
                long intervalMs = TimeUnit.SECONDS.toMillis(intervalSeconds);
                if (lastSyncedBranch != null
                    && lastSyncedBranch.equals(targetBranch)
                    && (now - lastSuccessfulSyncMs) < intervalMs) {
                    log.fine("Skipping shell testcases git sync (last sync " + (now - lastSuccessfulSyncMs) / 1000 + "s ago < " + intervalSeconds + "s interval)");
                    return;
                }
            }
            
            doSync(log);
        }
    }
    
    /**
     * Internal method that performs the actual git sync operations.
     * Must be called while holding SHELL_TC_SYNC_LOCK.
     */
    private void doSync(Logger log) throws IOException, InterruptedException {
        String repoPath = config.getShellTcDir();
        String targetBranch = config.getShellTcBranch();
        File repoDir = new File(repoPath);
        if (!repoDir.exists() || !repoDir.isDirectory()) {
            log.warning("shell_tc_dir does not exist: " + repoPath + "; skipping sync");
            return;
        }

        if (config.isShellTcOverlayActive()) {
            log.fine("Using shell testcases overlay workspace: " + repoPath +
                     " (source=" + config.getShellTcSourceDir() + ")");
        }

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(repoDir);

        // Verify git repo
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "rev-parse", "--is-inside-work-tree"}) != 0) {
            log.warning("shell_tc_dir is not a git repository: " + repoPath + "; skipping sync");
            return;
        }

        // Determine preferred remote from config (default: upstream), fallback to origin if missing
        String preferred = config.getShellTcPreferredRemote();
        if (preferred == null || preferred.trim().isEmpty()) {
            preferred = "upstream";
        }
        String chosenRemote = preferred;
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "remote", "get-url", preferred}) != 0) {
            chosenRemote = "origin";
            if (ProcessIO.runAndExitCode(pb, new String[]{"git", "remote", "get-url", chosenRemote}) != 0) {
                log.warning("Neither 'upstream' nor 'origin' remotes are configured in " + repoPath + "; skipping sync");
                return;
            }
        }

        // Prefer upstream if it has the branch; otherwise use origin if available
        if (!remoteBranchExists(pb, chosenRemote, targetBranch)) {
            if (!"origin".equals(chosenRemote)
                && ProcessIO.runAndExitCode(pb, new String[]{"git", "remote", "get-url", "origin"}) == 0
                && remoteBranchExists(pb, "origin", targetBranch)) {
                chosenRemote = "origin";
            } else {
                log.warning("Branch '" + targetBranch + "' not found on remote '" + chosenRemote + "'. Skipping sync.");
                return;
            }
        }

        log.info("Syncing shell testcases repo: branch='" + targetBranch + "' via remote='" + chosenRemote + "'");

        try {
            // Fetch just the target branch to reduce traffic
            ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", chosenRemote, targetBranch});
            // Create/reset local branch to remote branch
            ProcessIO.runOrThrow(pb, new String[]{"git", "checkout", "-B", targetBranch, chosenRemote + "/" + targetBranch});
            // Ensure clean state (avoid untracked noise)
            ProcessIO.runAndExitCode(pb, new String[]{"git", "clean", "-df"});
            // Hard reset to remote branch to avoid local drift
            ProcessIO.runOrThrow(pb, new String[]{"git", "reset", "--hard", chosenRemote + "/" + targetBranch});
            lastSuccessfulSyncMs = System.currentTimeMillis();
            lastSyncedBranch = targetBranch;
        } catch (IOException e) {
            // If a git index.lock is present, avoid interfering unless it appears stale
            if (isGitLockPresent(repoDir)) {
                long staleThresholdMs = 10L * 60L * 1000L; // 10 minutes
                if (isLikelyStaleGitLock(repoDir, staleThresholdMs)) {
                    log.warning("Detected stale git index lock; removing and retrying sync once...");
                    removeStaleGitIndexLock(repoDir, log);
                    // Retry once after cleanup
                    ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", chosenRemote, targetBranch});
                    ProcessIO.runOrThrow(pb, new String[]{"git", "checkout", "-B", targetBranch, chosenRemote + "/" + targetBranch});
                    ProcessIO.runAndExitCode(pb, new String[]{"git", "clean", "-df"});
                    ProcessIO.runOrThrow(pb, new String[]{"git", "reset", "--hard", chosenRemote + "/" + targetBranch});
                    lastSuccessfulSyncMs = System.currentTimeMillis();
                    lastSyncedBranch = targetBranch;
                } else {
                    log.warning("Git index.lock present; another git process may be running. Skipping repo sync this run to avoid interference.");
                }
            } else {
                throw e;
            }
        }
    }

    /**
     * Checks if a remote branch exists by using 'git ls-remote --heads <remote> <branch>'.
     */
    public boolean remoteBranchExists(ProcessBuilder pb, String remote, String branch) throws IOException, InterruptedException {
        // Use git ls-remote --heads <remote> <branch> and check for any output lines
        ProcessBuilder lp = new ProcessBuilder(
            "git", "ls-remote", "--heads", remote, branch
        );
        lp.directory(pb.directory());
        lp.redirectErrorStream(true);
        Process p = lp.start();
        boolean found = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    found = true;
                    break;
                }
            }
        }
        p.waitFor();
        return found;
    }

    public boolean isGitLockError(IOException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("index.lock") || msg.contains(".lock");
    }

    public boolean isGitLockPresent(File repoDir) {
        File gitDir = new File(repoDir, ".git");
        File indexLock = new File(gitDir, "index.lock");
        return indexLock.exists();
    }

    public boolean isLikelyStaleGitLock(File repoDir, long staleAgeMillis) {
        File gitDir = new File(repoDir, ".git");
        File indexLock = new File(gitDir, "index.lock");
        if (!indexLock.exists()) return false;
        long age = System.currentTimeMillis() - indexLock.lastModified();
        return age >= staleAgeMillis;
    }

    public void removeStaleGitIndexLock(File repoDir, Logger log) {
        File gitDir = new File(repoDir, ".git");
        File indexLock = new File(gitDir, "index.lock");
        if (indexLock.exists()) {
            if (indexLock.delete()) {
                log.warning("Removed stale git index lock: " + indexLock.getAbsolutePath());
            } else {
                log.warning("Failed to remove stale git index lock: " + indexLock.getAbsolutePath());
            }
        }
    }
}
