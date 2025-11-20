package com.navercorp.cubridqa.builder.tester.demand;

import com.navercorp.cubridqa.builder.tester.stats.NodeHardware;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Canonical, capacity-normalized predicted demand with optional phases.
 *
 * <p>Canonical fields use hardware-independent units:
 * <ul>
 *   <li>cpuMillicores (int): CPU in mCPU (cores × 1000)</li>
 *   <li>memBytes (long): Memory in bytes (peak or enforced working set)</li>
 *   <li>ioBytesPerSec (long): Sequential IO budget in B/s (optional)</li>
 *   <li>iops (long): IOPS budget (optional)</li>
 *   <li>netBytesPerSec (long): Network budget in B/s (optional)</li>
 *   <li>durationMs (long): Predicted duration of the test or current phase</li>
 *   <li>confidence (double or per-dimension object)</li>
 * </ul></p>
 *
 * <p>Legacy fields (still accepted on wire, mapped to canonical):
 * <ul>
 *   <li>cpuPct, memMb, ioMbPerSec, netMbPerSec</li>
 * </ul></p>
 *
 * <p>Phases are optional; when present they override single-vector interpretation.</p>
 */
public class PredictedDemand {

    // Canonical single-vector (used when no phases are provided)
    private final long durationMs;
    private final int cpuMillicores;
    private final long memBytes;
    private final long ioBytesPerSec;
    private final long ioReadBytesPerSec;
    private final long ioWriteBytesPerSec;
    private final long iops;
    private final long netBytesPerSec;
    private final double confidence; // scalar (per-dimension optional via confidenceCpu/Mem/Io/Net/Iops)

    // Optional per-dimension confidences (0..1). If negative, scalar confidence applies.
    private final double confidenceCpu;
    private final double confidenceMem;
    private final double confidenceIo;
    private final double confidenceNet;
    private final double confidenceIops;

    // Optional phases (setup/run). If non-empty, scheduler/tester may reserve per phase.
    private final List<Phase> phases;

    /**
     * Represents a single phase of test execution (e.g., "setup", "run").
     */
    public static final class Phase {
        public final String name;
        public final long durationMs;
        public final int cpuMillicores;
        public final long memBytes;
        public final long ioBytesPerSec;
        public final long ioReadBytesPerSec;
        public final long ioWriteBytesPerSec;
        public final long iops;
        public final long netBytesPerSec;

        public Phase(String name, long durationMs, int cpuMillicores, long memBytes,
                     long ioBytesPerSec, long ioReadBytesPerSec, long ioWriteBytesPerSec,
                     long iops, long netBytesPerSec) {
            this.name = Objects.requireNonNull(name);
            this.durationMs = Math.max(0, durationMs);
            this.cpuMillicores = Math.max(0, cpuMillicores);
            this.memBytes = Math.max(0, memBytes);
            this.ioBytesPerSec = Math.max(0, ioBytesPerSec);
            this.ioReadBytesPerSec = Math.max(0, ioReadBytesPerSec);
            this.ioWriteBytesPerSec = Math.max(0, ioWriteBytesPerSec);
            this.iops = Math.max(0, iops);
            this.netBytesPerSec = Math.max(0, netBytesPerSec);
        }
    }

