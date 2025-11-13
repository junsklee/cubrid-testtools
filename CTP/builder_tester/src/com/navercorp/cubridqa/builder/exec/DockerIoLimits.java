package com.navercorp.cubridqa.builder.exec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.logging.Logger;

/**
 * Utility class for handling Docker I/O limits and device detection.
 */
public class DockerIoLimits {

    /**
     * Detect the root block device for the filesystem.
     * Tries multiple methods to find the actual block device.
     *
     * @param logger Logger for diagnostic messages
     * @return Device path (e.g., "/dev/sda") or null if detection failed
     */
    public static String detectRootDevice(Logger logger) {
        // Try method 1: Use df and lsblk to find root device
        String device = detectViaLsblk(logger);
        if (device != null) {
            return device;
        }

        // Try method 2: Parse /proc/mounts
        device = detectViaProcMounts(logger);
        if (device != null) {
            return device;
        }

        // Try method 3: Use findmnt
        device = detectViaFindmnt(logger);
        if (device != null) {
            return device;
        }

        logger.warning("Unable to auto-detect root block device. I/O limits will not be applied.");
        return null;
    }

    /**
     * Detect root device using lsblk command.
     */
    private static String detectViaLsblk(Logger logger) {
        try {
            ProcessBuilder pb = new ProcessBuilder("lsblk", "-no", "PKNAME", "/");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    String device = "/dev/" + line.trim();
                    logger.fine("Detected root device via lsblk: " + device);
                    return device;
                }
            }

            process.waitFor();
        } catch (Exception e) {
            logger.fine("lsblk detection failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Detect root device by parsing /proc/mounts.
     */
    private static String detectViaProcMounts(Logger logger) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cat", "/proc/mounts");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2 && parts[1].equals("/")) {
                        String device = parts[0];
                        // Filter out non-block devices
                        if (device.startsWith("/dev/") && !device.contains("loop") && !device.contains("ram")) {
                            // Extract base device (remove partition number)
                            device = extractBaseDevice(device);
                            logger.fine("Detected root device via /proc/mounts: " + device);
                            return device;
                        }
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            logger.fine("/proc/mounts detection failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Detect root device using findmnt command.
     */
    private static String detectViaFindmnt(Logger logger) {
        try {
            ProcessBuilder pb = new ProcessBuilder("findmnt", "-no", "SOURCE", "/");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    String device = extractBaseDevice(line.trim());
                    logger.fine("Detected root device via findmnt: " + device);
                    return device;
                }
            }

            process.waitFor();
        } catch (Exception e) {
            logger.fine("findmnt detection failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Extract base device from a device path.
     * Removes partition numbers (e.g., /dev/sda1 -> /dev/sda).
     *
     * @param device Device path
     * @return Base device path
     */
    private static String extractBaseDevice(String device) {
        // Handle /dev/sdXN -> /dev/sdX
        if (device.matches("/dev/sd[a-z]\\d+")) {
            return device.replaceAll("\\d+$", "");
        }
        // Handle /dev/nvmeXnYpZ -> /dev/nvmeXnY
        if (device.matches("/dev/nvme\\d+n\\d+p\\d+")) {
            return device.replaceAll("p\\d+$", "");
        }
        // Handle /dev/vdXN -> /dev/vdX
        if (device.matches("/dev/vd[a-z]\\d+")) {
            return device.replaceAll("\\d+$", "");
        }
        // Handle /dev/xvdXN -> /dev/xvdX
        if (device.matches("/dev/xvd[a-z]\\d+")) {
            return device.replaceAll("\\d+$", "");
        }
        // Handle /dev/mmcblkXpY -> /dev/mmcblkX
        if (device.matches("/dev/mmcblk\\d+p\\d+")) {
            return device.replaceAll("p\\d+$", "");
        }
        return device;
    }
}
