package com.navercorp.cubridqa.builder.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses the machine-readable outcome of one SQL case execution.
 *
 * The generated sql_run_case.sh writes a single status line
 * ("SQLCASE_RESULT status=ok rc=0 core=0 [reason=...]") both to stdout and to
 * <out>/status.line. Exit codes are never trusted: CTP's ConsoleAgent exits 0
 * even for [NOK].
 */
public final class SqlResultParser {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_NOK = "nok";
    public static final String STATUS_NOTRUN = "notrun";
    public static final String STATUS_TIMEOUT = "timeout";
    public static final String STATUS_ERROR = "error";

    public static class SqlCaseOutcome {
        public final String status;
        public final boolean core;
        public final int rc;
        public final String reason;

        SqlCaseOutcome(String status, boolean core, int rc, String reason) {
            this.status = status;
            this.core = core;
            this.rc = rc;
            this.reason = reason;
        }

        public boolean isPass() {
            return STATUS_OK.equals(status);
        }

        public boolean isFail() {
            return STATUS_NOK.equals(status);
        }
    }

    private SqlResultParser() {
    }

    /** Parses a "SQLCASE_RESULT key=value ..." line; returns null if the marker is absent. */
    public static SqlCaseOutcome parseStatusLine(String line) {
        if (line == null) {
            return null;
        }
        int idx = line.indexOf("SQLCASE_RESULT");
        if (idx < 0) {
            return null;
        }
        Map<String, String> kv = new HashMap<>();
        for (String token : line.substring(idx + "SQLCASE_RESULT".length()).trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                kv.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        String status = kv.getOrDefault("status", STATUS_ERROR);
        boolean core = "1".equals(kv.get("core"));
        int rc = 0;
        try {
            rc = Integer.parseInt(kv.getOrDefault("rc", "0"));
        } catch (NumberFormatException ignore) {
        }
        return new SqlCaseOutcome(status, core, rc, kv.get("reason"));
    }

    /** Reads and parses <attemptDir>/status.line; returns null if missing/unparseable. */
    public static SqlCaseOutcome parseStatusFile(Path attemptDir) {
        Path statusFile = attemptDir.resolve("status.line");
        if (!Files.isReadable(statusFile)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(statusFile)) {
                SqlCaseOutcome outcome = parseStatusLine(line);
                if (outcome != null) {
                    return outcome;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** Scans arbitrary console output for the SQLCASE_RESULT marker (docker exec stdout). */
    public static SqlCaseOutcome parseConsole(String console) {
        if (console == null) {
            return null;
        }
        SqlCaseOutcome last = null;
        for (String line : console.split("\\R")) {
            SqlCaseOutcome outcome = parseStatusLine(line);
            if (outcome != null) {
                last = outcome;
            }
        }
        return last;
    }
}
