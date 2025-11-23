package com.navercorp.cubridqa.builder.scheduler;

import org.json.JSONObject;
import org.json.JSONArray;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of a tester node's state from /health endpoint.
 *
 * <p>Used by NodeDirectory to track cluster state. Includes capacity,
 * utilization, cached artifacts, and health flags.</p>
 */
public class NodeSnapshot {

    private final String nodeId;
    private final Instant timestamp;
    private final String status;

    // Concurrency
    private final int maxConcurrentTests;
    private final int maxWhileHeavy;
    private final int maxAfterHeavy;
    private final int activeLimit;
    private final int heavyRunning;
    private final int retryRunning;
    private final int maxRetry;
    private final int runningTests;
    private final int queuedTests;

    // Capacity
    private final double cpuPct;
    private final double memMb;
    private final double ioMbPerSec;
    private final double ioReadMbPerSec;
    private final double ioWriteMbPerSec;
    private final double iops;
    private final double netMbPerSec;

    // Utilization (current usage)
    private final double usedCpuPct;
    private final double usedMemMb;
    private final double usedIoMbPerSec;
    private final double usedIoReadMbPerSec;
    private final double usedIoWriteMbPerSec;
    private final double usedIops;
    private final double usedNetMbPerSec;

    // Cached artifacts
    private final Set<String> cachedImages;
    private final Set<String> cachedPackages;

    // Health flags
    private final boolean degraded;
    private final boolean diskPressure;

    private NodeSnapshot(Builder builder) {
        this.nodeId = builder.nodeId;
        this.timestamp = builder.timestamp;
        this.status = builder.status;
        this.maxConcurrentTests = builder.maxConcurrentTests;
        this.maxWhileHeavy = builder.maxWhileHeavy;
        this.maxAfterHeavy = builder.maxAfterHeavy;
        this.activeLimit = builder.activeLimit;
        this.heavyRunning = builder.heavyRunning;
        this.retryRunning = builder.retryRunning;
        this.maxRetry = builder.maxRetry;
        this.runningTests = builder.runningTests;
        this.queuedTests = builder.queuedTests;
        this.cpuPct = builder.cpuPct;
        this.memMb = builder.memMb;
        this.ioMbPerSec = builder.ioMbPerSec;
        this.ioReadMbPerSec = builder.ioReadMbPerSec;
        this.ioWriteMbPerSec = builder.ioWriteMbPerSec;
        this.iops = builder.iops;
        this.netMbPerSec = builder.netMbPerSec;
        this.usedCpuPct = builder.usedCpuPct;
        this.usedMemMb = builder.usedMemMb;
        this.usedIoMbPerSec = builder.usedIoMbPerSec;
        this.usedIoReadMbPerSec = builder.usedIoReadMbPerSec;
        this.usedIoWriteMbPerSec = builder.usedIoWriteMbPerSec;
        this.usedIops = builder.usedIops;
        this.usedNetMbPerSec = builder.usedNetMbPerSec;
        this.cachedImages = Collections.unmodifiableSet(new HashSet<>(builder.cachedImages));
        this.cachedPackages = Collections.unmodifiableSet(new HashSet<>(builder.cachedPackages));
        this.degraded = builder.degraded;
        this.diskPressure = builder.diskPressure;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder pre-populated with this snapshot's values.
     * Useful for creating modified copies via optimistic updates.
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.nodeId = this.nodeId;
        builder.timestamp = this.timestamp;
        builder.status = this.status;
        builder.maxConcurrentTests = this.maxConcurrentTests;
        builder.maxWhileHeavy = this.maxWhileHeavy;
        builder.maxAfterHeavy = this.maxAfterHeavy;
        builder.activeLimit = this.activeLimit;
        builder.heavyRunning = this.heavyRunning;
        builder.retryRunning = this.retryRunning;
        builder.maxRetry = this.maxRetry;
        builder.runningTests = this.runningTests;
        builder.queuedTests = this.queuedTests;
        builder.cpuPct = this.cpuPct;
        builder.memMb = this.memMb;
        builder.ioMbPerSec = this.ioMbPerSec;
        builder.ioReadMbPerSec = this.ioReadMbPerSec;
        builder.ioWriteMbPerSec = this.ioWriteMbPerSec;
        builder.iops = this.iops;
        builder.netMbPerSec = this.netMbPerSec;
        builder.usedCpuPct = this.usedCpuPct;
        builder.usedMemMb = this.usedMemMb;
        builder.usedIoMbPerSec = this.usedIoMbPerSec;
        builder.usedIoReadMbPerSec = this.usedIoReadMbPerSec;
        builder.usedIoWriteMbPerSec = this.usedIoWriteMbPerSec;
        builder.usedIops = this.usedIops;
        builder.usedNetMbPerSec = this.usedNetMbPerSec;
        builder.cachedImages = new HashSet<>(this.cachedImages);
        builder.cachedPackages = new HashSet<>(this.cachedPackages);
        builder.degraded = this.degraded;
        builder.diskPressure = this.diskPressure;
        return builder;
    }

