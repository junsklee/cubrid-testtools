package com.navercorp.cubridqa.builder.scheduler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Metrics payload sent by a tester when requesting work in pull-based scheduling.
 *
 * <p>Immutable value object – builder-style construction.</p>
 */
public class NodeMetrics {

    private final double cpuUsedPct;
    private final double memUsedMb;
    private final double ioReadUsedMbPerSec;
    private final double ioWriteUsedMbPerSec;
    private final double iopsUsed;
    private final double netUsedMbPerSec;
    private final int runningTests;
    private final int queuedTests;
    private final Map<TestClassifier.TestClass, Integer> runningClassCounts;

    private NodeMetrics(Builder builder) {
        this.cpuUsedPct = builder.cpuUsedPct;
        this.memUsedMb = builder.memUsedMb;
        this.ioReadUsedMbPerSec = builder.ioReadUsedMbPerSec;
        this.ioWriteUsedMbPerSec = builder.ioWriteUsedMbPerSec;
        this.iopsUsed = builder.iopsUsed;
        this.netUsedMbPerSec = builder.netUsedMbPerSec;
        this.runningTests = builder.runningTests;
        this.queuedTests = builder.queuedTests;
        this.runningClassCounts = Collections.unmodifiableMap(new HashMap<>(builder.runningClassCounts));
    }

    public double getCpuUsedPct() {
        return cpuUsedPct;
    }

    public double getMemUsedMb() {
        return memUsedMb;
    }

    public double getIoReadUsedMbPerSec() {
        return ioReadUsedMbPerSec;
    }

    public double getIoWriteUsedMbPerSec() {
        return ioWriteUsedMbPerSec;
    }

    public double getIopsUsed() {
        return iopsUsed;
    }

    public double getNetUsedMbPerSec() {
        return netUsedMbPerSec;
    }

    public int getRunningTests() {
        return runningTests;
    }

    public int getQueuedTests() {
        return queuedTests;
    }

    public Map<TestClassifier.TestClass, Integer> getRunningClassCounts() {
        return runningClassCounts;
    }

    public int getRunningClassCount(TestClassifier.TestClass cls) {
        return runningClassCounts.getOrDefault(cls, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double cpuUsedPct = 0.0;
        private double memUsedMb = 0.0;
        private double ioReadUsedMbPerSec = 0.0;
        private double ioWriteUsedMbPerSec = 0.0;
        private double iopsUsed = 0.0;
        private double netUsedMbPerSec = 0.0;
        private int runningTests = 0;
        private int queuedTests = 0;
        private final Map<TestClassifier.TestClass, Integer> runningClassCounts = new HashMap<>();

        private Builder() {
        }

        public Builder cpuUsedPct(double val) {
            this.cpuUsedPct = val;
            return this;
        }

        public Builder memUsedMb(double val) {
            this.memUsedMb = val;
            return this;
        }

        public Builder ioReadUsedMbPerSec(double val) {
            this.ioReadUsedMbPerSec = val;
            return this;
        }

        public Builder ioWriteUsedMbPerSec(double val) {
            this.ioWriteUsedMbPerSec = val;
            return this;
        }

        public Builder iopsUsed(double val) {
            this.iopsUsed = val;
            return this;
        }

        public Builder netUsedMbPerSec(double val) {
            this.netUsedMbPerSec = val;
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

        public Builder runningClassCount(TestClassifier.TestClass cls, int count) {
            Objects.requireNonNull(cls, "cls");
            this.runningClassCounts.put(cls, Math.max(0, count));
            return this;
        }

        public NodeMetrics build() {
            return new NodeMetrics(this);
        }
    }
}
