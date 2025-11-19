package com.navercorp.cubridqa.builder.tester;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.logging.Logger;

/**
 * Measures and reports node hardware capacity.
 *
 * <p>Capacity is measured once at startup and cached. This provides baseline
 * metrics for the scheduler to understand node capabilities.</p>
 *
 * <p>Measurements include:
 * <ul>
 *   <li>CPU: cores × 100 (e.g., 600.0 for 6-core node)</li>
 *   <li>Memory: total system RAM in MB</li>
 *   <li>I/O: baseline disk throughput (MB/s) - estimated</li>
 *   <li>IOPS: baseline I/O operations per second - estimated</li>
 *   <li>Network: baseline network throughput (MB/s) - estimated</li>
 * </ul>
 * </p>
 */
public class NodeCapacity {

    private static final Logger logger = Logger.getLogger(NodeCapacity.class.getName());

    private final double cpuPct;
    private final double memMb;
    private final double ioMbPerSec;
    private final double ioReadMbPerSec;
    private final double ioWriteMbPerSec;
    private final double iops;
    private final double netMbPerSec;
    private final long diskFreeMb;
    private final long diskTotalMb;

    private NodeCapacity(Builder builder) {
        this.cpuPct = builder.cpuPct;
        this.memMb = builder.memMb;
        this.ioMbPerSec = builder.ioMbPerSec;
        this.ioReadMbPerSec = builder.ioReadMbPerSec;
        this.ioWriteMbPerSec = builder.ioWriteMbPerSec;
        this.iops = builder.iops;
        this.netMbPerSec = builder.netMbPerSec;
        this.diskFreeMb = builder.diskFreeMb;
        this.diskTotalMb = builder.diskTotalMb;
    }

    public double getCpuPct() {
        return cpuPct;
    }

    public double getMemMb() {
        return memMb;
    }

    public double getIoMbPerSec() {
        return ioMbPerSec;
    }

    public double getIoReadMbPerSec() {
        return ioReadMbPerSec;
    }

    public double getIoWriteMbPerSec() {
        return ioWriteMbPerSec;
    }

    public long getIoReadCapacityBytesPerSec() {
        return (long) (ioReadMbPerSec * 1024 * 1024);
    }

    public long getIoWriteCapacityBytesPerSec() {
        return (long) (ioWriteMbPerSec * 1024 * 1024);
    }

    public double getIops() {
        return iops;
    }

    public double getNetMbPerSec() {
        return netMbPerSec;
    }

    public long getDiskFreeMb() {
        return diskFreeMb;
    }

    public long getDiskTotalMb() {
        return diskTotalMb;
    }

    public boolean isDiskPressure() {
        // Consider disk pressure if less than 10% free or less than 5GB
        double freePercent = diskTotalMb > 0 ? ((double) diskFreeMb / diskTotalMb) * 100.0 : 100.0;
        return freePercent < 10.0 || diskFreeMb < 5120; // 5GB
    }

    /**
     * Converts capacity to JSON for /health endpoint.
     */
    public JSONObject toJSON() {
        JSONObject obj = new JSONObject();
        obj.put("cpu_pct", cpuPct);
        obj.put("mem_mb", memMb);
        obj.put("io_mb_s", ioMbPerSec);
        obj.put("iops", iops);
        obj.put("net_mb_s", netMbPerSec);
        return obj;
    }

    /**
     * Measures current node capacity.
     */
    public static NodeCapacity measure(String workDir) {
        Builder builder = new Builder();

        // Measure CPU cores
        int cores = Runtime.getRuntime().availableProcessors();
        builder.cpuPct(cores * 100.0);
        logger.info("NodeCapacity: Detected " + cores + " CPU cores (" + builder.cpuPct + "%)");

        // Measure total memory
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            long totalMemBytes = osBean.getTotalPhysicalMemorySize();
            builder.memMb(totalMemBytes / (1024.0 * 1024.0));
            logger.info("NodeCapacity: Total memory: " + String.format("%.0f", builder.memMb) + " MB");
        } catch (Exception e) {
            logger.warning("Could not determine total memory: " + e.getMessage());
            builder.memMb(8192.0); // Default 8GB
        }

        // Measure disk space
        try {
            File workDirFile = new File(workDir);
            long freeSpace = workDirFile.getFreeSpace();
            long totalSpace = workDirFile.getTotalSpace();
            builder.diskFreeMb(freeSpace / (1024 * 1024));
            builder.diskTotalMb(totalSpace / (1024 * 1024));
            logger.info("NodeCapacity: Disk: " + builder.diskFreeMb + " MB free / " + builder.diskTotalMb + " MB total");
        } catch (Exception e) {
            logger.warning("Could not determine disk space: " + e.getMessage());
            builder.diskFreeMb(50000); // Default 50GB free
            builder.diskTotalMb(100000); // Default 100GB total
        }

        // Estimate I/O capacity (conservative defaults for now)
        // Future: Could run fio or dd benchmark at startup
        // For read/write, split total capacity 50/50 by default (can be overridden via config)
        double totalIoMbPerSec = 500.0; // Conservative HDD/SSD baseline
        builder.ioMbPerSec(totalIoMbPerSec);
        builder.ioReadMbPerSec(totalIoMbPerSec / 2.0);  // Split evenly
        builder.ioWriteMbPerSec(totalIoMbPerSec / 2.0);
        builder.iops(10000.0);     // Conservative baseline
        logger.info("NodeCapacity: I/O estimates: " + builder.ioMbPerSec + " MB/s total (read: " + builder.ioReadMbPerSec + ", write: " + builder.ioWriteMbPerSec + "), " + builder.iops + " IOPS");

