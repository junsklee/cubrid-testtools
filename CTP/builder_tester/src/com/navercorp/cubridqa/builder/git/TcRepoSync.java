package com.navercorp.cubridqa.builder.git;

import com.navercorp.cubridqa.builder.exec.ProcessIO;
import com.navercorp.cubridqa.builder.tester.SafeIo;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Generic git-based testcase repository synchronizer.
 *
 * Keeps a base clone fast-forwarded to a configured branch and materializes
 * per-request pinned checkouts (clones on a named branch) so every test in a
 * request sees the exact same testcase tree. Repository location/branch/remote/sync-mode are supplied via
 * {@link RepoSpec}, so the same machinery serves both the shell testcases repo
 * ({@link ShellTcSync}) and the SQL testcases repo ({@link SqlTcSync}).
 *
 * Sync bookkeeping (per-request markers, workspace locks) is instance state:
 * each repository gets its own {@code TcRepoSync} instance and their request
 * tracking must not interfere.
 */
public class TcRepoSync {

    /**
     * Sync mode options for a testcases repository.
     */
    public enum SyncMode {
        /** Sync once per unique request ID (default) */
        PER_REQUEST,
        /** Sync on every test execution, respecting interval */
        PER_TEST,
        /** Skip sync entirely */
        DISABLED
    }

    /** Static description of the repository this instance manages. */
    public static class RepoSpec {
        private final String label;
        private final String repoDir;
        private final String branch;
        private final String preferredRemote;
        private final SyncMode syncMode;
        private final long syncIntervalSeconds;
        private final String requestsRootDir;
        private final boolean overlayActive;
        private final String sourceDir;

        public RepoSpec(String label, String repoDir, String branch, String preferredRemote,
                        SyncMode syncMode, long syncIntervalSeconds, String requestsRootDir,
                        boolean overlayActive, String sourceDir) {
            this.label = label;
            this.repoDir = repoDir;
            this.branch = branch;
            this.preferredRemote = preferredRemote;
            this.syncMode = syncMode;
            this.syncIntervalSeconds = syncIntervalSeconds;
            this.requestsRootDir = requestsRootDir;
            this.overlayActive = overlayActive;
            this.sourceDir = sourceDir;
        }
    }

    private final Object syncLock = new Object();
    private volatile long lastSuccessfulSyncMs = 0L;
    private volatile String lastSyncedBranch = null;

    // Request-scoped sync tracking: maps requestId -> timestamp of successful sync
    private final ConcurrentHashMap<String, Long> syncedRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TTL_MS = TimeUnit.HOURS.toMillis(1); // Clean up after 1 hour
    private final ConcurrentHashMap<String, Object> requestWorkspaceLocks = new ConcurrentHashMap<>();
    private static final long REQUEST_WORKSPACE_TTL_MS = TimeUnit.HOURS.toMillis(24);

    private final RepoSpec spec;

    public TcRepoSync(RepoSpec spec) {
        this.spec = spec;
    }

    protected String label() {
        return spec.label;
    }

    public String getRepoDir() {
        return spec.repoDir;
    }

    public String getConfiguredBranch() {
        return spec.branch;
    }