    /**
     * Parses NodeSnapshot from /health JSON response.
     */
    public static NodeSnapshot fromJSON(JSONObject json) {
        Builder builder = builder();

        if (json.has("nodeId")) {
            builder.nodeId(json.getString("nodeId"));
        }
        if (json.has("ts")) {
            builder.timestamp(Instant.parse(json.getString("ts")));
        }
        if (json.has("status")) {
            builder.status(json.getString("status"));
        }

        // Concurrency
        if (json.has("concurrency")) {
            JSONObject concurrency = json.getJSONObject("concurrency");
            builder.maxConcurrentTests(concurrency.optInt("max", 1));
            builder.maxWhileHeavy(concurrency.optInt("maxWhileHeavy", builder.maxConcurrentTests));
            builder.maxAfterHeavy(concurrency.optInt("maxAfterHeavy",
                    concurrency.optInt("maxPeak", builder.maxConcurrentTests)));
            builder.activeLimit(concurrency.optInt("activeLimit", builder.maxConcurrentTests));
            builder.heavyRunning(concurrency.optInt("heavyRunning", 0));
            builder.retryRunning(concurrency.optInt("retryRunning", 0));
            builder.maxRetry(concurrency.optInt("maxRetry", builder.maxConcurrentTests));
            builder.runningTests(concurrency.optInt("running", 0));
            builder.queuedTests(concurrency.optInt("queued", 0));
        }

        // Capacity - parse NEW canonical format (v2) with fallback to legacy format (v1)
        if (json.has("capacity")) {
            JSONObject capacity = json.getJSONObject("capacity");
            // NEW: canonical units (cpu_millicores, mem_bytes)
            if (capacity.has("cpu_millicores")) {
                builder.cpuPct(capacity.getLong("cpu_millicores") / 10.0); // mCPU → %
                builder.memMb(capacity.getLong("mem_bytes") / (1024.0 * 1024.0)); // bytes → MB
                // Parse read/write IO capacity (new format)
                long ioReadBps = capacity.optLong("io_read_bytes_per_sec", -1);
                long ioWriteBps = capacity.optLong("io_write_bytes_per_sec", -1);
                if (ioReadBps >= 0 && ioWriteBps >= 0) {
                    builder.ioReadMbPerSec(ioReadBps / (1024.0 * 1024.0));
                    builder.ioWriteMbPerSec(ioWriteBps / (1024.0 * 1024.0));
                    builder.ioMbPerSec((ioReadBps + ioWriteBps) / (1024.0 * 1024.0)); // Total
                } else {
                    // Fallback to legacy single IO value, split 50/50
                    double totalIo = capacity.optLong("io_bytes_per_sec", 0) / (1024.0 * 1024.0);
                    builder.ioMbPerSec(totalIo);
                    builder.ioReadMbPerSec(totalIo / 2.0);
                    builder.ioWriteMbPerSec(totalIo / 2.0);
                }
                builder.iops(capacity.optLong("iops", 0));
                builder.netMbPerSec(capacity.optLong("net_bytes_per_sec", 0) / (1024.0 * 1024.0));
            } else {
                // LEGACY: percentage-based units (cpu_pct, mem_mb)
                builder.cpuPct(capacity.optDouble("cpu_pct", 0.0));
                builder.memMb(capacity.optDouble("mem_mb", 0.0));
                double totalIo = capacity.optDouble("io_mb_s", 0.0);
                builder.ioMbPerSec(totalIo);
                builder.ioReadMbPerSec(totalIo / 2.0); // Split evenly for legacy
                builder.ioWriteMbPerSec(totalIo / 2.0);
                builder.iops(capacity.optDouble("iops", 0.0));
                builder.netMbPerSec(capacity.optDouble("net_mb_s", 0.0));
            }
        }

        // Utilization - parse NEW reserved format (v2) with fallback to legacy (v1)
        if (json.has("utilization_reserved")) {
            JSONObject utilization = json.getJSONObject("utilization_reserved");
            // NEW: canonical units
            builder.usedCpuPct(utilization.getLong("cpu_millicores") / 10.0); // mCPU → %
            builder.usedMemMb(utilization.getLong("mem_bytes") / (1024.0 * 1024.0)); // bytes → MB
            // Parse read/write IO utilization (new format)
            long usedIoReadBps = utilization.optLong("io_read_bytes_per_sec", -1);
            long usedIoWriteBps = utilization.optLong("io_write_bytes_per_sec", -1);
            if (usedIoReadBps >= 0 && usedIoWriteBps >= 0) {
                builder.usedIoReadMbPerSec(usedIoReadBps / (1024.0 * 1024.0));
                builder.usedIoWriteMbPerSec(usedIoWriteBps / (1024.0 * 1024.0));
                builder.usedIoMbPerSec((usedIoReadBps + usedIoWriteBps) / (1024.0 * 1024.0)); // Total
            } else {
                // Fallback to legacy single IO value, split 50/50
                double totalUsedIo = utilization.optLong("io_bytes_per_sec", 0) / (1024.0 * 1024.0);
                builder.usedIoMbPerSec(totalUsedIo);
                builder.usedIoReadMbPerSec(totalUsedIo / 2.0);
                builder.usedIoWriteMbPerSec(totalUsedIo / 2.0);
            }
            builder.usedIops(utilization.optLong("iops", 0));
            builder.usedNetMbPerSec(utilization.optLong("net_bytes_per_sec", 0) / (1024.0 * 1024.0));
        } else if (json.has("utilization")) {
            JSONObject utilization = json.getJSONObject("utilization");
            // LEGACY: percentage-based units
            builder.usedCpuPct(utilization.optDouble("cpu_pct", 0.0));
            builder.usedMemMb(utilization.optDouble("mem_mb", 0.0));
            double totalUsedIo = utilization.optDouble("io_mb_s", 0.0);
            builder.usedIoMbPerSec(totalUsedIo);
            builder.usedIoReadMbPerSec(totalUsedIo / 2.0); // Split evenly for legacy
            builder.usedIoWriteMbPerSec(totalUsedIo / 2.0);
            builder.usedIops(utilization.optDouble("iops", 0.0));
            builder.usedNetMbPerSec(utilization.optDouble("net_mb_s", 0.0));
        }

        // Images
        if (json.has("images")) {
            JSONObject images = json.getJSONObject("images");
            if (images.has("present")) {
                JSONArray present = images.getJSONArray("present");
                for (int i = 0; i < present.length(); i++) {
                    builder.addCachedImage(present.getString(i));
                }
            }
        }

        // Packages
        if (json.has("packages")) {
            JSONObject packages = json.getJSONObject("packages");
            if (packages.has("present")) {
                JSONArray present = packages.getJSONArray("present");
                for (int i = 0; i < present.length(); i++) {
                    builder.addCachedPackage(present.getString(i));
                }
            }
        }

        // Flags
        if (json.has("flags")) {
            JSONObject flags = json.getJSONObject("flags");
            builder.degraded(flags.optBoolean("degraded", false));
            builder.diskPressure(flags.optBoolean("disk_pressure", false));
        }

        return builder.build();
    }

