package com.navercorp.cubridqa.builder.ctp;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.exec.ProcessIO;
import com.navercorp.cubridqa.builder.tester.SafeIo;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Prepares the CTP payload used for SQL test execution: latest CTP from the
 * configured ref (default: develop of CUBRID/cubrid-testtools), or a pinned SHA.
 *
 * The single-case execution capability is provided by builder-tester's own
 * generated runner (which calls CTP's standard {@code ConsoleAgent runCQT}
 * entry point), so no upstream PR needs to be layered on top — the payload is
 * plain CTP.
 *
 * Payloads are cached under {@code <work_dir>/ctp_sql/payload_<sha7>/} so a
 * payload is rebuilt only when the resolved ref moves. Each payload contains
 * only the CTP components a test container needs: {@code CTP/{bin,common,conf,sql}}
 * (~16MB, prebuilt jars included — no compilation required).
 */
public class CtpProvisioner {
    private static final String[] PAYLOAD_DIRS = {"bin", "common", "conf", "sql"};

    private final BuilderConfig config;
    private final Object lock = new Object();

    public CtpProvisioner(BuilderConfig config) {
        this.config = config;
    }

    /** A prepared payload on disk plus its provenance. */
    public static class CtpPayload {
        public final Path ctpDir;
        public final String baseSha;
        public final String fingerprint;

        CtpPayload(Path ctpDir, String baseSha, String fingerprint) {
            this.ctpDir = ctpDir;
            this.baseSha = baseSha;
            this.fingerprint = fingerprint;
        }

        public JSONObject toProvenanceJson() {
            return new JSONObject()
                .put("baseSha", baseSha)
                .put("fingerprint", fingerprint)
                .put("harness", "builtin");
        }
    }

    /**
     * Resolve the CTP base SHA directly against the remote via {@code git
     * ls-remote}, without needing a local clone. Used by the Builder at request
     * setup so every tester provisions the exact same CTP.
     *
     * @return provenance JSON ({baseSha, harness:"builtin"}) or null on failure
     */
    public static JSONObject resolveRemoteProvenance(String repoUrl, String ref, String pinnedBaseSha, Logger log) {
        try {
            if (pinnedBaseSha != null && !pinnedBaseSha.trim().isEmpty()) {
                return new JSONObject().put("baseSha", pinnedBaseSha.trim()).put("harness", "builtin");
            }
            ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", repoUrl, "refs/heads/" + ref);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String baseSha = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2 && parts[1].equals("refs/heads/" + ref)) {
                        baseSha = parts[0];
                    }
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warning("git ls-remote timed out resolving CTP ref from " + repoUrl);
                return null;
            }
            if (process.exitValue() != 0 || baseSha == null || baseSha.isEmpty()) {
                log.warning("Could not resolve CTP ref '" + ref + "' from " + repoUrl);
                return null;
            }
            return new JSONObject().put("baseSha", baseSha).put("harness", "builtin");
        } catch (Exception e) {
            log.warning("Failed to resolve CTP provenance from " + repoUrl + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Prepare (or reuse) the CTP payload.
     *
     * @param requestedBaseSha exact base SHA to use, or null to resolve the configured ref's head
     */
    public CtpPayload preparePayload(String requestedBaseSha, Logger log) throws IOException, InterruptedException {
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

            String fingerprint = shortSha(baseSha);
            Path payloadDir = root.resolve("payload_" + fingerprint);
            Path ctpDir = payloadDir.resolve("CTP");
            if (Files.exists(payloadDir.resolve(".complete")) && Files.isDirectory(ctpDir)) {
                log.fine("Reusing cached CTP payload: " + payloadDir);
                touch(payloadDir);
                return new CtpPayload(ctpDir, baseSha, fingerprint);
            }

            log.info("Building CTP payload " + fingerprint + " (base " + shortSha(baseSha) + ")");
            buildPayload(repoDir, payloadDir, baseSha, log);
            evictOldPayloads(root, log);
            return new CtpPayload(ctpDir, baseSha, fingerprint);
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

    private void buildPayload(Path repoDir, Path payloadDir, String baseSha, Logger log)
            throws IOException, InterruptedException {
        ProcessBuilder repoPb = new ProcessBuilder();
        repoPb.directory(repoDir.toFile());

        Path worktree = payloadDir.getParent().resolve("wt_" + payloadDir.getFileName() + "_" + System.nanoTime());
        ProcessIO.runAndExitCode(repoPb, new String[]{"git", "worktree", "prune"});
        ProcessIO.runOrThrow(repoPb, new String[]{"git", "worktree", "add", "--force", "--detach",
            worktree.toString(), baseSha});
        try {
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
                .put("harness", "builtin")
                .put("createdAtMs", System.currentTimeMillis());
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