    /**
     * Sync the testcases repository once per request ID.
     * If this request ID has already been synced, the sync is skipped.
     *
     * @param log Logger for output
     * @param requestId The request ID to track (may be null for legacy calls)
     */
    public void syncOncePerRequest(Logger log, String requestId) throws IOException, InterruptedException {
        SyncMode mode = spec.syncMode;

        // Handle disabled mode
        if (mode == SyncMode.DISABLED) {
            log.fine(label() + " sync is disabled");
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
            log.fine(label() + " sync already done for request " + requestId + "; skipping");
            return;
        }

        // Perform sync and track that this request has been synced
        synchronized (syncLock) {
            // Double-check after acquiring lock
            if (syncedRequests.containsKey(requestId)) {
                log.fine(label() + " sync already done for request " + requestId + " (after lock); skipping");
                return;
            }

            // Mark this request as synced BEFORE attempting sync
            // This ensures we only try once per request, even if sync fails
            // (the testcases are likely already in a usable state from a previous run)
            syncedRequests.put(requestId, System.currentTimeMillis());

            try {
                // Perform the actual sync
                doSync(log);
                log.info(label() + " sync completed for request " + requestId);
            } catch (Exception e) {
                // Log failure but don't rethrow - we've already marked this request as synced
                // to prevent repeated sync attempts. The testcases are likely usable.
                log.warning(label() + " sync failed for request " + requestId + ": " + e.getMessage());
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

    public Path prepareRequestWorkspace(Logger log, String requestId, String requestedBranch, String requestedCommit)
            throws IOException, InterruptedException {
        String safeRequestId = sanitizeRequestId(requestId);
        String branch = normalizeRequestedBranch(requestedBranch);
        String commit = requestedCommit == null ? "" : requestedCommit.trim();

        Object lock = requestWorkspaceLocks.computeIfAbsent(safeRequestId, k -> new Object());
        synchronized (lock) {
            // A test that was already in flight when its request was cancelled must not recreate the
            // workspace the cancel just removed (it would only be cleaned at the next tester start).
            if (com.navercorp.cubridqa.builder.tester.CancelledRequests.isCancelled(requestId)) {
                throw new IOException("Request " + requestId + " was cancelled");
            }
            synchronized (baseRepoLock) {
                return prepareRequestWorkspaceLocked(log, safeRequestId, branch, commit);
            }
        }
    }

    // The base repo, its FETCH_HEAD and checked-out branch are shared by every request: two requests
    // fetching different branches could each read the other's FETCH_HEAD and pin the wrong commit.
    private final Object baseRepoLock = new Object();

    // requestId -> {local branch, commit} the base checkout is held at for that request
    private final ConcurrentHashMap<String, String[]> baseCheckouts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> baseCheckoutTimes = new ConcurrentHashMap<>();

    /**
     * Puts the base checkout itself on the head of branch, as CTP's run_git_update leaves a
     * checkout: a local branch named CTP style tracking the remote branch, the working tree forced
     * to it and cleaned (-df, as run_git_update). Shell cases use sibling checkouts in place
     * (cbrd_23613_6 copies and moves ~/cubrid-testcases/sql), so they must follow the request's
     * branch instead of staying wherever they were cloned.
     *
     * <p>The head is resolved once per request, so every case of a request (all of its commits)
     * sees the same tree; each call restores the tree to it, undoing what a killed case left.
     * Nothing happens when sync is disabled (the checkout belongs to someone else) or the branch
     * is on no remote.
     *
     * @return the commit the checkout is on, or null when it was left as it was
     */
    public String checkoutBaseForRequest(Logger log, String requestId, String requestedBranch)
            throws InterruptedException {
        if (spec.syncMode == SyncMode.DISABLED) {
            return null;
        }
        Path base = getBaseRepoRoot();
        if (!Files.isDirectory(base.resolve(".git"))) {
            log.fine(label() + " checkout " + base + " does not exist; nothing to update");
            return null;
        }
        String branch = normalizeRequestedBranch(requestedBranch);
        String key = sanitizeRequestId(requestId) + "\n" + branch;
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(base.toFile());
        synchronized (baseRepoLock) {
            long cutoff = System.currentTimeMillis() - REQUEST_TTL_MS;
            baseCheckoutTimes.entrySet().removeIf(e -> {
                if (e.getValue() < cutoff) {
                    baseCheckouts.remove(e.getKey());
                    return true;
                }
                return false;
            });
            String[] target = baseCheckouts.get(key);
            if (target == null) {
                // Resolved once per request, whether or not it works: a branch this repository does
                // not have is not looked up again at every case
                target = new String[0];
                try {
                    String remote = selectRemoteForBranch(pb, branch);
                    ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", remote, branch});
                    String head = runAndGetOutput(pb, "git", "rev-parse", "FETCH_HEAD").trim();
                    target = new String[]{localBranchName(branch), head, remote + "/" + branch};
                    log.info(label() + " checkout " + base + " follows " + branch + " at " + abbreviateCommit(head)
                        + " for request " + requestId);
                } catch (IOException e) {
                    log.warning(label() + " checkout " + base + " left as it was: branch '" + branch
                        + "' could not be fetched: " + e.getMessage());
                }
                baseCheckouts.put(key, target);
                baseCheckoutTimes.put(key, System.currentTimeMillis());
            }
            if (target.length == 0) {
                return null;
            }
            try {
                ProcessIO.runOrThrow(pb, new String[]{"git", "checkout", "--quiet", "-f", "-B", target[0], target[1]});
                ProcessIO.runOrThrow(pb, new String[]{"git", "reset", "--quiet", "--hard", target[1]});
                ProcessIO.runAndExitCode(pb, new String[]{"git", "clean", "-dfq"});
                if (ProcessIO.runAndExitCode(pb, new String[]{"git", "rev-parse", "--verify", "--quiet", target[2]}) == 0) {
                    ProcessIO.runAndExitCode(pb, new String[]{"git", "branch", "--quiet", "--set-upstream-to=" + target[2], target[0]});
                }
                return target[1];
            } catch (IOException e) {
                log.warning(label() + " checkout " + base + " left as it was for branch '" + branch + "': " + e.getMessage());
                return null;
            }
        }
    }

    private Path prepareRequestWorkspaceLocked(Logger log, String safeRequestId, String branch, String commit)
            throws IOException, InterruptedException {
        {
            cleanupOldRequests();
            Files.createDirectories(getRequestsRoot());
            boolean shouldRefreshBaseRepo = !syncedRequests.containsKey(safeRequestId);

            if (commit.isEmpty()) {
                commit = resolveBranchHead(log, branch);
            } else {
                ensureBaseRepoHasCommit(log, branch, commit);
                if (shouldRefreshBaseRepo) {
                    maybeFastForwardBaseRepoBranch(log, branch, commit);
                }
            }

            Path requestRoot = getRequestWorkspaceRoot(safeRequestId);
            Path repoRoot = requestRoot.resolve("repo");
            if (isReusableRequestWorkspace(repoRoot, commit, localBranchName(branch))) {
                syncedRequests.put(safeRequestId, System.currentTimeMillis());
                log.fine("Reusing " + label() + " workspace for request " + safeRequestId + ": " + repoRoot);
                return repoRoot;
            }

            cleanupRequestWorkspaceInternal(log, safeRequestId);
            createRequestWorkspace(log, safeRequestId, branch, commit);
            syncedRequests.put(safeRequestId, System.currentTimeMillis());
            return getRequestWorkspaceRoot(safeRequestId).resolve("repo");
        }
    }

    public void cleanupRequestWorkspace(Logger log, String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return;
        }
        String safeRequestId = sanitizeRequestId(requestId);
        Object lock = requestWorkspaceLocks.computeIfAbsent(safeRequestId, k -> new Object());
        synchronized (lock) {
            synchronized (baseRepoLock) {
                cleanupRequestWorkspaceInternal(log, safeRequestId);
            }
        }
        // The lock object stays mapped: removing it let a test still waiting on it and a new caller
        // (with a fresh lock) prepare the same workspace at the same time.
    }

    public void cleanupStaleRequestWorkspaces(Logger log) {
        cleanupStaleRequestWorkspaces(log, REQUEST_WORKSPACE_TTL_MS);
    }

    public void cleanupStaleRequestWorkspaces(Logger log, long staleAgeMillis) {
        Path root = getRequestsRoot();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Math.max(0L, staleAgeMillis);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path requestRoot : stream) {
                if (!Files.isDirectory(requestRoot)) {
                    continue;
                }
                long lastModified = Files.getLastModifiedTime(requestRoot).toMillis();
                if (lastModified < cutoff) {
                    String requestId = requestRoot.getFileName().toString();
                    log.info("Cleaning stale " + label() + " workspace for request " + requestId);
                    cleanupRequestWorkspace(log, requestId);
                }
            }
        } catch (Exception e) {
            log.warning("Failed to cleanup stale " + label() + " workspaces: " + e.getMessage());
        }
    }

