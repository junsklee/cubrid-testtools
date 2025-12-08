#!/usr/bin/env python3
"""
DEBUG/ANALYSIS TOOL: Generate test profile report from WAL statistics.

NOTE: This script is NOT required for production use. The Builder automatically
loads profiles from the Tester's WAL system (latest.json.gz) and computes
NORMAL/HEAVY/EXTREME classifications dynamically using HeavyProfiler.java.

Use this script for:
- Debugging: Inspect which tests are classified as HEAVY/EXTREME
- Analysis: Validate classification thresholds before deployment
- One-time reports: Generate human-readable classification summaries

The Builder does NOT use the output file - it reads WAL stats directly.

Classification Algorithm:
- Compute global means for CPU, memory, I/O, and IOPS.
- For each test, compute ratios: avg_metric / global_mean
- Classify based on max ratio across all dimensions:
  - ratio < 2.0 → NORMAL
  - 2.0 <= ratio < 4.0 → HEAVY
  - ratio >= 4.0 → EXTREME

Usage:
    python generate_test_profiles.py [--input latest.json] [--output test_profiles.json]

Example:
    # Analyze current WAL stats
    python scripts/generate_test_profiles.py \\
        --input ~/tmp/tester_work/profiles/latest.json.gz \\
        --output /tmp/profile_report.json
    
    # Inspect EXTREME tests
    cat /tmp/profile_report.json | jq '.profiles[] | select(.heavyClass == "EXTREME")'
"""

import argparse
import gzip
import json
import sys
import os
from collections import defaultdict
from datetime import datetime
from typing import Dict, List, Optional, Tuple

# Classification thresholds
HEAVY_THRESHOLD = 2.0
EXTREME_THRESHOLD = 4.0

# Minimum observations for reliable classification
MIN_OBSERVATIONS = 3

# Default peak blending weights (set to 0 to classify on means; peaks still parsed/output)
DEFAULT_PEAK_WEIGHTS = {
    "cpu": 0.0,
    "mem": 0.0,
    "io": 0.0,
    "iops": 0.0,
}


class TestStats:
    """Raw test statistics from latest.json."""
    
    def __init__(self, test_key: str, data: dict):
        self.test_key = test_key
        self.observation_count = data.get('observation_count', data.get('counts', 0))
        
        # Duration
        duration_data = data.get('duration_ms', {})
        self.duration_ewma_ms = duration_data.get('ewma', duration_data.get('p50', 30000))
        
        # CPU
        cpu_data = data.get('cpu_pct', {})
        self.cpu_pct_avg = cpu_data.get('avg', cpu_data.get('mean', 50.0))
        self.cpu_pct_peak = cpu_data.get('peak', cpu_data.get('p95', self.cpu_pct_avg))
        
        # Memory
        mem_data = data.get('mem_mb', {})
        self.mem_mb_avg = mem_data.get('avg', mem_data.get('mean', 512.0))
        self.mem_mb_peak = mem_data.get('peak', mem_data.get('p95', self.mem_mb_avg))
        
        # I/O
        io_data = data.get('io_mb_s', {})
        self.io_mb_s_avg = io_data.get('avg', io_data.get('mean', 10.0))
        self.io_mb_s_peak = io_data.get('peak', self.io_mb_s_avg)
        
        # IOPS
        iops_data = data.get('iops', {})
        self.iops_avg = iops_data.get('avg', iops_data.get('mean', 200.0))
        self.iops_peak = iops_data.get('peak', self.iops_avg)


class GlobalStats:
    """Global statistics computed from all tests."""
    
    def __init__(self, mean_cpu: float, mean_mem: float, mean_io: float, mean_iops: float, test_count: int):
        self.mean_cpu = mean_cpu
        self.mean_mem = mean_mem
        self.mean_io = mean_io
        self.mean_iops = mean_iops
        self.test_count = test_count


