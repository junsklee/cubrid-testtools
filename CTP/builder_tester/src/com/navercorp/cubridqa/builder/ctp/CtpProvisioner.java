package com.navercorp.cubridqa.builder.ctp;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.exec.ProcessIO;
import com.navercorp.cubridqa.builder.tester.SafeIo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Prepares the CTP payload used for SQL test execution: latest CTP from the
 * configured ref (default: develop of CUBRID/cubrid-testtools) with the
 * configured PRs merged on top (default: #757, which adds bin/run_sql.sh for
 * single-case execution).
 *
 * Payloads are cached under <work_dir>/ctp_sql/payload_<fingerprint>/ where
 * the fingerprint is derived from the exact base and PR head SHAs, so a
 * payload is rebuilt only when upstream moves. The payload contains only the
 * CTP components needed inside a test container: CTP/{bin,common,conf,sql}
 * (~16MB, prebuilt jars included — no compilation required).
 */
public class CtpProvisioner {
    private static final String[] PAYLOAD_DIRS = {"bin", "common", "conf", "sql"};

    private final BuilderConfig config;
    private final Object lock = new Object();

    public CtpProvisioner(BuilderConfig config) {
        this.config = config;
    }

    /** A resolved PR reference: number + exact head SHA. */
    public static class PrRef {
        public final int number;
        public final String sha;

        public PrRef(int number, String sha) {
            this.number = number;
            this.sha = sha;
        }
    }

    /** A prepared payload on disk plus its provenance. */
    public static class CtpPayload {
        public final Path ctpDir;
        public final String baseSha;
        public final List<PrRef> prRefs;
        public final String fingerprint;

        CtpPayload(Path ctpDir, String baseSha, List<PrRef> prRefs, String fingerprint) {
            this.ctpDir = ctpDir;
            this.baseSha = baseSha;
            this.prRefs = prRefs;
            this.fingerprint = fingerprint;
        }

        public JSONObject toProvenanceJson() {
            JSONObject json = new JSONObject()
                .put("baseSha", baseSha)
                .put("fingerprint", fingerprint);
            JSONArray prs = new JSONArray();
            for (PrRef pr : prRefs) {
                prs.put(new JSONObject().put("pr", pr.number).put("sha", pr.sha));
            }
            json.put("prs", prs);
            return json;
        }
    }