    /**
     * Ensure the testcases repository is checked out to the configured branch
     * using the preferred remote, falling back to origin when needed.
     *
     * @deprecated Use {@link #syncOncePerRequest(Logger, String)} for request-scoped sync
     */
    @Deprecated
    public void sync(Logger log) throws IOException, InterruptedException {
        synchronized (syncLock) {
            // Check sync mode - if disabled, skip entirely
            SyncMode mode = spec.syncMode;
            if (mode == SyncMode.DISABLED) {
                log.fine(label() + " sync is disabled");
                return;
            }

            // Apply interval-based skip for per_test mode
            String targetBranch = spec.branch;
            long intervalSeconds = spec.syncIntervalSeconds;
            if (intervalSeconds > 0) {
                long now = System.currentTimeMillis();
                long intervalMs = TimeUnit.SECONDS.toMillis(intervalSeconds);
                if (lastSyncedBranch != null
                    && lastSyncedBranch.equals(targetBranch)
                    && (now - lastSuccessfulSyncMs) < intervalMs) {
                    log.fine("Skipping " + label() + " git sync (last sync " + (now - lastSuccessfulSyncMs) / 1000 + "s ago < " + intervalSeconds + "s interval)");
                    return;
                }
            }

            doSync(log);
        }
    }

    private Path getRequestsRoot() {
        return Paths.get(spec.requestsRootDir).toAbsolutePath().normalize();
    }

