package com.navercorp.cubridqa.builder.tester.stats;

import org.json.JSONObject;
import java.util.Objects;

/**
 * Immutable prediction of a test's resource demands and duration.
 *
 * <p>Returned by the predictor and used by the scheduler to compute
 * bin-packing scores and estimate completion times.</p>
 *
 * <p>Resource fields are scaled to the target node's hardware capacity
 * and optionally inflated by a safety margin if confidence is low.</p>
 */
public final class PredictedDemand {

    private final long tpredMs;
    private final double cpuPct;
    private final double memMb;
    private final double ioMbPerSec;
    private final double iops;
    private final double netMbPerSec;
    private final double confidence;

    private PredictedDemand(Builder builder) {
        this.tpredMs = builder.tpredMs;
        this.cpuPct = builder.cpuPct;
        this.memMb = builder.memMb;
        this.ioMbPerSec = builder.ioMbPerSec;
        this.iops = builder.iops;
        this.netMbPerSec = builder.netMbPerSec;
        this.confidence = builder.confidence;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long getTpredMs() {
        return tpredMs;
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

    public double getConfidence() {
        return confidence;
    }

    /**
     * Converts this prediction to a JSON object for the /score endpoint.
     */
    public JSONObject toJSON() {
        JSONObject obj = new JSONObject();
        obj.put("tpred_ms", tpredMs);
        obj.put("cpu_pct", cpuPct);
        obj.put("mem_mb", memMb);
        obj.put("io_mb_s", ioMbPerSec);
        obj.put("iops", iops);
        obj.put("net_mb_s", netMbPerSec);
        obj.put("confidence", confidence);
        return obj;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PredictedDemand)) return false;
        PredictedDemand that = (PredictedDemand) o;
        return tpredMs == that.tpredMs
                && Double.compare(that.cpuPct, cpuPct) == 0
                && Double.compare(that.memMb, memMb) == 0
                && Double.compare(that.ioMbPerSec, ioMbPerSec) == 0
                && Double.compare(that.iops, iops) == 0
                && Double.compare(that.netMbPerSec, netMbPerSec) == 0
                && Double.compare(that.confidence, confidence) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tpredMs, cpuPct, memMb, ioMbPerSec, iops, netMbPerSec, confidence);
    }

    @Override
    public String toString() {
        return "PredictedDemand{tpred=" + tpredMs + "ms, cpu=" + cpuPct + "%, mem=" + memMb + "MB, conf=" + confidence + "}";
    }

    public static final class Builder {
        private long tpredMs = 0L;
        private double cpuPct = 0.0;
        private double memMb = 0.0;
        private double ioMbPerSec = 0.0;
        private double iops = 0.0;
        private double netMbPerSec = 0.0;
        private double confidence = 0.0;

        private Builder() {
        }

        public Builder tpredMs(long val) {
            this.tpredMs = val;
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

        public Builder iops(double val) {
            this.iops = val;
            return this;
        }

        public Builder netMbPerSec(double val) {
            this.netMbPerSec = val;
            return this;
        }

        public Builder confidence(double val) {
            this.confidence = val;
            return this;
        }

        public PredictedDemand build() {
            return new PredictedDemand(this);
        }
    }
}