    // Getters

    public String getNodeId() {
        return nodeId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getStatus() {
        return status;
    }

    public int getMaxConcurrentTests() {
        return maxConcurrentTests;
    }

    public int getRunningTests() {
        return runningTests;
    }

    public int getQueuedTests() {
        return queuedTests;
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

    public double getIops() {
        return iops;
    }

    public double getNetMbPerSec() {
        return netMbPerSec;
    }

    public double getUsedCpuPct() {
        return usedCpuPct;
    }

    public double getUsedMemMb() {
        return usedMemMb;
    }

    public double getUsedIoMbPerSec() {
        return usedIoMbPerSec;
    }

    public double getUsedIoReadMbPerSec() {
        return usedIoReadMbPerSec;
    }

    public double getUsedIoWriteMbPerSec() {
        return usedIoWriteMbPerSec;
    }

    public double getUsedIops() {
        return usedIops;
    }

    public double getUsedNetMbPerSec() {
        return usedNetMbPerSec;
    }

    public Set<String> getCachedImages() {
        return cachedImages;
    }

    public Set<String> getCachedPackages() {
        return cachedPackages;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public boolean isDiskPressure() {
        return diskPressure;
    }

    /**
     * Returns available concurrency headroom.
     */
    public int getAvailableConcurrency() {
        return Math.max(0, maxConcurrentTests - runningTests);
    }

    public int getMaxWhileHeavy() {
        return maxWhileHeavy;
    }

    public int getMaxAfterHeavy() {
        return maxAfterHeavy;
    }

    public int getActiveLimit() {
        return activeLimit;
    }

    public int getHeavyRunning() {
        return heavyRunning;
    }

    public int getRetryRunning() {
        return retryRunning;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    /**
     * Returns available retry slots (maxRetry - retryRunning).
     * Returns Integer.MAX_VALUE if maxRetry < 0 (unlimited).
     */
    public int getRetryHeadroom() {
        if (maxRetry < 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, maxRetry - retryRunning);
    }

    /**
     * Returns non-retry running count (running - retryRunning).
     */
    public int getNonRetryRunning() {
        return Math.max(0, runningTests - retryRunning);
    }

    /**
     * Returns available slots for new (non-retry) tests considering retry reservation.
     * If retries are pending cluster-wide, reserves slots for them.
     *
     * @param hasPendingRetries whether retries are pending in the cluster
     * @param retryReservedSlots number of slots to reserve per node for retries
     * @return available slots for new tests
     */
    public int getNewTestHeadroom(boolean hasPendingRetries, int retryReservedSlots) {
        int reserved = hasPendingRetries ? Math.min(retryReservedSlots, Math.max(0, maxRetry)) : 0;
        int nonRetryRunning = getNonRetryRunning();
        return Math.max(0, activeLimit - reserved - nonRetryRunning);
    }

    /**
     * Returns free capacity for a resource dimension.
     */
    public double getFreeCpuPct() {
        return Math.max(0.0, cpuPct - usedCpuPct);
    }

    public double getFreeMemMb() {
        return Math.max(0.0, memMb - usedMemMb);
    }

    public double getFreeIoMbPerSec() {
        return Math.max(0.0, ioMbPerSec - usedIoMbPerSec);
    }

    public double getFreeIoReadMbPerSec() {
        return Math.max(0.0, ioReadMbPerSec - usedIoReadMbPerSec);
    }

    public double getFreeIoWriteMbPerSec() {
        return Math.max(0.0, ioWriteMbPerSec - usedIoWriteMbPerSec);
    }

    public long getIoReadCapacityBytesPerSec() {
        return (long) (ioReadMbPerSec * 1024 * 1024);
    }

    public long getIoWriteCapacityBytesPerSec() {
        return (long) (ioWriteMbPerSec * 1024 * 1024);
    }

    public double getFreeIops() {
        return Math.max(0.0, iops - usedIops);
    }

    public double getFreeNetMbPerSec() {
        return Math.max(0.0, netMbPerSec - usedNetMbPerSec);
    }

    @Override
    public String toString() {
        return "NodeSnapshot{nodeId=" + nodeId + ", running=" + runningTests + "/" + maxConcurrentTests + ", status=" + status + "}";
    }

    public static final class Builder {
        private String nodeId = "unknown";
        private Instant timestamp = Instant.now();
        private String status = "unknown";
        private int maxConcurrentTests = 1;
        private int maxWhileHeavy = 1;
        private int maxAfterHeavy = 1;
        private int activeLimit = 1;
        private int heavyRunning = 0;
        private int retryRunning = 0;
        private int maxRetry = 0;
        private int runningTests = 0;
        private int queuedTests = 0;
        private double cpuPct = 0.0;
        private double memMb = 0.0;
        private double ioMbPerSec = 0.0;
        private double ioReadMbPerSec = 0.0;
        private double ioWriteMbPerSec = 0.0;
        private double iops = 0.0;
        private double netMbPerSec = 0.0;
        private double usedCpuPct = 0.0;
        private double usedMemMb = 0.0;
        private double usedIoMbPerSec = 0.0;
        private double usedIoReadMbPerSec = 0.0;
        private double usedIoWriteMbPerSec = 0.0;
        private double usedIops = 0.0;
        private double usedNetMbPerSec = 0.0;
        private Set<String> cachedImages = new HashSet<>();
        private Set<String> cachedPackages = new HashSet<>();
        private boolean degraded = false;
        private boolean diskPressure = false;

        private Builder() {
        }

        public Builder nodeId(String val) {
            this.nodeId = val;
            return this;
        }

        public Builder timestamp(Instant val) {
            this.timestamp = val;
            return this;
        }

        public Builder status(String val) {
            this.status = val;
            return this;
        }

        public Builder maxConcurrentTests(int val) {
            this.maxConcurrentTests = val;
            return this;
        }

        public Builder maxWhileHeavy(int val) {
            this.maxWhileHeavy = val;
            return this;
        }

        public Builder maxAfterHeavy(int val) {
            this.maxAfterHeavy = val;
            return this;
        }

        public Builder activeLimit(int val) {
            this.activeLimit = val;
            return this;
        }

        public Builder heavyRunning(int val) {
            this.heavyRunning = val;
            return this;
        }

        public Builder retryRunning(int val) {
            this.retryRunning = val;
            return this;
        }

        public Builder maxRetry(int val) {
            this.maxRetry = val;
            return this;
        }

        public Builder runningTests(int val) {
            this.runningTests = val;
            return this;
        }

        public Builder queuedTests(int val) {
            this.queuedTests = val;
            return this;
        }

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

        public Builder usedCpuPct(double val) {
            this.usedCpuPct = val;
            return this;
        }

        public Builder usedMemMb(double val) {
            this.usedMemMb = val;
            return this;
        }

        public Builder usedIoMbPerSec(double val) {
            this.usedIoMbPerSec = val;
            return this;
        }

        public Builder usedIoReadMbPerSec(double val) {
            this.usedIoReadMbPerSec = val;
            return this;
        }

        public Builder usedIoWriteMbPerSec(double val) {
            this.usedIoWriteMbPerSec = val;
            return this;
        }

        public Builder usedIops(double val) {
            this.usedIops = val;
            return this;
        }

        public Builder usedNetMbPerSec(double val) {
            this.usedNetMbPerSec = val;
            return this;
        }

        public Builder addCachedImage(String image) {
            this.cachedImages.add(image);
            return this;
        }

        public Builder addCachedPackage(String pkg) {
            this.cachedPackages.add(pkg);
            return this;
        }

        public Builder degraded(boolean val) {
            this.degraded = val;
            return this;
        }

        public Builder diskPressure(boolean val) {
            this.diskPressure = val;
            return this;
        }

        public NodeSnapshot build() {
            return new NodeSnapshot(this);
        }
    }
}