    private Path getRequestWorkspaceRoot(String safeRequestId) {
        return getRequestsRoot().resolve(safeRequestId);
    }

    private String sanitizeRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.trim();
        if (value.isEmpty()) {
            value = "adhoc_" + System.currentTimeMillis();
        }
        value = value.replaceAll("[^a-zA-Z0-9_.-]", "_").replace("..", "__");
        // "." or a leading dot/dash would resolve to the requests root or a hidden/option-like name.
        if (value.startsWith(".") || value.startsWith("-")) {
            value = "_" + value;
        }
        return value;
    }

    private String normalizeRequestedBranch(String requestedBranch) {
        String branch = requestedBranch == null ? "" : requestedBranch.trim();
        if (!branch.isEmpty()) {
            return branch;
        }
        String configured = spec.branch;
        if (configured == null || configured.trim().isEmpty()) {
            return "develop";
        }
        return configured.trim();
    }

    private Path getBaseRepoRoot() {
        return Paths.get(spec.repoDir).toAbsolutePath().normalize();
    }

    private boolean isReusableRequestWorkspace(Path repoRoot, String commit, String localBranch) {
        if (repoRoot == null || commit == null || commit.trim().isEmpty()) {
            return false;
        }
        if (!Files.exists(repoRoot) || !Files.isDirectory(repoRoot.resolve(".git"))) {
            // A .git file is a worktree of the base repo (detached HEAD) from before named branches
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(repoRoot.toFile());
            if (ProcessIO.runAndExitCode(pb, new String[]{"git", "rev-parse", "--is-inside-work-tree"}) != 0) {
                return false;
            }
            String head = runAndGetOutput(pb, "git", "rev-parse", "HEAD").trim();
            String headBranch = runAndGetOutput(pb, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
            return commit.equals(head) && localBranch.equals(headBranch);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The local branch a checkout of branch is named in regression, as CTP's run_git_update names
     * it: '/' becomes '_' (release/11.4 -> release_11.4). Cases turn it back with _ -> /.
     */
    public static String localBranchName(String branch) {
        return branch.trim().replace('/', '_');
    }

    /**
     * Object directories a request workspace borrows from the base repo (its objects/info/alternates).
     * A container that runs git in the workspace needs them mounted at the same paths.
     */
    public static List<Path> borrowedObjectDirs(Path repoRoot) {
        List<Path> dirs = new ArrayList<>();
        Path objects = repoRoot.resolve(".git").resolve("objects");
        Path alternates = objects.resolve("info").resolve("alternates");
        if (!Files.isRegularFile(alternates)) {
            return dirs;
        }
        try {
            for (String line : Files.readAllLines(alternates)) {
                String entry = line.trim();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                Path dir = objects.resolve(entry).toAbsolutePath().normalize();
                if (Files.isDirectory(dir)) {
                    dirs.add(dir);
                }
            }
        } catch (IOException ignore) {
        }
        return dirs;
    }

    private void createRequestWorkspace(Logger log, String safeRequestId, String branch, String commit)
            throws IOException, InterruptedException {
        Path requestRoot = getRequestWorkspaceRoot(safeRequestId);
        Path repoRoot = requestRoot.resolve("repo");
        Path baseRepoRoot = getBaseRepoRoot();
        Files.createDirectories(requestRoot);

        // A clone rather than a worktree: worktrees share the base repo's branches, and a branch can
        // be checked out in only one of them, so two requests could not both be on "develop".
        // --shared borrows the base repo's objects instead of copying them; base repo gc keeps
        // unreachable objects for two weeks, and a workspace lives only as long as its request.
        ProcessBuilder rootPb = new ProcessBuilder();
        rootPb.directory(requestRoot.toFile());
        ProcessIO.runOrThrow(rootPb, new String[]{"git", "clone", "--quiet", "--shared", "--no-checkout",
            baseRepoRoot.toString(), repoRoot.toString()});

        ProcessBuilder wtPb = new ProcessBuilder();
        wtPb.directory(repoRoot.toFile());
        // Cases name a sibling repository's branch after their own checkout's
        // (git rev-parse --abbrev-ref HEAD, then _ -> /), so HEAD is a named branch, as in regression.
        ProcessIO.runOrThrow(wtPb, new String[]{"git", "checkout", "--quiet", "-f", "-B", localBranchName(branch), commit});
        ProcessIO.runOrThrow(wtPb, new String[]{"git", "reset", "--hard", commit});
        ProcessIO.runOrThrow(wtPb, new String[]{"git", "clean", "-fdx"});
        // The remotes a worktree showed (it shared the base repo's config): origin is what CTP's
        // convert_to_git_url.sh and run_git_update read
        ProcessBuilder basePb = new ProcessBuilder();
        basePb.directory(baseRepoRoot.toFile());
        try {
            String originUrl = runAndGetOutput(basePb, "git", "remote", "get-url", "origin").trim();
            if (!originUrl.isEmpty()) {
                ProcessIO.runAndExitCode(wtPb, new String[]{"git", "remote", "set-url", "origin", originUrl});
            }
        } catch (IOException noOrigin) {
            log.fine(label() + " base repo has no origin remote: " + noOrigin.getMessage());
        }
        try {
            ProcessIO.runOrThrow(wtPb, new String[]{"git", "submodule", "sync", "--recursive"});
            ProcessIO.runOrThrow(wtPb, new String[]{"git", "submodule", "update", "--init", "--recursive", "--checkout", "--force"});
        } catch (Exception e) {
            log.fine("No testcase submodules to update for request " + safeRequestId + ": " + e.getMessage());
        }

        writeWorkspaceMetadata(requestRoot, branch, commit);
        log.info("Prepared " + label() + " workspace for request " + safeRequestId + " at " + repoRoot);
    }

    private void cleanupRequestWorkspaceInternal(Logger log, String safeRequestId) {
        Path requestRoot = getRequestWorkspaceRoot(safeRequestId);
        Path repoRoot = requestRoot.resolve("repo");
        Path baseRepoRoot = getBaseRepoRoot();

        try {
            if (Files.exists(repoRoot)) {
                ProcessBuilder basePb = new ProcessBuilder();
                basePb.directory(baseRepoRoot.toFile());
                ProcessIO.runAndExitCode(basePb, new String[]{"git", "worktree", "remove", "--force", repoRoot.toString()});
                ProcessIO.runAndExitCode(basePb, new String[]{"git", "worktree", "prune"});
            }
        } catch (Exception e) {
            log.warning("Failed to unregister " + label() + " worktree for request " + safeRequestId + ": " + e.getMessage());
        }

        try {
            if (Files.exists(requestRoot)) {
                SafeIo.deleteDirectory(requestRoot.toFile());
            }
            if (Files.exists(requestRoot)) {
                SafeIo.deleteDirectoryWithPrivileges(requestRoot.toFile(), log);
            }
        } catch (Exception e) {
            log.warning("Failed to delete " + label() + " workspace for request " + safeRequestId + ": " + e.getMessage());
        }

        syncedRequests.remove(safeRequestId);
    }

    private void writeWorkspaceMetadata(Path requestRoot, String branch, String commit) throws IOException {
        JSONObject metadata = new JSONObject()
            .put("branch", branch)
            .put("commit", commit)
            .put("createdAtMs", System.currentTimeMillis());
        Files.write(requestRoot.resolve("metadata.json"), metadata.toString(2).getBytes("UTF-8"));
    }

    private String resolveBranchHead(Logger log, String branch) throws IOException, InterruptedException {
        Path baseRepoRoot = getBaseRepoRoot();
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(baseRepoRoot.toFile());

        String remote = selectRemoteForBranch(pb, branch);
        log.info("Resolving " + label() + " branch '" + branch + "' via remote '" + remote + "'");
        ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", remote, branch});
        String commit = runAndGetOutput(pb, "git", "rev-parse", "FETCH_HEAD").trim();
        if (commit.isEmpty()) {
            throw new IOException("Failed to resolve " + label() + " branch head for " + branch);
        }
        maybeFastForwardBaseRepoBranch(log, pb, branch, remote, commit);
        return commit;
    }

    private void maybeFastForwardBaseRepoBranch(Logger log, String branch, String requestedCommit)
            throws IOException, InterruptedException {
        if (spec.syncMode == SyncMode.DISABLED) {
            return;
        }

        Path baseRepoRoot = getBaseRepoRoot();
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(baseRepoRoot.toFile());

        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "rev-parse", "--is-inside-work-tree"}) != 0) {
            return;
        }

        String remote = selectRemoteForBranch(pb, branch);
        ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", remote, branch});
        String remoteHead = runAndGetOutput(pb, "git", "rev-parse", "FETCH_HEAD").trim();
        if (!requestedCommit.equals(remoteHead)) {
            log.fine("Skipping " + label() + " base repo fast-forward for branch '" + branch
                + "' because requested commit " + abbreviateCommit(requestedCommit)
                + " is not the current remote head " + abbreviateCommit(remoteHead));
            return;
        }
        maybeFastForwardBaseRepoBranch(log, pb, branch, remote, remoteHead);
    }

    private void maybeFastForwardBaseRepoBranch(Logger log, ProcessBuilder pb, String branch, String remote, String expectedCommit)
            throws IOException, InterruptedException {
        if (spec.syncMode == SyncMode.DISABLED) {
            return;
        }

        String remoteRef = remote + "/" + branch;
        String checkoutTarget = remoteRef;
        try {
            runAndGetOutput(pb, "git", "rev-parse", "--verify", remoteRef);
        } catch (Exception e) {
            checkoutTarget = "FETCH_HEAD";
        }

        try {
            ProcessIO.runOrThrow(pb, new String[]{"git", "checkout", "-B", branch, checkoutTarget});
            ProcessIO.runAndExitCode(pb, new String[]{"git", "clean", "-df"});
            ProcessIO.runOrThrow(pb, new String[]{"git", "reset", "--hard", expectedCommit});
            if (!"FETCH_HEAD".equals(checkoutTarget)) {
                ProcessIO.runAndExitCode(pb, new String[]{"git", "branch", "--set-upstream-to=" + remoteRef, branch});
            }
            lastSuccessfulSyncMs = System.currentTimeMillis();
            lastSyncedBranch = branch;
            log.fine("Fast-forwarded " + label() + " base repo to " + abbreviateCommit(expectedCommit)
                + " on branch '" + branch + "'");
        } catch (IOException e) {
            File repoDir = pb.directory();
            if (repoDir != null && isGitLockPresent(repoDir)) {
                long staleThresholdMs = 10L * 60L * 1000L;
                if (isLikelyStaleGitLock(repoDir, staleThresholdMs)) {
                    log.warning("Detected stale git index lock while updating " + label() + " base repo; removing and retrying once...");
                    removeStaleGitIndexLock(repoDir, log);
                    ProcessIO.runOrThrow(pb, new String[]{"git", "checkout", "-B", branch, checkoutTarget});
                    ProcessIO.runAndExitCode(pb, new String[]{"git", "clean", "-df"});
                    ProcessIO.runOrThrow(pb, new String[]{"git", "reset", "--hard", expectedCommit});
                    if (!"FETCH_HEAD".equals(checkoutTarget)) {
                        ProcessIO.runAndExitCode(pb, new String[]{"git", "branch", "--set-upstream-to=" + remoteRef, branch});
                    }
                    lastSuccessfulSyncMs = System.currentTimeMillis();
                    lastSyncedBranch = branch;
                    log.fine("Fast-forwarded " + label() + " base repo to " + abbreviateCommit(expectedCommit)
                        + " on branch '" + branch + "' after clearing stale lock");
                } else {
                    log.warning("Git index.lock present; skipping " + label() + " base repo fast-forward for branch '" + branch + "'.");
                }
            } else {
                throw e;
            }
        }
    }

    private void ensureBaseRepoHasCommit(Logger log, String branch, String commit) throws IOException, InterruptedException {
        Path baseRepoRoot = getBaseRepoRoot();
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(baseRepoRoot.toFile());

        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "rev-parse", "--is-inside-work-tree"}) != 0) {
            throw new IOException(label() + " repo dir is not a git repository: " + baseRepoRoot);
        }

        if (isCommitAvailable(pb, commit)) {
            return;
        }

        List<String> remotes = candidateRemotes(pb);
        for (String remote : remotes) {
            if (!remoteBranchExists(pb, remote, branch)) {
                continue;
            }
            try {
                log.info("Fetching " + label() + " branch '" + branch + "' from remote '" + remote + "' to materialize commit " + abbreviateCommit(commit));
                ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", remote, branch});
                if (isCommitAvailable(pb, commit)) {
                    return;
                }
            } catch (Exception e) {
                log.warning("Failed to fetch " + label() + " branch '" + branch + "' from " + remote + ": " + e.getMessage());
            }
        }

        for (String remote : remotes) {
            try {
                log.info("Fetching " + label() + " commit " + abbreviateCommit(commit) + " from remote '" + remote + "'");
                ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", remote, commit});
                if (isCommitAvailable(pb, commit)) {
                    return;
                }
            } catch (Exception e) {
                log.fine("Direct " + label() + " commit fetch failed from " + remote + ": " + e.getMessage());
            }
        }

        throw new IOException(label() + " commit " + commit + " is not available in tester base repo after fetch attempts");
    }

    private boolean isCommitAvailable(ProcessBuilder pb, String commit) throws IOException, InterruptedException {
        if (commit == null || commit.trim().isEmpty()) {
            return false;
        }
        return ProcessIO.runAndExitCode(pb, new String[]{"git", "cat-file", "-e", commit + "^{commit}"}) == 0;
    }

    private String selectRemoteForBranch(ProcessBuilder pb, String branch) throws IOException, InterruptedException {
        String preferred = spec.preferredRemote;
        if (preferred == null || preferred.trim().isEmpty()) {
            preferred = "upstream";
        }
        if (remoteExists(pb, preferred) && remoteBranchExists(pb, preferred, branch)) {
            return preferred;
        }
        if (!"origin".equals(preferred) && remoteExists(pb, "origin") && remoteBranchExists(pb, "origin", branch)) {
            return "origin";
        }
        if (!"upstream".equals(preferred) && remoteExists(pb, "upstream") && remoteBranchExists(pb, "upstream", branch)) {
            return "upstream";
        }
        throw new IOException(label() + " branch '" + branch + "' not found on configured remotes");
    }

    private List<String> candidateRemotes(ProcessBuilder pb) throws IOException, InterruptedException {
        LinkedHashSet<String> remotes = new LinkedHashSet<>();
        String preferred = spec.preferredRemote;
        if (preferred != null && !preferred.trim().isEmpty()) {
            remotes.add(preferred.trim());
        }
        remotes.add("origin");
        remotes.add("upstream");

        List<String> available = new ArrayList<>();
        for (String remote : remotes) {
            if (remoteExists(pb, remote)) {
                available.add(remote);
            }
        }
        return available;
    }

    private boolean remoteExists(ProcessBuilder pb, String remote) throws IOException, InterruptedException {
        return ProcessIO.runAndExitCode(pb, new String[]{"git", "remote", "get-url", remote}) == 0;
    }

    private String abbreviateCommit(String commit) {
        if (commit == null) {
            return "unknown";
        }
        return commit.substring(0, Math.min(12, commit.length()));
    }

    private String runAndGetOutput(ProcessBuilder basePb, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(basePb.directory());
        pb.redirectErrorStream(true);
        GitCredentials.apply(pb);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed (" + exitCode + "): " + String.join(" ", cmd));
        }
        return output.toString();
    }

    /**
     * Internal method that performs the actual git sync operations.
     * Must be called while holding syncLock.
     */
    private void doSync(Logger log) throws IOException, InterruptedException {
        String repoPath = spec.repoDir;
        String targetBranch = spec.branch;
        File repoDir = new File(repoPath);
        if (!repoDir.exists() || !repoDir.isDirectory()) {
            log.warning(label() + " repo dir does not exist: " + repoPath + "; skipping sync");
            return;
        }

        if (spec.overlayActive) {
            log.fine("Using " + label() + " overlay workspace: " + repoPath +
                     " (source=" + spec.sourceDir + ")");
        }

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(repoDir);

        // Verify git repo
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "rev-parse", "--is-inside-work-tree"}) != 0) {
            log.warning(label() + " repo dir is not a git repository: " + repoPath + "; skipping sync");
            return;
        }

        // Determine preferred remote from config (default: upstream), fallback to origin if missing
        String preferred = spec.preferredRemote;
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

        log.info("Syncing " + label() + " repo: branch='" + targetBranch + "' via remote='" + chosenRemote + "'");

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
        GitCredentials.apply(lp);
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
