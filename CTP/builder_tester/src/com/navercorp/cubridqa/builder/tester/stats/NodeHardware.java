package com.navercorp.cubridqa.builder.tester.stats;

import java.util.Objects;

/**
 * Immutable description of a tester node's hardware capacity.
 *
 * <p>Used by the predictor to scale resource demands appropriately.
 * For example, a test historically using 50% CPU on a 4-core node
 * should be predicted to use 25% CPU on an 8-core node (assuming
 * the test itself is single-threaded).</p>
 *
 * <p>All fields represent maximum available capacity:
 * <ul>
 *   <li>cpuPct: cores × 100 (e.g., 600.0 for a 6-core node)</li>
 *   <li>memMb: total RAM in megabytes</li>
 *   <li>ioMbPerSec: baseline I/O throughput (MB/s)</li>
 *   <li>iops: baseline I/O operations per second</li>
 *   <li>netMbPerSec: baseline network throughput (MB/s)</li>
 * </ul>
 * </p>
 */
public final class NodeHardware {

    private final double cpuPct;
    private final double memMb;
    private final double ioMbPerSec;
    private final double iops;
    private final double netMbPerSec;

    private NodeHardware(Builder builder) {
        this.cpuPct = builder.cpuPct;
        this.memMb = builder.memMb;
        this.ioMbPerSec = builder.ioMbPerSec;
        this.iops = builder.iops;
        this.netMbPerSec = builder.netMbPerSec;
    }

    public static Builder builder() {
        return new Builder();
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

    public double getIops() {
        return iops;
    }

    public double getNetMbPerSec() {
        return netMbPerSec;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeHardware)) return false;
        NodeHardware that = (NodeHardware) o;
        return Double.compare(that.cpuPct, cpuPct) == 0
                && Double.compare(that.memMb, memMb) == 0
                && Double.compare(that.ioMbPerSec, ioMbPerSec) == 0
                && Double.compare(that.iops, iops) == 0
                && Double.compare(that.netMbPerSec, netMbPerSec) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(cpuPct, memMb, ioMbPerSec, iops, netMbPerSec);
    }

    @Override
    public String toString() {
        return "NodeHardware{cpu=" + cpuPct + "%, mem=" + memMb + "MB, io=" + ioMbPerSec + "MB/s, iops=" + iops + ", net=" + netMbPerSec + "MB/s}";
    }

    public static final class Builder {
        private double cpuPct = 0.0;
        private double memMb = 0.0;
        private double ioMbPerSec = 0.0;
        private double iops = 0.0;
        private double netMbPerSec = 0.0;

        private Builder() {
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

        public Builder iops(double val) {
            this.iops = val;
            return this;
        }

        public Builder netMbPerSec(double val) {
            this.netMbPerSec = val;
            return this;
        }

        public NodeHardware build() {
            return new NodeHardware(this);
        }
    }
}