    private PredictedDemand(long durationMs, int cpuMillicores, long memBytes,
                            long ioBytesPerSec, long ioReadBytesPerSec, long ioWriteBytesPerSec,
                            long iops, long netBytesPerSec,
                            double confidence, double cCpu, double cMem, double cIo,
                            double cNet, double cIops, List<Phase> phases) {
        this.durationMs = Math.max(0, durationMs);
        this.cpuMillicores = Math.max(0, cpuMillicores);
        this.memBytes = Math.max(0, memBytes);
        this.ioBytesPerSec = Math.max(0, ioBytesPerSec);
        this.ioReadBytesPerSec = Math.max(0, ioReadBytesPerSec);
        this.ioWriteBytesPerSec = Math.max(0, ioWriteBytesPerSec);
        this.iops = Math.max(0, iops);
        this.netBytesPerSec = Math.max(0, netBytesPerSec);
        this.confidence = clamp01(confidence);
        this.confidenceCpu = clampOrNeg(cCpu);
        this.confidenceMem = clampOrNeg(cMem);
        this.confidenceIo = clampOrNeg(cIo);
        this.confidenceNet = clampOrNeg(cNet);
        this.confidenceIops = clampOrNeg(cIops);
        this.phases = phases == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(phases));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clampOrNeg(double v) {
        if (Double.isNaN(v)) return -1.0;
        if (v < 0) return -1.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Conservative defaults scaled to typical "mice" tests.
     *
     * @param hw node hardware for scaling (if null, uses absolute defaults)
     * @return PredictedDemand with conservative resource estimates
     */
    public static PredictedDemand conservative(NodeHardware hw) {
        if (hw == null) {
            // Absolute fallback when hardware unknown
            final long ioB = 30L * 1024 * 1024;  // 30MB/s total to avoid under-reporting IO
            final long ioRB = ioB / 2;            // split read/write conservatively
            final long ioWB = ioB - ioRB;
            return new PredictedDemand(30_000, 500, 512L * 1024 * 1024, ioB, ioRB, ioWB,
                    200, 5L * 1024 * 1024, 0.25, -1, -1, -1, -1, -1, null);
        }
        // Scale to node: ~0.5 cores per core count, 512MB, moderate IO
        final int cpuMc = Math.max(500, Math.min((int) (hw.getCpuPct() * 10), (int) (0.5 * hw.getCpuPct() * 10)));
        final long memB = 512L * 1024 * 1024; // 512MB
        final long ioB = 30L * 1024 * 1024;  // 30MB/s total
        final long ioRB = ioB / 2;            // split read/write conservatively
        final long ioWB = ioB - ioRB;
        final long netB = 5L * 1024 * 1024;  // 5MB/s
        return new PredictedDemand(30_000, cpuMc, memB, ioB, ioRB, ioWB, 200, netB, 0.25, -1, -1, -1, -1, -1, null);
    }

    /**
     * Parse from request JSON; accepts canonical and legacy fields.
     *
     * @param req test request with optional "predicted" block
     * @param hw  node hardware for legacy→canonical mapping (required for cpuPct conversion)
     * @return PredictedDemand (conservative defaults if absent)
     */
    public static PredictedDemand fromRequest(JSONObject req, NodeHardware hw) {
        if (req == null || !req.has("predicted")) return conservative(hw);
        final JSONObject p = req.getJSONObject("predicted");

        // Canonical fields
        long durationMs = p.optLong("durationMs", 30_000);
        int cpuMc = p.optInt("cpuMillicores", -1);
        long memBytes = p.optLong("memBytes", -1);
        long ioBps = p.optLong("ioBytesPerSec", -1);
        long ioReadBps = p.optLong("ioReadBytesPerSec", -1);
        long ioWriteBps = p.optLong("ioWriteBytesPerSec", -1);
        long iops = p.optLong("iops", -1);
        long netBps = p.optLong("netBytesPerSec", -1);

        // Legacy mapping → canonical (requires hw)
        if (cpuMc < 0 && p.has("cpuPct")) {
            double pct = Math.max(0.0, p.optDouble("cpuPct", 0.0));
            cpuMc = (int) Math.round((pct / 100.0) * (hw != null ? hw.getCpuPct() : 100.0) * 10.0);
        }
        if (memBytes < 0 && p.has("memMb")) {
            memBytes = (long) Math.max(0, p.optDouble("memMb", 0.0)) * 1024L * 1024L;
        }
        if (ioBps < 0 && p.has("ioMbPerSec")) {
            ioBps = (long) Math.max(0, p.optDouble("ioMbPerSec", 0.0)) * 1024L * 1024L;
            // Legacy single IO value → split evenly if read/write not provided
            if (ioReadBps < 0 && ioWriteBps < 0) {
                ioReadBps = ioBps / 2;
                ioWriteBps = ioBps - ioReadBps;
            }
        }
        // Legacy per-direction MB/s keys (builder may send these)
        if (ioReadBps < 0 && p.has("ioReadMbPerSec")) {
            ioReadBps = (long) Math.max(0, p.optDouble("ioReadMbPerSec", 0.0)) * 1024L * 1024L;
        }
        if (ioWriteBps < 0 && p.has("ioWriteMbPerSec")) {
            ioWriteBps = (long) Math.max(0, p.optDouble("ioWriteMbPerSec", 0.0)) * 1024L * 1024L;
        }
        if (ioReadBps < 0) ioReadBps = Math.max(0, p.optLong("ioReadBytesPerSec", -1));
        if (ioWriteBps < 0) ioWriteBps = Math.max(0, p.optLong("ioWriteBytesPerSec", -1));
        if (netBps < 0 && p.has("netMbPerSec")) {
            netBps = (long) Math.max(0, p.optDouble("netMbPerSec", 0.0)) * 1024L * 1024L;
        }

        double confidence = p.optDouble("confidence", 0.0);
        double cCpu = -1, cMem = -1, cIo = -1, cNet = -1, cIops = -1;
        if (p.has("confidenceObj")) {
            JSONObject co = p.getJSONObject("confidenceObj");
            cCpu = co.optDouble("cpu", -1);
            cMem = co.optDouble("mem", -1);
            cIo = co.optDouble("io", -1);
            cNet = co.optDouble("net", -1);
            cIops = co.optDouble("iops", -1);
        }

        List<Phase> phases = null;
        if (p.has("phases")) {
            phases = new ArrayList<>();
            JSONArray arr = p.getJSONArray("phases");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ph = arr.getJSONObject(i);
                long phIoBps = ph.optLong("ioBytesPerSec", Math.max(0, ioBps));
                long phIoReadBps = ph.optLong("ioReadBytesPerSec", -1);
                long phIoWriteBps = ph.optLong("ioWriteBytesPerSec", -1);
                if (phIoReadBps < 0 && phIoWriteBps < 0) {
                    // Use parent values or split evenly
                    phIoReadBps = (ioReadBps >= 0 ? ioReadBps : phIoBps / 2);
                    phIoWriteBps = (ioWriteBps >= 0 ? ioWriteBps : phIoBps - phIoReadBps);
                }
                phases.add(new Phase(
                        ph.optString("name", "phase-" + i),
                        ph.optLong("durationMs", 0L),
                        ph.optInt("cpuMillicores", Math.max(0, cpuMc)),
                        ph.optLong("memBytes", Math.max(0, memBytes)),
                        phIoBps,
                        phIoReadBps,
                        phIoWriteBps,
                        ph.optLong("iops", Math.max(0, iops)),
                        ph.optLong("netBytesPerSec", Math.max(0, netBps))
                ));
            }
        }

        if (cpuMc < 0) cpuMc = conservative(hw).cpuMillicores;
        if (memBytes < 0) memBytes = conservative(hw).memBytes;
        if (ioBps < 0) ioBps = Math.max(0, ioReadBps) + Math.max(0, ioWriteBps);
        if (ioReadBps < 0) ioReadBps = 0;
        if (ioWriteBps < 0) ioWriteBps = 0;
        if (netBps < 0) netBps = conservative(hw).netBytesPerSec;
        if (iops < 0) iops = 200;

        return new PredictedDemand(durationMs, cpuMc, memBytes, ioBps, ioReadBps, ioWriteBps, iops, netBps,
                confidence, cCpu, cMem, cIo, cNet, cIops, phases);
    }