class TestProfile:
    """Computed test profile with heavy classification."""
    
    def __init__(self, test_key: str, heavy_class: str, dominant_dim: str,
                 cpu_ratio: float, mem_ratio: float, io_ratio: float, iops_ratio: float,
                 predicted_duration_ms: int, avg_cpu_pct: float, avg_mem_mb: float,
                 avg_io_mb_s: float, avg_iops: float,
                 eff_cpu_pct: float, eff_mem_mb: float, eff_io_mb_s: float, eff_iops: float,
                 peak_cpu_pct: float, peak_mem_mb: float, peak_io_mb_s: float, peak_iops: float):
        self.test_key = test_key
        self.heavy_class = heavy_class
        self.dominant_dim = dominant_dim
        self.cpu_ratio = cpu_ratio
        self.mem_ratio = mem_ratio
        self.io_ratio = io_ratio
        self.iops_ratio = iops_ratio
        self.predicted_duration_ms = predicted_duration_ms
        self.avg_cpu_pct = avg_cpu_pct
        self.avg_mem_mb = avg_mem_mb
        self.avg_io_mb_s = avg_io_mb_s
        self.avg_iops = avg_iops
        self.eff_cpu_pct = eff_cpu_pct
        self.eff_mem_mb = eff_mem_mb
        self.eff_io_mb_s = eff_io_mb_s
        self.eff_iops = eff_iops
        self.peak_cpu_pct = peak_cpu_pct
        self.peak_mem_mb = peak_mem_mb
        self.peak_io_mb_s = peak_io_mb_s
        self.peak_iops = peak_iops
    
    def to_dict(self) -> dict:
        return {
            "testKey": self.test_key,
            "heavyClass": self.heavy_class,
            "dominantDim": self.dominant_dim,
            "cpuRatio": round(self.cpu_ratio, 4),
            "memRatio": round(self.mem_ratio, 4),
            "ioRatio": round(self.io_ratio, 4),
            "iopsRatio": round(self.iops_ratio, 4),
            "predictedDurationMs": self.predicted_duration_ms,
            "avgCpuPct": round(self.avg_cpu_pct, 2),
            "avgMemMb": round(self.avg_mem_mb, 2),
            "avgIoMbPerSec": round(self.avg_io_mb_s, 2),
            "avgIops": round(self.avg_iops, 2),
            "effectiveCpuPct": round(self.eff_cpu_pct, 2),
            "effectiveMemMb": round(self.eff_mem_mb, 2),
            "effectiveIoMbPerSec": round(self.eff_io_mb_s, 2),
            "effectiveIops": round(self.eff_iops, 2),
            "peakCpuPct": round(self.peak_cpu_pct, 2),
            "peakMemMb": round(self.peak_mem_mb, 2),
            "peakIoMbPerSec": round(self.peak_io_mb_s, 2),
            "peakIops": round(self.peak_iops, 2)
        }


def load_json(filepath: str) -> dict:
    """Load JSON file, handling gzip compression."""
    path = filepath
    # If caller passed latest.json but a .gz exists beside it, prefer the fresher .gz
    if not filepath.endswith('.gz') and os.path.exists(filepath + '.gz'):
        path = filepath + '.gz'
    if path.endswith('.gz'):
        with gzip.open(path, 'rt', encoding='utf-8') as f:
            return json.load(f)
    else:
        with open(path, 'r', encoding='utf-8') as f:
            return json.load(f)


def parse_test_stats(data: dict) -> List[TestStats]:
    """Parse test statistics from various JSON formats."""
    stats = []
    
    if isinstance(data, dict):
        # Format: { "tests": { "testKey": {...}, ... } }
        if 'tests' in data and isinstance(data['tests'], dict):
            for test_key, test_data in data['tests'].items():
                stats.append(TestStats(test_key, test_data))
        # Format: { "testKey": {...}, ... } (direct mapping)
        else:
            for key, value in data.items():
                if isinstance(value, dict) and any(k in value for k in ['duration_ms', 'cpu_pct', 'observation_count', 'counts']):
                    stats.append(TestStats(key, value))
    
    elif isinstance(data, list):
        # Format: [ { "testKey": "...", ... }, ... ]
        for i, item in enumerate(data):
            if isinstance(item, dict):
                test_key = item.get('testKey', item.get('test_key', f'unknown_{i}'))
                stats.append(TestStats(test_key, item))
    
    return stats


