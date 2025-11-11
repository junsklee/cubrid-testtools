package com.navercorp.cubridqa.builder.tester.demand;

import org.json.JSONObject;

/**
 * Immutable representation of predicted resource demands for a test.
 *
 * <p>Contains predictions for duration and multi-dimensional resource usage
 * (CPU, memory, I/O, IOPS, network) along with a confidence score.</p>
 *
 * <p>Used by RunningTestTracker to compute actual utilization based on
 * running test predictions rather than conservative fixed estimates.</p>
 */
public class PredictedDemand {

    private final long durationMs;
    private final double cpuPct;
    private final double memMb;
    private final double ioMbPerSec;
    private final double iops;
    private final double netMbPerSec;
    private final double confidence;

    private PredictedDemand(Builder builder) {
        this.durationMs = builder.durationMs;
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

    /**
     * Creates conservative default predictions.
     * Used when test request has no prediction data.
     */
    public static PredictedDemand conservative() {
        return builder()
                .durationMs(30000L)
                .cpuPct(50.0)
                .memMb(512.0)
                .ioMbPerSec(10.0)
                .iops(200.0)
                .netMbPerSec(5.0)
                .confidence(0.0)
                .build();
    }

    /**
     * Parses predicted demand from test request JSON.
     *
     * @param request the test request containing optional "predicted" field
     * @return PredictedDemand from request, or conservative defaults if absent
     */
    public static PredictedDemand fromRequest(JSONObject request) {
        if (!request.has("predicted")) {
            return conservative();
        }

        JSONObject predicted = request.getJSONObject("predicted");

        // Validate and sanitize values
        return builder()
                .durationMs(sanitizeLong(predicted.optLong("durationMs", 30000L), 1L, 86400000L))
                .cpuPct(sanitizeDouble(predicted.optDouble("cpuPct", 50.0), 0.0, 800.0))
                .memMb(sanitizeDouble(predicted.optDouble("memMb", 512.0), 0.0, 524288.0))
                .ioMbPerSec(sanitizeDouble(predicted.optDouble("ioMbPerSec", 10.0), 0.0, 10000.0))
                .iops(sanitizeDouble(predicted.optDouble("iops", 200.0), 0.0, 1000000.0))
                .netMbPerSec(sanitizeDouble(predicted.optDouble("netMbPerSec", 5.0), 0.0, 10000.0))
                .confidence(sanitizeDouble(predicted.optDouble("confidence", 0.0), 0.0, 1.0))
                .build();
    }

    private static long sanitizeLong(long value, long min, long max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static double sanitizeDouble(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    // Getters

    public long getDurationMs() {
        return durationMs;
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
     * Returns true if this is a conservative default (no historical data).
     */
    public boolean isDefault() {
        return confidence == 0.0;
    }

    @Override
    public String toString() {
        return String.format("PredictedDemand{dur=%dms, cpu=%.1f%%, mem=%.0fMB, conf=%.2f}",
                durationMs, cpuPct, memMb, confidence);
    }

    public static final class Builder {
        private long durationMs = 30000L;
        private double cpuPct = 50.0;
        private double memMb = 512.0;
        private double ioMbPerSec = 10.0;
        private double iops = 200.0;
        private double netMbPerSec = 5.0;
        private double confidence = 0.0;

        private Builder() {
        }

        public Builder durationMs(long val) {
            this.durationMs = val;
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