    // Getters

    public long getDurationMs() {
        return durationMs;
    }

    public int getCpuMillicores() {
        return cpuMillicores;
    }

    public long getMemBytes() {
        return memBytes;
    }

    public long getIoBytesPerSec() {
        return ioBytesPerSec;
    }

    public long getIoReadBytesPerSec() {
        return ioReadBytesPerSec;
    }

    public long getIoWriteBytesPerSec() {
        return ioWriteBytesPerSec;
    }

    public long getIops() {
        return iops;
    }

    public long getNetBytesPerSec() {
        return netBytesPerSec;
    }

    public double getConfidence() {
        return confidence;
    }

    public double getConfidenceCpu() {
        return confidenceCpu;
    }

    public double getConfidenceMem() {
        return confidenceMem;
    }

    public double getConfidenceIo() {
        return confidenceIo;
    }

    public double getConfidenceNet() {
        return confidenceNet;
    }

    public double getConfidenceIops() {
        return confidenceIops;
    }

    public List<Phase> getPhases() {
        return phases;
    }

    /**
     * Returns true if this is a conservative default (low confidence).
     */
    public boolean isDefault() {
        return confidence < 0.5;
    }

    /**
     * Converts this prediction to a JSON object for wire transmission.
     */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("durationMs", durationMs);
        o.put("cpuMillicores", cpuMillicores);
        o.put("memBytes", memBytes);
        o.put("ioBytesPerSec", ioBytesPerSec);
        o.put("ioReadBytesPerSec", ioReadBytesPerSec);
        o.put("ioWriteBytesPerSec", ioWriteBytesPerSec);
        o.put("iops", iops);
        o.put("netBytesPerSec", netBytesPerSec);
        o.put("confidence", confidence);
        if (!phases.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Phase ph : phases) {
                JSONObject pj = new JSONObject()
                        .put("name", ph.name)
                        .put("durationMs", ph.durationMs)
                        .put("cpuMillicores", ph.cpuMillicores)
                        .put("memBytes", ph.memBytes)
                        .put("ioBytesPerSec", ph.ioBytesPerSec)
                        .put("ioReadBytesPerSec", ph.ioReadBytesPerSec)
                        .put("ioWriteBytesPerSec", ph.ioWriteBytesPerSec)
                        .put("iops", ph.iops)
                        .put("netBytesPerSec", ph.netBytesPerSec);
                arr.put(pj);
            }
            o.put("phases", arr);
        }
        return o;
    }

    @Override
    public String toString() {
        return String.format("PredictedDemand{dur=%dms, cpu=%dmCPU, mem=%dB, conf=%.2f}",
                durationMs, cpuMillicores, memBytes, confidence);
    }
}