def blend_with_peak(avg: float, peak: float, weight: float) -> float:
    """Blend average with peak using a configurable weight."""
    return avg + weight * max(0.0, peak - avg)


def compute_global_stats(all_stats: List[TestStats], peak_weights: dict, min_obs: int = MIN_OBSERVATIONS) -> GlobalStats:
    """Compute global effective means from test statistics."""
    if not all_stats:
        print("Warning: No test stats provided, using defaults", file=sys.stderr)
        return GlobalStats(50.0, 512.0, 10.0, 200.0, 0)
    
    # Filter tests with sufficient observations
    valid_stats = [s for s in all_stats if s.observation_count >= min_obs]
    
    if not valid_stats:
        print(f"Warning: No tests with >= {min_obs} observations, using all tests", file=sys.stderr)
        valid_stats = all_stats
    
    sum_cpu = sum(blend_with_peak(s.cpu_pct_avg, s.cpu_pct_peak, peak_weights["cpu"]) for s in valid_stats)
    sum_mem = sum(blend_with_peak(s.mem_mb_avg, s.mem_mb_peak, peak_weights["mem"]) for s in valid_stats)
    sum_io = sum(blend_with_peak(s.io_mb_s_avg, s.io_mb_s_peak, peak_weights["io"]) for s in valid_stats)
    sum_iops = sum(blend_with_peak(s.iops_avg, s.iops_peak, peak_weights["iops"]) for s in valid_stats)
    count = len(valid_stats)
    
    return GlobalStats(
        sum_cpu / count,
        sum_mem / count,
        sum_io / count,
        sum_iops / count,
        count
    )


def classify_ratio(ratio: float) -> str:
    """Classify a ratio into NORMAL, HEAVY, or EXTREME."""
    if ratio >= EXTREME_THRESHOLD:
        return "EXTREME"
    elif ratio >= HEAVY_THRESHOLD:
        return "HEAVY"
    else:
        return "NORMAL"


def get_dominant_dimension(cpu_r: float, mem_r: float, io_r: float, iops_r: float) -> Tuple[str, float]:
    """Determine the dominant dimension based on max ratio."""
    ratios = [
        ("CPU", cpu_r),
        ("MEM", mem_r),
        ("IO", io_r),
        ("IOPS", iops_r)
    ]
    max_dim, max_ratio = max(ratios, key=lambda x: x[1])
    return max_dim if max_ratio >= HEAVY_THRESHOLD else "NONE", max_ratio


