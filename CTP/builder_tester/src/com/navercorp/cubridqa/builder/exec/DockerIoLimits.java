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
     * First finds the device mounted on /, then gets its parent device.
     */
    private static String detectViaLsblk(Logger logger) {
        try {
            // First, find which device is mounted on /
            ProcessBuilder pb1 = new ProcessBuilder("findmnt", "-n", "-o", "SOURCE", "/");
            pb1.redirectErrorStream(true);
            Process process1 = pb1.start();

            String mountedDevice = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process1.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    mountedDevice = line.trim();
                }
            }
            process1.waitFor();

            if (mountedDevice == null || !mountedDevice.startsWith("/dev/")) {
                logger.fine("Could not find mounted device for /");
                return null;
            }

            // Now get the parent device name using lsblk
            ProcessBuilder pb2 = new ProcessBuilder("lsblk", "-no", "PKNAME", mountedDevice);
            pb2.redirectErrorStream(false); // Don't merge stderr to stdout
            Process process2 = pb2.start();

            String parentDevice = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process2.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty() && !line.contains("not a block device")) {
                    parentDevice = "/dev/" + line.trim();
                }
            }
            process2.waitFor();

            // If we got a parent device, use it; otherwise use the base of the mounted device
            if (parentDevice != null && !parentDevice.equals("/dev/")) {
                logger.fine("Detected root device via lsblk: " + parentDevice);
                return parentDevice;
            } else {
                // Extract base device from mounted device (e.g., /dev/sda1 -> /dev/sda)
                String baseDevice = extractBaseDevice(mountedDevice);
                logger.fine("Detected root device via lsblk (base extraction): " + baseDevice);
                return baseDevice;
            }
        } catch (Exception e) {
            logger.fine("lsblk detection failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Detect root device by parsing /proc/mounts directly (no external command).
     */
    private static String detectViaProcMounts(Logger logger) {
        try {
            java.nio.file.Path mountsPath = java.nio.file.Paths.get("/proc/mounts");
            if (!java.nio.file.Files.exists(mountsPath)) {
                logger.fine("/proc/mounts does not exist");
                return null;
            }

            try (BufferedReader reader = java.nio.file.Files.newBufferedReader(mountsPath)) {
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
            ProcessBuilder pb = new ProcessBuilder("findmnt", "-n", "-o", "SOURCE", "/");
            pb.redirectErrorStream(false); // Don't merge stderr to stdout
            Process process = pb.start();

            String device = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty() && line.trim().startsWith("/dev/")) {
                    device = extractBaseDevice(line.trim());
                }
            }

            process.waitFor();

            if (device != null) {
                logger.fine("Detected root device via findmnt: " + device);
                return device;
            }
        } catch (Exception e) {
            logger.fine("findmnt detection failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Extract base device from a device path.
     * Removes partition numbers (e.g., /dev/sda1 -> /dev/sda).
     * For LVM/mapper devices, tries to find the underlying physical device.
     *
     * @param device Device path
     * @return Base device path
     */
    private static String extractBaseDevice(String device) {
        // Handle LVM and device mapper devices - try to find underlying device
        if (device.startsWith("/dev/mapper/") || device.startsWith("/dev/dm-")) {
            String underlying = findUnderlyingDevice(device);
            if (underlying != null) {
                return underlying;
            }
            // If we can't find underlying device, return as-is
            return device;
        }

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

    /**
     * Find the underlying physical device for LVM/mapper devices.
     * Uses lsblk -s to traverse the device stack (inverse dependencies).
     */
    private static String findUnderlyingDevice(String device) {
        try {
            // Use lsblk -s to show the full device stack including parent devices
            ProcessBuilder pb = new ProcessBuilder("lsblk", "-s", "-n", "-o", "NAME,TYPE", device);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String physicalDevice = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Remove tree drawing characters only at the start of the line
                    String cleaned = line.replaceAll("^[\\s│├└`─\\-]+", "").trim();
                    String[] parts = cleaned.split("\\s+");
                    if (parts.length >= 2) {
                        String name = parts[0];
                        String type = parts[1];
                        // Look for the physical disk device
                        if (type.equals("disk")) {
                            physicalDevice = "/dev/" + name;
                            break;
                        }
                    }
                }
            }
            process.waitFor();
            return physicalDevice;
        } catch (Exception e) {
            // If we can't determine underlying device, return null
            return null;
        }
    }
}
