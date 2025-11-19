package com.navercorp.cubridqa.builder.docker;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.logging.Logger;

/**
 * SecureDockerEnv - Utility for securely passing environment variables to Docker containers
 *
 * This class creates temporary environment files that are passed to Docker via --env-file
 * instead of -e flags, preventing sensitive data (like tokens) from appearing in ps output.
 *
 * Usage:
 *   try (SecureDockerEnv env = new SecureDockerEnv()) {
 *       env.add("GITHUB_TOKEN", token);
 *       env.add("OTHER_VAR", value);
 *       dockerCmd.add("--env-file");
 *       dockerCmd.add(env.getFilePath());
 *       // ... run docker command
 *   } // file is automatically deleted
 */
public class SecureDockerEnv implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(SecureDockerEnv.class.getName());

    private final Path envFile;
    private final Map<String, String> variables;
    private boolean written;

    /**
     * Creates a new secure environment file in the system temp directory
     */
    public SecureDockerEnv() throws IOException {
        this.envFile = Files.createTempFile("docker-env-", ".env");
        this.variables = new LinkedHashMap<>();
        this.written = false;

        // Set restrictive permissions (owner read/write only) if on Unix
        try {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(envFile, perms);
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem (e.g., Windows), skip permission setting
            logger.fine("Cannot set POSIX permissions on: " + envFile);
        }

        logger.fine("Created secure Docker env file: " + envFile);
    }

    /**
     * Creates a new secure environment file in a specific directory
     */
    public SecureDockerEnv(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }
        this.envFile = Files.createTempFile(directory, "docker-env-", ".env");
        this.variables = new LinkedHashMap<>();
        this.written = false;

        // Set restrictive permissions (owner read/write only) if on Unix
        try {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(envFile, perms);
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem (e.g., Windows), skip permission setting
            logger.fine("Cannot set POSIX permissions on: " + envFile);
        }

        logger.fine("Created secure Docker env file: " + envFile);
    }

    /**
     * Adds an environment variable to the file
     *
     * @param name Variable name
     * @param value Variable value (can be null or empty)
     * @return this for chaining
     */
    public SecureDockerEnv add(String name, String value) {
        if (written) {
            throw new IllegalStateException("Cannot add variables after file has been written");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Variable name cannot be null or empty");
        }

        // Handle null or empty values
        variables.put(name, value != null ? value : "");
        return this;
    }

    /**
     * Writes all environment variables to the file
     * This is called automatically when getFilePath() is called
     */
    private void writeFile() throws IOException {
        if (written) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(envFile)) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();

                // Escape special characters in value
                String escapedValue = escapeValue(value);

                writer.write(name + "=" + escapedValue);
                writer.newLine();
            }
        }

        written = true;
        logger.fine("Wrote " + variables.size() + " variables to: " + envFile);
    }

    /**
     * Escapes special characters in environment variable values
     * According to Docker env-file format
     */
    private String escapeValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        // If value contains spaces, newlines, or special chars, quote it
        if (value.contains(" ") || value.contains("\n") || value.contains("\r") ||
            value.contains("\"") || value.contains("'") || value.contains("$")) {
            // Use double quotes and escape internal quotes and backslashes
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        return value;
    }

    /**
     * Gets the path to the environment file
     * Automatically writes the file if not already written
     *
     * @return Absolute path to the env file
     */
    public String getFilePath() throws IOException {
        writeFile();
        return envFile.toAbsolutePath().toString();
    }

    /**
     * Gets the number of variables stored
     */
    public int size() {
        return variables.size();
    }

    /**
     * Checks if the env file is empty
     */
    public boolean isEmpty() {
        return variables.isEmpty();
    }

    /**
     * Deletes the environment file
     * This is automatically called when using try-with-resources
     */
    @Override
    public void close() {
        try {
            if (Files.exists(envFile)) {
                Files.delete(envFile);
                logger.fine("Deleted secure Docker env file: " + envFile);
            }
        } catch (IOException e) {
            logger.warning("Failed to delete secure Docker env file: " + envFile + " - " + e.getMessage());
        }
    }

    /**
     * Creates a secure env file with a single variable
     * Convenience method for simple cases
     */
    public static SecureDockerEnv withVariable(String name, String value) throws IOException {
        SecureDockerEnv env = new SecureDockerEnv();
        env.add(name, value);
        return env;
    }
}
