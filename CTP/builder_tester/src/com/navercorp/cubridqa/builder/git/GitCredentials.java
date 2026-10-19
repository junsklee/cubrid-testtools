package com.navercorp.cubridqa.builder.git;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Credentials for the git commands that sync testcase checkouts.
 *
 * <p>A QA VM fetches its private testcase repository the way CTP's run_git_update does: with
 * git_user/git_pwd from CTP/conf/common.conf. They reach git through GIT_ASKPASS and the
 * environment, so they never appear on a command line or in a repository's .git/config. Every git
 * command also gets GIT_TERMINAL_PROMPT=0: a missing credential fails at once instead of waiting
 * for a terminal that is not there.
 */
public final class GitCredentials {
    private static final Logger logger = Logger.getLogger(GitCredentials.class.getName());
    private static volatile Map<String, String> environment = Collections.emptyMap();

    private GitCredentials() {}

    /** Adds the git environment when the command runs git. */
    public static void apply(ProcessBuilder pb) {
        if (pb.command().isEmpty() || !"git".equals(pb.command().get(0))) {
            return;
        }
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().putAll(environment);
    }

    /**
     * Uses git_user/git_pwd from ctpHome/conf/common.conf, if both are set. The askpass helper is
     * written to helperDir. Returns true when credentials are configured.
     */
    public static boolean configureFromCtp(Path ctpHome, Path helperDir) {
        Path commonConf = ctpHome.resolve("conf").resolve("common.conf");
        if (!Files.isRegularFile(commonConf)) {
            logger.info("No " + commonConf + "; git fetches use no credentials");
            return false;
        }
        try {
            Properties common = new Properties();
            try (Reader in = new InputStreamReader(new FileInputStream(commonConf.toFile()), StandardCharsets.UTF_8)) {
                common.load(in);
            }
            String user = common.getProperty("git_user", "").trim();
            String password = common.getProperty("git_pwd", "").trim();
            if (user.isEmpty() || password.isEmpty()) {
                logger.info(commonConf + " has no git_user/git_pwd; git fetches use no credentials");
                return false;
            }
            Files.createDirectories(helperDir);
            Path askpass = helperDir.resolve("git_askpass.sh");
            Files.write(askpass, ("#!/bin/sh\n"
                + "# Answers git's credential prompts from the environment (CTP common.conf git_user/git_pwd)\n"
                + "case \"$1\" in\n"
                + "  Username*) printf '%s\\n' \"$BT_GIT_USER\" ;;\n"
                + "  *) printf '%s\\n' \"$BT_GIT_PASSWORD\" ;;\n"
                + "esac\n").getBytes(StandardCharsets.UTF_8));
            Files.setPosixFilePermissions(askpass, PosixFilePermissions.fromString("rwx------"));
            Map<String, String> env = new HashMap<>();
            env.put("GIT_ASKPASS", askpass.toAbsolutePath().toString());
            env.put("BT_GIT_USER", user);
            env.put("BT_GIT_PASSWORD", password);
            environment = Collections.unmodifiableMap(env);
            logger.info("Git fetches use the credentials of " + commonConf + " (user " + user + ")");
            return true;
        } catch (IOException e) {
            logger.warning("Could not set up git credentials from " + commonConf + ": " + e.getMessage());
            return false;
        }
    }
}