        // Estimate network capacity (conservative defaults)
        // Future: Could run iperf benchmark at startup
        builder.netMbPerSec(1000.0); // 1 Gbps baseline
        logger.info("NodeCapacity: Network estimate: " + builder.netMbPerSec + " MB/s");

        return builder.build();
    }

    /**
     * Measures current node capacity with config support for overrides.
     * @param workDir working directory path
     * @param config builder configuration (can be null for defaults)
     * @return measured node capacity
     */
    public static NodeCapacity measure(String workDir, com.navercorp.cubridqa.builder.BuilderConfig config) {
        Builder builder = new Builder();

        // Measure CPU cores
        int cores = Runtime.getRuntime().availableProcessors();
        builder.cpuPct(cores * 100.0);
        logger.info("NodeCapacity: Detected " + cores + " CPU cores (" + builder.cpuPct + "%)");

        // Measure memory
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            long totalMemBytes = osBean.getTotalPhysicalMemorySize();
            builder.memMb(totalMemBytes / (1024.0 * 1024.0));
            logger.info("NodeCapacity: Total memory: " + String.format("%.0f", builder.memMb) + " MB");
        } catch (Exception e) {
            logger.warning("Could not determine total memory: " + e.getMessage());
            builder.memMb(8192.0); // Default 8GB
        }

        // Measure disk space
        try {
            File workDirFile = new File(workDir);
            long freeSpace = workDirFile.getFreeSpace();
            long totalSpace = workDirFile.getTotalSpace();
            builder.diskFreeMb(freeSpace / (1024 * 1024));
            builder.diskTotalMb(totalSpace / (1024 * 1024));
            logger.info("NodeCapacity: Disk: " + builder.diskFreeMb + " MB free / " + builder.diskTotalMb + " MB total");
        } catch (Exception e) {
            logger.warning("Could not determine disk space: " + e.getMessage());
            builder.diskFreeMb(50000); // Default 50GB free
            builder.diskTotalMb(100000); // Default 100GB total
        }

        // Estimate I/O capacity (conservative defaults for now)
        // Future: Could run fio or dd benchmark at startup
        // For read/write, split total capacity 50/50 by default (can be overridden via config)
        double totalIoMbPerSec = 500.0; // Conservative HDD/SSD baseline
        builder.ioMbPerSec(totalIoMbPerSec);
        builder.ioReadMbPerSec(totalIoMbPerSec / 2.0);  // Split evenly
        builder.ioWriteMbPerSec(totalIoMbPerSec / 2.0);

        // IOPS capacity - configurable via tester.conf
        double iopsCapacity = 25000.0; // Default: moderate baseline for SATA SSD
        if (config != null) {
            String iopsStr = getConfigProperty(config, "node_iops_capacity", "25000");
            try {
                iopsCapacity = Double.parseDouble(iopsStr);
            } catch (NumberFormatException e) {
                logger.warning("Invalid node_iops_capacity value: " + iopsStr + ", using default 25000");
            }
        }
        builder.iops(iopsCapacity);
        logger.info("NodeCapacity: I/O estimates: " + builder.ioMbPerSec + " MB/s total (read: " + builder.ioReadMbPerSec + ", write: " + builder.ioWriteMbPerSec + "), " + builder.iops + " IOPS");

        // Estimate network capacity (conservative defaults)
        // Future: Could run iperf benchmark at startup
        builder.netMbPerSec(1000.0); // 1 Gbps baseline
        logger.info("NodeCapacity: Network estimate: " + builder.netMbPerSec + " MB/s");

        return builder.build();
    }

    /**
     * Helper method to read config property using reflection.
     * Based on pattern from ActualSampler.java.
     */
    private static String getConfigProperty(com.navercorp.cubridqa.builder.BuilderConfig config, String key, String defaultValue) {
        try {
            java.lang.reflect.Field propsField = config.getClass().getDeclaredField("properties");
            propsField.setAccessible(true);
            java.util.Properties props = (java.util.Properties) propsField.get(config);
            String value = props.getProperty(key, defaultValue);
            logger.fine("Config property '" + key + "' = '" + value + "' (default: '" + defaultValue + "')");
            return value;
        } catch (Exception e) {
            logger.fine("Could not read config property '" + key + "', using default: " + defaultValue);
            return defaultValue;
        }
    }

    private static class Builder {
        private double cpuPct = 0.0;
        private double memMb = 0.0;
        private double ioMbPerSec = 0.0;
        private double ioReadMbPerSec = 0.0;
        private double ioWriteMbPerSec = 0.0;
        private double iops = 0.0;
        private double netMbPerSec = 0.0;
        private long diskFreeMb = 0L;
        private long diskTotalMb = 0L;

        public Builder cpuPct(double val) {
            this.cpuPct = val;
            return this;
        }

        public Builder memMb(double val) {
            this.memMb = val;
            return this;
        }

        public Builder ioMbPerSec(double val) {
            this.ioMbPerSec = val;
            return this;
        }

        public Builder ioReadMbPerSec(double val) {
            this.ioReadMbPerSec = val;
            return this;
        }

        public Builder ioWriteMbPerSec(double val) {
            this.ioWriteMbPerSec = val;
            return this;
        }

        public Builder iops(double val) {
            this.iops = val;
            return this;
        }

        public Builder netMbPerSec(double val) {
            this.netMbPerSec = val;
            return this;
        }

        public Builder diskFreeMb(long val) {
            this.diskFreeMb = val;
            return this;
        }

        public Builder diskTotalMb(long val) {
            this.diskTotalMb = val;
            return this;
        }

        public NodeCapacity build() {
            return new NodeCapacity(this);
        }
    }
}