    /**
     * Resolve the CTP provenance (base + PR head SHAs) directly against the
     * remote via `git ls-remote`, without needing a local clone. Used by the
     * Builder at request setup so every tester provisions the exact same CTP.
     *
     * @return provenance JSON ({baseSha, prs:[{pr,sha}]}) or null on failure
     */
    public static JSONObject resolveRemoteProvenance(String repoUrl, String ref, String pinnedBaseSha,
                                                     List<Integer> prs, Logger log) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("ls-remote");
            cmd.add(repoUrl);
            cmd.add("refs/heads/" + ref);
            for (Integer pr : prs) {
                cmd.add("refs/pull/" + pr + "/head");
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            Map<String, String> refToSha = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        refToSha.put(parts[1], parts[0]);
                    }
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warning("git ls-remote timed out resolving CTP provenance from " + repoUrl);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warning("git ls-remote failed (" + process.exitValue() + ") resolving CTP provenance from " + repoUrl);
                return null;
            }

            String baseSha = pinnedBaseSha != null && !pinnedBaseSha.trim().isEmpty()
                ? pinnedBaseSha.trim()
                : refToSha.get("refs/heads/" + ref);
            if (baseSha == null || baseSha.isEmpty()) {
                log.warning("Could not resolve CTP base ref '" + ref + "' from " + repoUrl);
                return null;
            }

            JSONObject provenance = new JSONObject().put("baseSha", baseSha);
            JSONArray prArray = new JSONArray();
            for (Integer pr : prs) {
                String sha = refToSha.get("refs/pull/" + pr + "/head");
                if (sha == null) {
                    log.warning("Could not resolve CTP PR #" + pr + " head from " + repoUrl);
                    return null;
                }
                prArray.put(new JSONObject().put("pr", pr).put("sha", sha));
            }
            provenance.put("prs", prArray);
            return provenance;
        } catch (Exception e) {
            log.warning("Failed to resolve CTP provenance from " + repoUrl + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Prepare (or reuse) the CTP payload for the given provenance.
     *
     * @param requestedBaseSha exact base SHA to use, or null to resolve the configured ref's head
     * @param requestedPrShas  PR number → exact head SHA overrides (may be empty; missing PRs are resolved live)
     */
    public CtpPayload preparePayload(String requestedBaseSha, Map<Integer, String> requestedPrShas, Logger log)
            throws IOException, InterruptedException {
        synchronized (lock) {
            Path root = Paths.get(config.getWorkDir()).resolve("ctp_sql");
            Files.createDirectories(root);
            Path repoDir = root.resolve("repo");
            ensureRepo(repoDir, log);

            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(repoDir.toFile());

            String pinned = config.getCtpSqlPin();
            String baseSha;
            if (pinned != null) {
                baseSha = pinned;
                ensureCommit(pb, baseSha, log);
            } else if (requestedBaseSha != null && !requestedBaseSha.trim().isEmpty()) {
                baseSha = requestedBaseSha.trim();
                ensureCommit(pb, baseSha, log);
            } else {
                ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", "origin", config.getCtpSqlRef()});
                baseSha = runAndGetOutput(pb, "git", "rev-parse", "FETCH_HEAD").trim();
            }

            List<PrRef> prRefs = new ArrayList<>();
            for (Integer pr : config.getCtpSqlPrs()) {
                String sha = requestedPrShas != null ? requestedPrShas.get(pr) : null;
                if (sha == null || sha.trim().isEmpty()) {
                    ProcessIO.runOrThrow(pb, new String[]{"git", "fetch", "origin", "pull/" + pr + "/head"});
                    sha = runAndGetOutput(pb, "git", "rev-parse", "FETCH_HEAD").trim();
                } else {
                    sha = sha.trim();
                    ensurePrCommit(pb, pr, sha, log);
                }
                prRefs.add(new PrRef(pr, sha));
            }

            StringBuilder fp = new StringBuilder(shortSha(baseSha));
            for (PrRef pr : prRefs) {
                fp.append("_pr").append(pr.number).append("-").append(shortSha(pr.sha));
            }
            String fingerprint = fp.toString();

            Path payloadDir = root.resolve("payload_" + fingerprint);
            Path ctpDir = payloadDir.resolve("CTP");
            if (Files.exists(payloadDir.resolve(".complete")) && Files.isDirectory(ctpDir)) {
                log.fine("Reusing cached CTP payload: " + payloadDir);
                touch(payloadDir);
                return new CtpPayload(ctpDir, baseSha, prRefs, fingerprint);
            }

            log.info("Building CTP payload " + fingerprint + " (base " + shortSha(baseSha)
                + (prRefs.isEmpty() ? ", no PRs" : ", +" + prRefs.size() + " PR(s)") + ")");
            buildPayload(repoDir, payloadDir, baseSha, prRefs, log);
            evictOldPayloads(root, log);
            return new CtpPayload(ctpDir, baseSha, prRefs, fingerprint);
        }
    }

    private void ensureRepo(Path repoDir, Logger log) throws IOException, InterruptedException {
        if (Files.isDirectory(repoDir.resolve(".git"))) {
            return;
        }
        log.info("Cloning CTP repository " + config.getCtpSqlRepo() + " into " + repoDir);
        Files.createDirectories(repoDir.getParent());
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(repoDir.getParent().toFile());
        ProcessIO.runOrThrow(pb, new String[]{
            "git", "clone", "--no-checkout", config.getCtpSqlRepo(), repoDir.getFileName().toString()});
    }

    private void ensureCommit(ProcessBuilder pb, String sha, Logger log) throws IOException, InterruptedException {
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "cat-file", "-e", sha + "^{commit}"}) == 0) {
            return;
        }
        // Try the configured ref first (cheap), then a direct SHA fetch.
        ProcessIO.runAndExitCode(pb, new String[]{"git", "fetch", "origin", config.getCtpSqlRef()});
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "cat-file", "-e", sha + "^{commit}"}) == 0) {
            return;
        }
        ProcessIO.runAndExitCode(pb, new String[]{"git", "fetch", "origin", sha});
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "cat-file", "-e", sha + "^{commit}"}) != 0) {
            throw new IOException("CTP base commit " + sha + " is not reachable from " + config.getCtpSqlRepo());
        }
    }

    private void ensurePrCommit(ProcessBuilder pb, int pr, String sha, Logger log) throws IOException, InterruptedException {
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "cat-file", "-e", sha + "^{commit}"}) == 0) {
            return;
        }
        ProcessIO.runAndExitCode(pb, new String[]{"git", "fetch", "origin", "pull/" + pr + "/head"});
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "cat-file", "-e", sha + "^{commit}"}) == 0) {
            return;
        }
        ProcessIO.runAndExitCode(pb, new String[]{"git", "fetch", "origin", sha});
        if (ProcessIO.runAndExitCode(pb, new String[]{"git", "cat-file", "-e", sha + "^{commit}"}) != 0) {
            throw new IOException("CTP PR #" + pr + " commit " + sha + " is not reachable from " + config.getCtpSqlRepo());
        }
    }

    private void buildPayload(Path repoDir, Path payloadDir, String baseSha, List<PrRef> prRefs, Logger log)
            throws IOException, InterruptedException {
        ProcessBuilder repoPb = new ProcessBuilder();
        repoPb.directory(repoDir.toFile());

        Path worktree = payloadDir.getParent().resolve("wt_" + payloadDir.getFileName() + "_" + System.nanoTime());
        ProcessIO.runAndExitCode(repoPb, new String[]{"git", "worktree", "prune"});
        ProcessIO.runOrThrow(repoPb, new String[]{"git", "worktree", "add", "--force", "--detach",
            worktree.toString(), baseSha});
        try {
            ProcessBuilder wtPb = new ProcessBuilder();
            wtPb.directory(worktree.toFile());
            for (PrRef pr : prRefs) {
                // Skip PRs already merged into the base.
                if (ProcessIO.runAndExitCode(wtPb, new String[]{"git", "merge-base", "--is-ancestor", pr.sha, "HEAD"}) == 0) {
                    log.info("CTP PR #" + pr.number + " (" + shortSha(pr.sha) + ") is already contained in base; skipping merge");
                    continue;
                }
                try {
                    ProcessIO.runOrThrow(wtPb, new String[]{
                        "git", "-c", "user.name=builder-tester", "-c", "user.email=builder-tester@localhost",
                        "merge", "--no-ff", "--no-edit", "-m", "builder-tester: apply CTP PR #" + pr.number, pr.sha});
                } catch (IOException e) {
                    ProcessIO.runAndExitCode(wtPb, new String[]{"git", "merge", "--abort"});
                    throw new IOException("Failed to merge CTP PR #" + pr.number + " (" + shortSha(pr.sha)
                        + ") onto base " + shortSha(baseSha)
                        + ". Pin a compatible base via ctp_sql_pin or adjust ctp_sql_prs. Cause: " + e.getMessage(), e);
                }
            }

            // Stage only the components containers need, into a temp dir, then move atomically.
            Path staging = payloadDir.getParent().resolve(payloadDir.getFileName() + ".tmp");
            SafeIo.deleteDirectory(staging.toFile());
            Path stagingCtp = staging.resolve("CTP");
            Files.createDirectories(stagingCtp);
            for (String dir : PAYLOAD_DIRS) {
                Path source = worktree.resolve("CTP").resolve(dir);
                if (!Files.isDirectory(source)) {
                    throw new IOException("CTP payload component missing in checkout: " + source);
                }
                SafeIo.copyTestCaseDirectory(source, stagingCtp.resolve(dir));
            }

            JSONObject provenance = new JSONObject()
                .put("repo", config.getCtpSqlRepo())
                .put("ref", config.getCtpSqlRef())
                .put("baseSha", baseSha)
                .put("createdAtMs", System.currentTimeMillis());
            JSONArray prs = new JSONArray();
            for (PrRef pr : prRefs) {
                prs.put(new JSONObject().put("pr", pr.number).put("sha", pr.sha));
            }
            provenance.put("prs", prs);
            Files.write(staging.resolve("provenance.json"), provenance.toString(2).getBytes("UTF-8"));
            Files.write(staging.resolve(".complete"), new byte[0]);

            SafeIo.deleteDirectory(payloadDir.toFile());
            try {
                Files.move(staging, payloadDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(staging, payloadDir);
            }
            log.info("CTP payload ready: " + payloadDir);
        } finally {
            ProcessIO.runAndExitCode(repoPb, new String[]{"git", "worktree", "remove", "--force", worktree.toString()});
            ProcessIO.runAndExitCode(repoPb, new String[]{"git", "worktree", "prune"});
            SafeIo.deleteDirectory(worktree.toFile());
        }
    }

    private void evictOldPayloads(Path root, Logger log) {
        int keep = Math.max(1, config.getCtpSqlPayloadKeep());
        List<Path> payloads = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "payload_*")) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    payloads.add(p);
                }
            }
        } catch (IOException e) {
            return;
        }
        if (payloads.size() <= keep) {
            return;
        }
        payloads.sort(Comparator.comparingLong(p -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));
        for (int i = 0; i < payloads.size() - keep; i++) {
            Path victim = payloads.get(i);
            log.info("Evicting old CTP payload: " + victim);
            SafeIo.deleteDirectory(victim.toFile());
        }
    }

    private void touch(Path dir) {
        try {
            Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignore) {
        }
    }

    private String shortSha(String sha) {
        if (sha == null) {
            return "unknown";
        }
        return sha.substring(0, Math.min(7, sha.length()));
    }

    private String runAndGetOutput(ProcessBuilder basePb, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(basePb.directory());
        pb.redirectErrorStream(true);
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
}