def compute_profile(stats: TestStats, global_stats: GlobalStats, peak_weights: dict) -> TestProfile:
    """Compute a test profile from stats."""
    EPSILON = 0.001
    
    eff_cpu = blend_with_peak(stats.cpu_pct_avg, stats.cpu_pct_peak, peak_weights["cpu"])
    eff_mem = blend_with_peak(stats.mem_mb_avg, stats.mem_mb_peak, peak_weights["mem"])
    eff_io = blend_with_peak(stats.io_mb_s_avg, stats.io_mb_s_peak, peak_weights["io"])
    eff_iops = blend_with_peak(stats.iops_avg, stats.iops_peak, peak_weights["iops"])

    cpu_ratio = eff_cpu / max(EPSILON, global_stats.mean_cpu)
    mem_ratio = eff_mem / max(EPSILON, global_stats.mean_mem)
    io_ratio = eff_io / max(EPSILON, global_stats.mean_io)
    iops_ratio = eff_iops / max(EPSILON, global_stats.mean_iops)
    
    dominant_dim, max_ratio = get_dominant_dimension(cpu_ratio, mem_ratio, io_ratio, iops_ratio)
    heavy_class = classify_ratio(max_ratio)
    
    return TestProfile(
        test_key=stats.test_key,
        heavy_class=heavy_class,
        dominant_dim=dominant_dim,
        cpu_ratio=cpu_ratio,
        mem_ratio=mem_ratio,
        io_ratio=io_ratio,
        iops_ratio=iops_ratio,
        predicted_duration_ms=int(stats.duration_ewma_ms),
        avg_cpu_pct=stats.cpu_pct_avg,
        avg_mem_mb=stats.mem_mb_avg,
        avg_io_mb_s=stats.io_mb_s_avg,
        avg_iops=stats.iops_avg,
        eff_cpu_pct=eff_cpu,
        eff_mem_mb=eff_mem,
        eff_io_mb_s=eff_io,
        eff_iops=eff_iops,
        peak_cpu_pct=stats.cpu_pct_peak,
        peak_mem_mb=stats.mem_mb_peak,
        peak_io_mb_s=stats.io_mb_s_peak,
        peak_iops=stats.iops_peak
    )


def generate_profiles(input_path: str, output_path: str, verbose: bool = True,
                      heavy_threshold: float = HEAVY_THRESHOLD,
                      extreme_threshold: float = EXTREME_THRESHOLD,
                      min_observations: int = MIN_OBSERVATIONS,
                      peak_weights: dict = DEFAULT_PEAK_WEIGHTS):
    """Generate test profiles from statistics file."""
    
    # Use function-local thresholds
    local_heavy = heavy_threshold
    local_extreme = extreme_threshold
    local_min_obs = min_observations
    
    # Load and parse stats
    if verbose:
        print(f"Loading test stats from: {input_path}")
    
    data = load_json(input_path)
    all_stats = parse_test_stats(data)
    
    if verbose:
        print(f"Parsed {len(all_stats)} test statistics")
    
    # Compute global stats
    global_stats = compute_global_stats(all_stats, peak_weights, local_min_obs)
    
    if verbose:
        print(f"\nGlobal statistics (from {global_stats.test_count} tests with >= {local_min_obs} observations):")
        print(f"  Mean CPU:  {global_stats.mean_cpu:.2f}%")
        print(f"  Mean MEM:  {global_stats.mean_mem:.2f} MB")
        print(f"  Mean I/O:  {global_stats.mean_io:.2f} MB/s")
        print(f"  Mean IOPS: {global_stats.mean_iops:.2f}")
    
    # Compute profiles
    profiles = [compute_profile(stats, global_stats, peak_weights) for stats in all_stats]
    
    # Count classifications
    counts = defaultdict(int)
    dim_counts = defaultdict(int)
    for p in profiles:
        counts[p.heavy_class] += 1
        if p.heavy_class != "NORMAL":
            dim_counts[p.dominant_dim] += 1
    
    if verbose:
        print(f"\nClassification summary:")
        print(f"  NORMAL:  {counts['NORMAL']}")
        print(f"  HEAVY:   {counts['HEAVY']}")
        print(f"  EXTREME: {counts['EXTREME']}")
        print(f"\nDominant dimensions (for non-NORMAL tests):")
        for dim in ["CPU", "MEM", "IO", "IOPS"]:
            print(f"  {dim}: {dim_counts[dim]}")
    
    # Build output JSON
    output = {
        "version": 1,
        "generated": datetime.now().isoformat(),
        "source": input_path,
        "testCount": len(profiles),
        "globalStats": {
            "meanCpuPct": round(global_stats.mean_cpu, 2),
            "meanMemMb": round(global_stats.mean_mem, 2),
            "meanIoMbPerSec": round(global_stats.mean_io, 2),
            "meanIops": round(global_stats.mean_iops, 2),
            "validTestCount": global_stats.test_count
        },
        "thresholds": {
            "heavyRatio": local_heavy,
            "extremeRatio": local_extreme,
            "minObservations": local_min_obs,
            "peakWeights": peak_weights
        },
        "summary": {
            "normal": counts["NORMAL"],
            "heavy": counts["HEAVY"],
            "extreme": counts["EXTREME"]
        },
        "profiles": [p.to_dict() for p in profiles]
    }
    
    # Write output
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(output, f, indent=2)
    
    if verbose:
        print(f"\nWrote {len(profiles)} profiles to: {output_path}")
    
    # Print top EXTREME tests
    extreme_tests = [p for p in profiles if p.heavy_class == "EXTREME"]
    if extreme_tests and verbose:
        print(f"\nTop EXTREME tests (max ratio >= {local_extreme}):")
        extreme_tests.sort(key=lambda p: max(p.cpu_ratio, p.mem_ratio, p.io_ratio, p.iops_ratio), reverse=True)
        for p in extreme_tests[:10]:
            max_ratio = max(p.cpu_ratio, p.mem_ratio, p.io_ratio, p.iops_ratio)
            print(f"  {p.test_key}: {p.dominant_dim}={max_ratio:.2f}x")


