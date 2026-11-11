package com.navercorp.cubridqa.builder;

import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;

/**
 * Which Builder-Tester code this JVM runs: bin/compile.sh writes build_stamp.properties into the jar
 * with a fingerprint of the source tree, the same wherever that source is compiled. The Builder
 * compares a tester's (from /health) with its own: restarting the Builder and its local tester
 * left the VM tester on code from days before (vm_sim/refresh_tester.sh not run), and nothing said so.
 */
public final class BuildStamp {
    public static final String UNKNOWN = "unknown";
    private static final Properties STAMP = load();
    private static final String STARTED_AT = Instant.now().toString();

    private BuildStamp() {}

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = BuildStamp.class.getResourceAsStream("build_stamp.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (Exception ignore) {
            // No stamp: compiled other than by bin/compile.sh
        }
        return p;
    }

    /** Fingerprint of the source tree compiled, or UNKNOWN. */
    public static String fingerprint() {
        return STAMP.getProperty("fingerprint", UNKNOWN);
    }

    /** When it was compiled, or UNKNOWN. */
    public static String built() {
        return STAMP.getProperty("built", UNKNOWN);
    }

    /** When this JVM loaded its code. */
    public static String startedAt() {
        return STARTED_AT;
    }

    public static String describe() {
        return fingerprint() + " (built " + built() + ", started " + startedAt() + ")";
    }
}
