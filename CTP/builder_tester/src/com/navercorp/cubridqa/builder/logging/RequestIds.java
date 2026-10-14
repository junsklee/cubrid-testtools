package com.navercorp.cubridqa.builder.logging;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Request IDs become directory names (log/requests/&lt;id&gt;, shell_tc_requests/&lt;id&gt;, ...), and
 * several endpoints that receive them are unauthenticated. Every ID taken from a request body
 * or URL must pass {@link #isValid} before it is used to build a path.
 */
public final class RequestIds {

    // Builder IDs look like req_20261015_101500_ab12; allow other client-chosen IDs of the same alphabet.
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");

    private RequestIds() {}

    /** True if the ID is a single, harmless path segment: no separators, no "..", not hidden. */
    public static boolean isValid(String requestId) {
        return requestId != null
            && VALID.matcher(requestId).matches()
            && !requestId.contains("..");
    }

    /** Returns the ID unchanged, or throws IllegalArgumentException naming the bad value. */
    public static String require(String requestId) {
        if (!isValid(requestId)) {
            throw new IllegalArgumentException("Invalid requestId: " + abbreviate(requestId));
        }
        return requestId;
    }

    /**
     * Resolves {@code requestId} under {@code base} and checks the result is a direct child of it.
     */
    public static Path resolveUnder(Path base, String requestId) {
        require(requestId);
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path dir = normalizedBase.resolve(requestId).normalize();
        if (!normalizedBase.equals(dir.getParent())) {
            throw new IllegalArgumentException("requestId escapes " + normalizedBase + ": " + abbreviate(requestId));
        }
        return dir;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        String printable = value.replaceAll("[^\\x20-\\x7e]", "?");
        return printable.length() > 80 ? printable.substring(0, 80) + "..." : printable;
    }
}