def main():
    parser = argparse.ArgumentParser(
        description="Generate test_profiles.json from test statistics",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument(
        '--input', '-i',
        default='latest.json',
        help='Input file path (latest.json or latest.json.gz)'
    )
    parser.add_argument(
        '--output', '-o',
        default='test_profiles.json',
        help='Output file path'
    )
    parser.add_argument(
        '--quiet', '-q',
        action='store_true',
        help='Suppress verbose output'
    )
    parser.add_argument(
        '--heavy-threshold',
        type=float,
        default=HEAVY_THRESHOLD,
        help=f'Ratio threshold for HEAVY classification (default: {HEAVY_THRESHOLD})'
    )
    parser.add_argument(
        '--extreme-threshold',
        type=float,
        default=EXTREME_THRESHOLD,
        help=f'Ratio threshold for EXTREME classification (default: {EXTREME_THRESHOLD})'
    )
    parser.add_argument(
        '--min-observations',
        type=int,
        default=MIN_OBSERVATIONS,
        help=f'Minimum observations for reliable classification (default: {MIN_OBSERVATIONS})'
    )
    parser.add_argument(
        '--peak-weight-cpu',
        type=float,
        default=DEFAULT_PEAK_WEIGHTS["cpu"],
        help=f'Peak blend weight for CPU (default: {DEFAULT_PEAK_WEIGHTS["cpu"]})'
    )
    parser.add_argument(
        '--peak-weight-mem',
        type=float,
        default=DEFAULT_PEAK_WEIGHTS["mem"],
        help=f'Peak blend weight for memory (default: {DEFAULT_PEAK_WEIGHTS["mem"]})'
    )
    parser.add_argument(
        '--peak-weight-io',
        type=float,
        default=DEFAULT_PEAK_WEIGHTS["io"],
        help=f'Peak blend weight for IO MB/s (default: {DEFAULT_PEAK_WEIGHTS["io"]})'
    )
    parser.add_argument(
        '--peak-weight-iops',
        type=float,
        default=DEFAULT_PEAK_WEIGHTS["iops"],
        help=f'Peak blend weight for IOPS (default: {DEFAULT_PEAK_WEIGHTS["iops"]})'
    )
    
    args = parser.parse_args()
    
    try:
        generate_profiles(
            args.input, 
            args.output, 
            verbose=not args.quiet,
            heavy_threshold=args.heavy_threshold,
            extreme_threshold=args.extreme_threshold,
            min_observations=args.min_observations,
            peak_weights={
                "cpu": max(0.0, args.peak_weight_cpu),
                "mem": max(0.0, args.peak_weight_mem),
                "io": max(0.0, args.peak_weight_io),
                "iops": max(0.0, args.peak_weight_iops),
            }
        )
    except FileNotFoundError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"Error parsing JSON: {e}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
