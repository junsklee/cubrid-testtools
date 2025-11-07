package com.navercorp.cubridqa.builder.exec;

import com.navercorp.cubridqa.builder.tester.TestRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Helper to resolve the execution directory for a test request.
 * Ensures we always derive the path from the local shell_tc_dir clone and
 * only fall back to the legacy absolute path when strictly necessary.
 */
final class TestDirectoryResolver {
    private TestDirectoryResolver() {}

    static Path resolve(Path shellRepoRoot, TestRequest request, Logger logger) {
        Path normalizedRoot = shellRepoRoot.toAbsolutePath().normalize();

        // Preferred: derive relative directory from testPath for local overlay clone
        String relativeDir = deriveRelativeDir(request);
        if (relativeDir != null) {
            Path candidate = normalizedRoot.resolve(relativeDir).normalize();
            if (isUsable(candidate, normalizedRoot)) {
                return candidate;
            }
            logger.warning("Derived test directory does not exist locally: " + candidate);
        }

        // Legacy fallback: trust the provided directory when it points to a valid path
        String providedDir = request.getTestDir();
        if (providedDir != null && !providedDir.trim().isEmpty()) {
            Path candidate = Paths.get(providedDir.trim()).toAbsolutePath().normalize();
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                logger.warning("Using legacy testDir path from request: " + candidate);
                return candidate;
            }
        }

        throw new IllegalArgumentException("Unable to resolve test directory for " +
            request.getTestName() + " (testPath=" + request.getTestPath() + ")");
    }

    static String deriveRelativeDir(TestRequest request) {
        String testPath = request.getTestPath();
        if (testPath == null) {
            return null;
        }

        String normalized = testPath.trim().replace('\\', '/');
        if (normalized.isEmpty()) {
            return null;
        }

        int idx = normalized.lastIndexOf('/');
        if (idx < 0) {
            return "";
        }

        String relative = normalized.substring(0, idx);
        // Strip leading slashes to keep the path relative to repo root
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return relative;
    }

    private static boolean isUsable(Path candidate, Path repoRoot) {
        if (!Files.exists(candidate) || !Files.isDirectory(candidate)) {
            return false;
        }
        try {
            Path realCandidate = candidate.toRealPath();
            Path realRoot = repoRoot.toRealPath();
            return realCandidate.startsWith(realRoot);
        } catch (Exception ignore) {
            return false;
        }
    }
}
