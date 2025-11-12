# Smart Scheduling Configuration Reference

## Overview

This document provides a comprehensive reference for all configuration options related to the Smart Scheduling system. It covers both builder and tester configuration, including default values, valid ranges, tuning guidelines, and example configurations for common scenarios.

## Table of Contents

1. [Builder Configuration](#builder-configuration)
2. [Tester Configuration](#tester-configuration)
3. [Configuration Tuning Guide](#configuration-tuning-guide)
4. [Common Configuration Scenarios](#common-configuration-scenarios)
5. [Troubleshooting Configuration Issues](#troubleshooting-configuration-issues)
6. [Migration from Legacy Configuration](#migration-from-legacy-configuration)

---

## Builder Configuration

Configuration file: `conf/builder.conf`

### Smart Scheduling Enable/Disable

#### `smart_scheduling_enabled`

**Type:** Boolean (true/false)
**Default:** `false` (opt-in)
**Since:** Phase 5

**Description:**
Master switch for smart scheduling. When `true`, replaces legacy round-robin distribution with intelligent multi-resource-aware scheduling. When `false`, uses legacy work-queue distribution.

**Example:**
```properties
smart_scheduling_enabled=true
```

**Notes:**
- Safe to toggle at any time (takes effect on next build job)
- No tester restart required when changing
- Legacy mode available as instant rollback

---

### Scheduling Algorithm Parameters

#### `scheduling_mice_threshold_ms`

**Type:** Long (milliseconds)
**Default:** `20000` (20 seconds)
**Range:** `1000` to `300000` (1s to 5min)
**Since:** Phase 5

**Description:**
Duration threshold separating "mice" (short tests) from "elephants" (long tests). Tests with predicted duration ≤ threshold are treated as mice and scheduled with Shortest-Job-First strategy. Tests above threshold are treated as elephants and scheduled with best-fit bin-packing.

**Example:**
```properties
# Aggressive SJF (treat more tests as mice)
scheduling_mice_threshold_ms=30000

# Conservative (fewer mice, more elephants)
scheduling_mice_threshold_ms=10000
```

**Tuning Guidelines:**
- **Increase** if you have many medium-duration tests (20-40s) that benefit from SJF
- **Decrease** if you have bimodal distribution (very short vs very long, few medium)
- Analyze P50/P90 of your test suite to find natural cutoff
- Monitor queue lengths: if mice queue grows unbounded, threshold may be too high

**Effect on Performance:**
- Higher threshold → More tests get SJF benefit → Lower mean completion time
- Lower threshold → More tests scored globally → Better bin-packing for diverse workloads

---

### Scoring Weights

All weights should sum to approximately 1.0 for balanced scoring. Individual weights can exceed 1.0 if you want to heavily favor one dimension, but keep total reasonable (<2.0).

#### I/O-First Scheduling Weights (November 2025)

**Since:** November 2025

The scoring function now uses per-dimension weights to make I/O the dominant scheduling constraint. These weights are applied to the pressure calculation for each resource dimension.

#### `scheduling_weight_io`

**Type:** Double
**Default:** `2.50`
**Range:** `0.5` to `10.0` recommended

**Description:**
Weight multiplier for I/O pressure (read/write bandwidth). This is the heaviest weight by default, making I/O the primary scheduling constraint. The I/O pressure is computed as `max(readPressure, writePressure)` to handle asymmetric workloads.

**Example:**
```properties
# Very I/O-dominant (prioritize I/O capacity above all)
scheduling_weight_io=5.00

# Balanced I/O emphasis (default)
scheduling_weight_io=2.50

# Less I/O emphasis (more balanced with CPU/memory)
scheduling_weight_io=1.50
```

**Tuning Guidelines:**
- **Increase** if I/O is your primary bottleneck (storage-bound workloads)
- **Decrease** if CPU or memory are more constrained
- Default 2.50 makes I/O 2.5× more important than CPU in scoring

#### `scheduling_weight_cpu`

**Type:** Double
**Default:** `1.00`
**Range:** `0.5` to `5.0` recommended

**Description:**
Weight multiplier for CPU pressure. Used as the baseline (1.00) for other weights.

**Example:**
```properties
# CPU-dominant (rare, usually I/O is bottleneck)
scheduling_weight_cpu=2.00
scheduling_weight_io=1.00

# Default (I/O-dominant)
scheduling_weight_cpu=1.00
scheduling_weight_io=2.50
```

#### `scheduling_weight_mem`

**Type:** Double
**Default:** `1.10`
**Range:** `0.5` to `5.0` recommended

**Description:**
Weight multiplier for memory pressure. Slightly higher than CPU (1.10) to account for memory spikes.

**Example:**
```properties
# Memory-dominant (memory-constrained workloads)
scheduling_weight_mem=2.00

# Default
scheduling_weight_mem=1.10
```

#### `scheduling_weight_net`

**Type:** Double
**Default:** `0.80`
**Range:** `0.1` to `3.0` recommended

**Description:**
Weight multiplier for network bandwidth pressure. Lower than CPU/memory since network is typically less of a bottleneck.

**Example:**
```properties
# Network-dominant (network-constrained workloads)
scheduling_weight_net=2.00

# Default (network less important)
scheduling_weight_net=0.80
```

**Note:** IOPS pressure uses the same weight as I/O (`scheduling_weight_io`) since IOPS are I/O-related.

#### `scheduling_weight_pressure`

**Type:** Double (0.0 to 1.0 recommended)
**Default:** `0.45`
**Since:** Phase 5

**Description:**
Weight for multi-resource pressure (bin-packing efficiency). Pressure is the dominant resource fraction (max of CPU%, memory%, I/O%, IOPS%, network% demand relative to free capacity). Higher weight favors tighter packing and higher utilization.

**Example:**
```properties
# Aggressive bin-packing (maximize utilization)
scheduling_weight_pressure=0.60

# Relaxed packing (leave more headroom)
scheduling_weight_pressure=0.30
```

**Tuning Guidelines:**
- **Increase** to drive utilization up (pack nodes tighter)
- **Decrease** if you see resource contention or performance degradation
- Balance with duration weight to avoid starving long-running tests

---

#### `scheduling_weight_duration`

**Type:** Double (0.0 to 1.0 recommended)
**Default:** `0.25`
**Since:** Phase 5

**Description:**
Weight for predicted test duration. Higher weight favors scheduling shorter tests first (even within elephants), reducing mean completion time but potentially delaying long tests.

**Example:**
```properties
# Strong preference for short tests
scheduling_weight_duration=0.35

# Less duration bias (more fairness)
scheduling_weight_duration=0.15
```

**Tuning Guidelines:**
- **Increase** if feedback loops matter (short tests complete fast, unblock developers)
- **Decrease** if fairness matters more (avoid starving long tests)
- Mice already get SJF; this affects elephants queue

---

#### `scheduling_weight_image`

**Type:** Double (0.0 to 1.0 recommended)
**Default:** `0.15`
**Since:** Phase 5

**Description:**
Weight for Docker image cache locality. Tests that require an image already cached on a node get penalty=0; tests requiring a cold image pull get penalty=1. Higher weight strongly favors cache hits, reducing cold-start overhead (10-30s per test).

**Example:**
```properties
# Strong cache locality preference
scheduling_weight_image=0.25

# Less cache sensitivity (more willing to cold-start)
scheduling_weight_image=0.05
```

**Tuning Guidelines:**
- **Increase** if Docker image pulls are expensive (slow network, large images)
- **Decrease** if cache hit rate is already high (diminishing returns)
- Monitor `docker_image_cached` ratio in TestObservations
- Effective only with Optimized Docker executor enabled

**Effect on Performance:**
- Higher weight → More cache hits → 10-30s faster per test on average
- Risk: May create load imbalance if some nodes have "hot" images

---

#### `scheduling_weight_package`

**Type:** Double (0.0 to 1.0 recommended)
**Default:** `0.05`
**Since:** Phase 5

**Description:**
Weight for build package cache locality. Similar to image weight, but for cached build artifacts (cubrid_<commit>.tar.gz files). Package extraction overhead is smaller than image pulls (~5-10s), so weight is lower by default.

**Example:**
```properties
# Strong package locality preference
scheduling_weight_package=0.10

# Ignore package locality (extraction cheap)
scheduling_weight_package=0.00
```

**Tuning Guidelines:**
- **Increase** if package extraction is slow (large packages, slow disks)
- **Decrease** if build cache size is large enough that most commits are cached everywhere
- Less impactful than image weight in practice

---

#### `scheduling_weight_age`

**Type:** Double (0.0 to 1.0 recommended)
**Default:** `0.10`
**Since:** Phase 5

**Description:**
Weight for queue aging (fairness). Tests waiting longer in the queue receive a boost that effectively reduces their score, increasing priority. This prevents starvation of low-priority or unlucky tests.

**Example:**
```properties
# Strong fairness guarantee (FIFO-like)
scheduling_weight_age=0.20

# Weak fairness (more SJF/bin-packing optimization)
scheduling_weight_age=0.05
```

**Tuning Guidelines:**
- **Increase** if you observe starvation (some tests never scheduled)
- **Decrease** if you want more aggressive SJF/bin-packing (accept some unfairness)
- Monitor wait times: if P95 wait > 5 minutes, increase weight

**Age Boost Formula:**
```
age_boost = min(1.0, wait_seconds / 200)
```
Caps at 1.0 after ~3 minutes of waiting.

**Effect on Performance:**
- Higher weight → Fairer (FIFO-like) → Less SJF benefit
- Lower weight → More optimization → Risk of starvation

---

### Node Polling and Staleness

#### `scheduling_poll_interval_ms`

**Type:** Long (milliseconds)
**Default:** `5000` (5 seconds)
**Range:** `1000` to `60000` (1s to 1min)
**Since:** Phase 5

**Description:**
How often the builder polls each tester's `/health` endpoint to update cluster state (capacity, utilization, cached images/packages, running test count).

**Example:**
```properties
# Fast polling (more responsive, higher network overhead)
scheduling_poll_interval_ms=3000

# Slow polling (less overhead, more staleness)
scheduling_poll_interval_ms=10000
```

**Tuning Guidelines:**
- **Decrease** if cluster state changes rapidly (short tests, many nodes)
- **Increase** if network overhead is a concern or tests are long-running
- Must be < `scheduling_stale_threshold_ms`
- Network cost: ~1 KB/s per node at 5s interval (negligible)

**Effect on Performance:**
- Faster polling → More up-to-date decisions → Better packing
- Slower polling → Scheduler may make decisions on stale data

---

#### `scheduling_stale_threshold_ms`

**Type:** Long (milliseconds)
**Default:** `30000` (30 seconds)
**Range:** `10000` to `300000` (10s to 5min)
**Since:** Phase 5

**Description:**
Maximum age of a node's health snapshot before it's marked as stale and excluded from scheduling decisions. Prevents assigning tests to potentially offline/unhealthy nodes.

**Example:**
```properties
# Aggressive staleness detection (tight SLA)
scheduling_stale_threshold_ms=15000

# Relaxed staleness (tolerate transient network issues)
scheduling_stale_threshold_ms=60000
```

**Tuning Guidelines:**
- **Decrease** if you want fast failure detection (node crashes, network partitions)
- **Increase** if you have flaky networks (transient connectivity issues)
- Should be 3-6× `scheduling_poll_interval_ms`
- Monitor "stale node" warnings in logs

**Effect on Performance:**
- Shorter threshold → Faster failure detection → Less risk of bad assignments
- Longer threshold → More tolerance for transient issues → Risk of stale assignments

---

### Resource Headroom Safety Margins (v2)

**Since:** v2 Production Hardening (November 2025)

These configuration options control the dimension-specific and confidence-aware safety margins used when checking if a node has sufficient resources for a test. They prevent oversubscription by requiring extra headroom beyond the predicted demand.

#### `scheduling_margin_cpu_base`

**Type:** Double (0.0 to 1.0)
**Default:** `0.10` (10% base margin)

**Description:**
Base safety margin for CPU headroom checks. A value of 0.10 means tests require 10% more CPU than predicted to be scheduled.

**Example:**
```properties
# Conservative CPU margins (less packing, more safety)
scheduling_margin_cpu_base=0.15

# Aggressive CPU margins (tighter packing)
scheduling_margin_cpu_base=0.05
```

#### `scheduling_margin_mem_base`

**Type:** Double (0.0 to 1.0)
**Default:** `0.20` (20% base margin)

**Description:**
Base safety margin for memory headroom checks. Memory gets a higher default margin than CPU because it spikes unpredictably. Additionally, a +100MB absolute floor is always added.

**Example:**
```properties
# High memory safety for production
scheduling_margin_mem_base=0.30

# Tight memory packing (risky)
scheduling_margin_mem_base=0.10
```

#### `scheduling_margin_io_read_base`

**Type:** Double (0.0 to 1.0)
**Default:** `0.35` (35% base margin)

**Description:**
Base safety margin for **read** I/O bandwidth headroom checks. Read I/O gets a high default margin due to high variability and burstiness (e.g., index scans, log replays).

**Example:**
```properties
# Conservative read margins (less packing, more safety)
scheduling_margin_io_read_base=0.45

# Aggressive read margins (tighter packing)
scheduling_margin_io_read_base=0.25
```

#### `scheduling_margin_io_write_base`

**Type:** Double (0.0 to 1.0)
**Default:** `0.35` (35% base margin)

**Description:**
Base safety margin for **write** I/O bandwidth headroom checks. Write I/O gets a high default margin due to high variability and burstiness (e.g., DB create/drop, journaling, data file writes).

**Example:**
```properties
# Conservative write margins (less packing, more safety)
scheduling_margin_io_write_base=0.45

# Aggressive write margins (tighter packing)
scheduling_margin_io_write_base=0.25
```

**Note:** The legacy `scheduling_margin_io_base` option is deprecated. Use `scheduling_margin_io_read_base` and `scheduling_margin_io_write_base` instead for granular control.

#### `scheduling_margin_net_base`

**Type:** Double (0.0 to 1.0)
**Default:** `0.25` (25% base margin)

**Description:**
Base safety margin for network bandwidth headroom checks.

#### `scheduling_margin_iops_base`

**Type:** Double (0.0 to 1.0)
**Default:** `0.25` (25% base margin)

**Description:**
Base safety margin for IOPS headroom checks.

#### `scheduling_margin_confidence_factor`

**Type:** Double (0.0 to 1.0)
**Default:** `0.50` (50% extra for low confidence)

**Description:**
Scales margin increase for low-confidence predictions. The final margin for a dimension is:

```
margin = base_margin + confidence_factor × (1 - confidence)
```

**Example:**
For memory with confidence=0.3:
```
margin = 0.20 + 0.50 × (1 - 0.3) = 0.20 + 0.35 = 55%
```

**Tuning Guidelines:**
- **High confidence_factor** (0.6-0.8): Very conservative for unknown workloads
- **Medium confidence_factor** (0.4-0.6): Balanced safety vs efficiency (default)
- **Low confidence_factor** (0.2-0.4): Trust predictions more, risk oversubscription

**Example Configuration:**
```properties
# Production-safe margins (recommended) - I/O-first
scheduling_margin_cpu_base=0.10
scheduling_margin_mem_base=0.20
scheduling_margin_io_read_base=0.35
scheduling_margin_io_write_base=0.35
scheduling_margin_net_base=0.25
scheduling_margin_iops_base=0.25
scheduling_margin_confidence_factor=0.50

# Aggressive packing (use with caution)
scheduling_margin_cpu_base=0.05
scheduling_margin_mem_base=0.15
scheduling_margin_io_read_base=0.25
scheduling_margin_io_write_base=0.25
scheduling_margin_net_base=0.15
scheduling_margin_iops_base=0.15
scheduling_margin_confidence_factor=0.30
```

#### `io_safety_headroom_ratio`

**Type:** Double (0.0 to 0.5)
**Default:** `0.15` (15%)
**Since:** November 2025

**Description:**
Global safety headroom ratio for I/O capacity. This fraction of total I/O capacity (read and write separately) is kept free at all times to prevent disk saturation. This is in addition to the dimension-specific margins.

**Example:**
```properties
# Conservative (keep 20% free)
io_safety_headroom_ratio=0.20

# Default (keep 15% free)
io_safety_headroom_ratio=0.15

# Aggressive (keep 10% free)
io_safety_headroom_ratio=0.10
```

**Tuning Guidelines:**
- **Increase** if you experience disk saturation or I/O contention
- **Decrease** if you want to maximize utilization (risky)
- Applied separately to read and write capacity
- Example: With 100 MB/s read capacity and 15% headroom, only 85 MB/s is available for scheduling

**Effect on Admission:**
A test requiring 50 MB/s read on a node with 100 MB/s capacity and 15% headroom:
- Available capacity: 100 - (100 × 0.15) = 85 MB/s
- Test must pass margin check: `requiredRead × (1 + margin) ≤ 85 MB/s`

---

## Tester Configuration

Configuration file: `conf/tester.conf`

### Docker Runtime Limits (v2)

#### `docker_enforce_memory_limits`

**Type:** Boolean (true/false)
**Default:** `false`
**Since:** v2 Production Hardening (November 2025)

**Description:**
Controls whether Docker memory limits are enforced based on predicted demand. When enabled, containers are started with `--memory` and `--memory-swap` flags matching the predicted memory demand (minimum 256MB). CPU limits are always enforced regardless of this setting.

**Example:**
```properties
# Disable memory limits (default - prioritizes test success)
docker_enforce_memory_limits=false

# Enable memory limits (enforces predicted demand)
docker_enforce_memory_limits=true
```

**Notes:**
- **Default is `false`** to ensure passing tests continue to pass without OOM failures
- When disabled, containers run with unlimited memory while CPU limits still apply
- Enable only if resource misallocation becomes a problem
- CPU limits are always enforced (minimum 0.1 CPUs for bootstrapping)

**Verification:**
```bash
# Check logs for memory limit status
tail -50 bin/tester_output.log | grep "Docker limits"
# With limits disabled: "memory=unlimited (docker_enforce_memory_limits=false)"
# With limits enabled: "memory=512MB"

# Verify container memory limits
docker inspect <container_id> --format '{{.HostConfig.Memory}}'
# 0 = unlimited, >0 = bytes limit
```

---

### Metrics Collection and Statistics

#### `stats_enabled`

**Type:** Boolean (true/false)
**Default:** `true`
**Since:** Phase 2

**Description:**
Master switch for per-test metrics collection and statistics storage. When enabled, the tester collects resource usage metrics (CPU, memory, I/O, network) for each test execution and maintains aggregated statistics in TestStatsStore.

**Example:**
```properties
stats_enabled=true
```

**Notes:**
- Required for smart scheduling to function (predictions need historical data)
- Can be disabled for testers not participating in smart scheduling
- Minimal overhead (~1-2 MB memory, ~50 KB/hour disk for 3K tests)

---

#### `stats_snapshot_interval_seconds`

**Type:** Integer (seconds)
**Default:** `300` (5 minutes)
**Range:** `60` to `3600` (1min to 1hr)
**Since:** Phase 2

**Description:**
How often the tester writes a compacted snapshot of test statistics to disk. Snapshots reduce WAL replay time on restart and prevent unbounded WAL growth.

**Example:**
```properties
# Frequent snapshots (faster restart, more disk I/O)
stats_snapshot_interval_seconds=120

# Infrequent snapshots (less overhead, slower restart)
stats_snapshot_interval_seconds=600
```

**Tuning Guidelines:**
- **Decrease** if testers restart frequently (fast recovery matters)
- **Increase** if disk I/O is a concern (reduce snapshot writes)
- Balance between recovery time and I/O overhead
- Snapshot write is atomic (no data loss risk)

**Storage Impact:**
- Snapshot size: ~1.5 MB for 3,000 tests (gzip-compressed)
- Write time: ~100-200ms
- Frequency: Default every 5min = ~12 writes/hour

---

#### `heartbeat_interval_seconds`

**Type:** Integer (seconds)
**Default:** `5`
**Range:** `1` to `60` (1s to 1min)
**Since:** Phase 3

**Description:**
Informational parameter indicating how often the builder is expected to poll the `/health` endpoint. Testers use this to adjust internal cache expiration for reported values.

**Example:**
```properties
# Fast heartbeat (more responsive scheduling)
heartbeat_interval_seconds=3

# Slow heartbeat (less overhead)
heartbeat_interval_seconds=10
```

**Notes:**
- Should match `scheduling_poll_interval_ms` on builder (converted to seconds)
- Tester doesn't actively send heartbeats (pull model, builder polls)
- Affects internal cache TTLs for Docker image lists, package lists

---

#### `score_endpoint_enabled`

**Type:** Boolean (true/false)
**Default:** `true`
**Since:** Phase 3

**Description:**
Enable the `/score` endpoint for batch resource demand predictions. Builder can POST a list of tests and receive predictions for each test based on this node's historical data.

**Example:**
```properties
score_endpoint_enabled=true
```

**Notes:**
- Required for accurate per-node predictions
- If disabled, builder uses bootstrap defaults for unseen tests
- Minimal overhead (stateless queries to in-memory TestStatsStore)

---

## Configuration Tuning Guide

### Goal-Based Tuning

#### Maximize Throughput (Tests/Hour)

**Objective:** Pack nodes tightly, minimize idle time.

**Configuration:**
```properties
# Builder
smart_scheduling_enabled=true
scheduling_mice_threshold_ms=25000              # Treat more tests as mice
scheduling_weight_pressure=0.55                 # Strong bin-packing
scheduling_weight_duration=0.20                 # Less duration bias
scheduling_weight_image=0.15                    # Cache locality still important
scheduling_weight_package=0.05                  # Moderate package locality
scheduling_weight_age=0.05                      # Weak fairness (accept some starvation)
scheduling_poll_interval_ms=3000                # Fast polling
scheduling_stale_threshold_ms=15000             # Aggressive staleness

# Tester
stats_enabled=true
stats_snapshot_interval_seconds=300
heartbeat_interval_seconds=3
score_endpoint_enabled=true
```

**Rationale:**
- High pressure weight drives utilization up
- Larger mice threshold gives more tests SJF benefit
- Low age weight allows aggressive optimization
- Fast polling keeps decisions fresh

**Tradeoffs:**
- Risk of starving some long tests
- Higher network overhead from fast polling
- May create load imbalance if cache locality dominates

---

#### Minimize Mean Completion Time (Fast Feedback)

**Objective:** Get short tests done ASAP, prioritize developer feedback loops.

**Configuration:**
```properties
# Builder
smart_scheduling_enabled=true
scheduling_mice_threshold_ms=30000              # Aggressive mice classification
scheduling_weight_pressure=0.35                 # Moderate bin-packing
scheduling_weight_duration=0.35                 # Strong duration preference
scheduling_weight_image=0.15                    # Cache locality
scheduling_weight_package=0.05                  # Moderate package locality
scheduling_weight_age=0.10                      # Moderate fairness
scheduling_poll_interval_ms=5000                # Balanced polling
scheduling_stale_threshold_ms=30000             # Balanced staleness

# Tester
stats_enabled=true
stats_snapshot_interval_seconds=300
heartbeat_interval_seconds=5
score_endpoint_enabled=true
```

**Rationale:**
- Large mice threshold + high duration weight = strong SJF effect
- Lower pressure weight avoids over-packing (leaves headroom for short tests)
- Balanced age weight prevents excessive starvation

**Tradeoffs:**
- Lower utilization (less tight packing)
- Long tests may wait longer

---

#### Maximize Fairness (No Starvation)

**Objective:** Ensure all tests complete in bounded time, FIFO-like behavior.

**Configuration:**
```properties
# Builder
smart_scheduling_enabled=true
scheduling_mice_threshold_ms=20000              # Balanced threshold
scheduling_weight_pressure=0.40                 # Moderate bin-packing
scheduling_weight_duration=0.15                 # Weak duration bias
scheduling_weight_image=0.15                    # Cache locality
scheduling_weight_package=0.05                  # Moderate package locality
scheduling_weight_age=0.25                      # Strong fairness guarantee
scheduling_poll_interval_ms=5000                # Balanced polling
scheduling_stale_threshold_ms=30000             # Balanced staleness

# Tester
stats_enabled=true
stats_snapshot_interval_seconds=300
heartbeat_interval_seconds=5
score_endpoint_enabled=true
```

**Rationale:**
- High age weight ensures long-waiting tests eventually get priority
- Lower duration weight reduces SJF bias
- Moderate pressure weight balances utilization and fairness

**Tradeoffs:**
- Lower throughput (less aggressive packing)
- Higher mean completion time (less SJF benefit)

---

#### Maximize Cache Hit Rate (Cold-Start Avoidance)

**Objective:** Minimize Docker image pulls and package extractions.

**Configuration:**
```properties
# Builder
smart_scheduling_enabled=true
scheduling_mice_threshold_ms=20000              # Balanced threshold
scheduling_weight_pressure=0.30                 # Lower bin-packing (accept underutilization)
scheduling_weight_duration=0.20                 # Moderate duration bias
scheduling_weight_image=0.30                    # Very strong image locality
scheduling_weight_package=0.10                  # Strong package locality
scheduling_weight_age=0.10                      # Moderate fairness
scheduling_poll_interval_ms=5000                # Balanced polling
scheduling_stale_threshold_ms=30000             # Balanced staleness

# Tester
stats_enabled=true
stats_snapshot_interval_seconds=300
heartbeat_interval_seconds=5
score_endpoint_enabled=true
optimized_docker_enabled=true                   # Required for image caching
build_cache_size=20                             # Larger cache
```

**Rationale:**
- Very high image/package weights strongly favor cache hits
- Lower pressure weight accepts some underutilization to keep tests on "warm" nodes
- Larger build cache keeps more images/packages available

**Tradeoffs:**
- Lower utilization (may leave nodes idle if no cached images match)
- Risk of load imbalance (all tests for commit X go to node Y)

---

### Workload-Specific Tuning

#### Short Tests (<10s mean)

**Characteristics:** Many small tests, high throughput workload.

**Recommended Config:**
```properties
scheduling_mice_threshold_ms=15000              # Lower threshold (most tests are mice)
scheduling_weight_pressure=0.50                 # High utilization
scheduling_weight_duration=0.25                 # Strong SJF
scheduling_weight_image=0.15
scheduling_weight_package=0.05
scheduling_weight_age=0.05                      # Weak fairness (SJF dominates)
scheduling_poll_interval_ms=3000                # Fast polling (cluster changes rapidly)
```

---

#### Long Tests (>60s mean)

**Characteristics:** Few heavy tests, bin-packing matters more than SJF.

**Recommended Config:**
```properties
scheduling_mice_threshold_ms=30000              # Higher threshold (capture medium tests)
scheduling_weight_pressure=0.55                 # Strong bin-packing
scheduling_weight_duration=0.15                 # Weaker SJF (most tests are elephants)
scheduling_weight_image=0.15
scheduling_weight_package=0.05
scheduling_weight_age=0.10
scheduling_poll_interval_ms=10000               # Slower polling (cluster changes slowly)
```

---

#### Heterogeneous Hardware

**Characteristics:** Nodes with different CPU, memory, disk capabilities.

**Recommended Config:**
```properties
scheduling_mice_threshold_ms=20000
scheduling_weight_pressure=0.50                 # Dominant resource pressure adapts to node bottlenecks
scheduling_weight_duration=0.20
scheduling_weight_image=0.15
scheduling_weight_package=0.05
scheduling_weight_age=0.10
scheduling_poll_interval_ms=5000
score_endpoint_enabled=true                     # Essential for per-node predictions
```

**Additional Notes:**
- Ensure all testers have `stats_enabled=true`
- Monitor per-node utilization balance
- Predictor automatically scales bootstrap defaults by node hardware

---

## Common Configuration Scenarios

### Scenario 1: Migration from Legacy to Smart Scheduling

**Goal:** Safely migrate existing deployment to smart scheduling.

**Phase 1 - Metrics Collection Only (Week 1-2):**
```properties
# Builder
smart_scheduling_enabled=false                  # Keep legacy distribution

# Tester
stats_enabled=true                              # Start collecting metrics
stats_snapshot_interval_seconds=300
```

**Validation:** Check that `test_stats.jl.gz` grows, snapshots are written. No impact on distribution.

---

**Phase 2 - Shadow Mode (Week 3):**

Enable smart scheduling but compare decisions against legacy (log-only, no effect).

*(Requires code change to run both paths and log differences)*

---

**Phase 3 - Smart Scheduling Enabled (Week 4+):**
```properties
# Builder
smart_scheduling_enabled=true                   # Switch to smart scheduling
# Use default weights (balanced)
```

**Validation:** Monitor throughput, mean completion time, cache hit rate. Compare against baseline.

---

**Rollback Plan:**
```properties
smart_scheduling_enabled=false                  # One-line rollback
```

---

### Scenario 2: High-Load Production Environment

**Goal:** Maximize throughput for continuous integration pipeline.

**Configuration:**
```properties
# Builder
smart_scheduling_enabled=true
scheduling_mice_threshold_ms=20000
scheduling_weight_pressure=0.50
scheduling_weight_duration=0.25
scheduling_weight_image=0.15
scheduling_weight_package=0.05
scheduling_weight_age=0.05
scheduling_poll_interval_ms=3000                # Fast polling
scheduling_stale_threshold_ms=15000             # Aggressive staleness

# Tester
stats_enabled=true
stats_snapshot_interval_seconds=180             # Frequent snapshots (tester restarts common)
heartbeat_interval_seconds=3
score_endpoint_enabled=true
max_concurrent_tests=8                          # Higher concurrency
optimized_docker_enabled=true
build_cache_size=30                             # Larger cache
```

**Monitoring:**
- Track tests/hour metric
- Monitor per-node utilization (target: 70-90%)
- Alert on stale node warnings

---

### Scenario 3: Developer Workstation (Single Tester)

**Goal:** Fast feedback for local development.

**Configuration:**
```properties
# Builder
smart_scheduling_enabled=false                  # Not needed for single node
# OR
smart_scheduling_enabled=true                   # Will work, but no benefit
scheduling_mice_threshold_ms=15000              # Aggressive SJF
scheduling_weight_duration=0.40                 # Prioritize short tests

# Tester
stats_enabled=true                              # Still useful for predictions
max_concurrent_tests=4                          # Laptop-appropriate
```

**Notes:**
- Smart scheduling provides minimal benefit with single tester
- Metrics collection still useful for understanding test characteristics

---

### Scenario 4: Cost-Optimized Cloud Environment

**Goal:** Minimize resource waste, maximize bin-packing efficiency.

**Configuration:**
```properties
# Builder
smart_scheduling_enabled=true
scheduling_mice_threshold_ms=20000
scheduling_weight_pressure=0.60                 # Very strong bin-packing
scheduling_weight_duration=0.15                 # Less SJF (accept longer waits)
scheduling_weight_image=0.15
scheduling_weight_package=0.05
scheduling_weight_age=0.05                      # Weak fairness (efficiency over fairness)
scheduling_poll_interval_ms=5000
scheduling_stale_threshold_ms=30000

# Tester
stats_enabled=true
max_concurrent_tests=6
optimized_docker_enabled=true                   # Reduce overhead
```

**Monitoring:**
- Track per-node CPU/memory utilization (target: 80-95%)
- Monitor for starved tests (wait time P95 > threshold)

---

## Troubleshooting Configuration Issues

### Issue: All tests going to one node (load imbalance)

**Symptoms:**
- Builder logs show same nodeId repeatedly in assignments
- Some testers idle, others saturated

**Diagnosis:**
Check weight configuration. Likely causes:
1. `scheduling_weight_image` or `scheduling_weight_package` too high
2. One node has all cached images/packages (cold start elsewhere)
3. Pressure weight too low (not enough bin-packing penalty)

**Fix:**
```properties
# Option 1: Lower cache locality weights
scheduling_weight_image=0.10                    # Was 0.30
scheduling_weight_package=0.03                  # Was 0.10

# Option 2: Increase pressure weight
scheduling_weight_pressure=0.55                 # Was 0.30
```

---

### Issue: Tests starving (never scheduled)

**Symptoms:**
- Some tests wait in queue indefinitely
- "No eligible nodes" warnings for specific tests
- Age boost not helping

**Diagnosis:**
1. Check if tests require more resources than any node has (demand > max capacity)
2. Check if age weight is too low
3. Check if duration weight is too high (long tests penalized)

**Fix:**
```properties
# Option 1: Increase age weight
scheduling_weight_age=0.20                      # Was 0.05

# Option 2: Decrease duration weight
scheduling_weight_duration=0.15                 # Was 0.35

# Option 3: Check test predictions (may need manual override)
```

**Also check:**
- TestStatsStore predictions (are they realistic?)
- Node capacity measurements (accurate?)

---

### Issue: Low cache hit rate (constant cold starts)

**Symptoms:**
- `docker_image_cached=false` in most observations
- Tester logs show "Building image" frequently
- Tests take 10-30s longer than expected

**Diagnosis:**
1. Image weight too low (scheduler not preferring cached images)
2. Build cache size too small (images evicted too soon)
3. Polling interval too slow (stale cache information)

**Fix:**
```properties
# Builder
scheduling_weight_image=0.25                    # Increase cache locality preference
scheduling_poll_interval_ms=3000                # Faster polling

# Tester
build_cache_size=20                             # Larger cache (was 10)
optimized_docker_enabled=true                   # Ensure optimization enabled
```

---

### Issue: High network overhead from polling

**Symptoms:**
- Network traffic spikes every N seconds
- Builder logs show frequent /health GETs
- Minimal benefit from fast polling (long tests)

**Diagnosis:**
Polling interval too fast for workload characteristics.

**Fix:**
```properties
scheduling_poll_interval_ms=10000               # Slow down (was 3000)
scheduling_stale_threshold_ms=60000             # Adjust staleness accordingly
```

**Calculate overhead:**
```
Network cost = (5 KB per health response) × (num_nodes) × (1000 / poll_interval_ms)

Example:
10 nodes, 5s polling = 5 KB × 10 × (1000/5000) = 10 KB/s = 80 Kbps (negligible)
10 nodes, 1s polling = 5 KB × 10 × (1000/1000) = 50 KB/s = 400 Kbps (moderate)
```

---

### Issue: Predictions always use defaults (low confidence)

**Symptoms:**
- `/score` responses show `confidence < 0.3` for all tests
- Tester logs show "No history for test X, using bootstrap"
- Scheduling decisions seem random

**Diagnosis:**
1. `stats_enabled=false` (metrics not being collected)
2. TestStatsStore file missing or corrupted
3. Tests run infrequently (not enough observations)

**Fix:**
```properties
# Tester
stats_enabled=true                              # Ensure enabled
```

**Also check:**
- File exists: `$TESTER_WORK/profiles/test_stats.jl.gz`
- File permissions (writable by tester user)
- Tester logs for WAL replay errors

**If files corrupted:**
```bash
# Backup and reset
mv $TESTER_WORK/profiles $TESTER_WORK/profiles.backup
mkdir $TESTER_WORK/profiles
# Restart tester (will start collecting fresh data)
```

---

## Migration from Legacy Configuration

### Backward Compatibility

All legacy configuration options remain supported. Smart scheduling is fully opt-in.

**Legacy options (still valid):**
```properties
# These continue to work and are used by legacy distribution
max_concurrent_tests=6
work_dir=~/tmp/tester_work
use_docker_tester=true
optimized_docker_enabled=true
build_cache_size=10
```

**New options (smart scheduling only):**
```properties
# Only used when smart_scheduling_enabled=true
scheduling_mice_threshold_ms=20000
scheduling_weight_*=...
scheduling_poll_interval_ms=5000
scheduling_stale_threshold_ms=30000
```

### Side-by-Side Comparison

| Feature | Legacy (Round-Robin) | Smart Scheduling |
|---------|---------------------|------------------|
| Distribution | `testIndex % numWorkers` | Multi-resource scoring |
| Resource awareness | None | CPU, mem, I/O, IOPS, net |
| Cache locality | None | Image + package awareness |
| Duration awareness | None | Mice/elephants + SJF |
| Fairness | Implicit (round-robin) | Explicit (aging) |
| Node health | None | Staleness detection |
| Configuration | Minimal | 9 tunable parameters |
| Fallback | N/A | One-line disable |

---

## Configuration Validation

### Validation Checklist

Before deploying smart scheduling configuration, verify:

**Builder:**
- [ ] `smart_scheduling_enabled` is `true`
- [ ] All weights are non-negative
- [ ] Weights sum to reasonable range (0.8 to 1.2)
- [ ] `scheduling_poll_interval_ms` < `scheduling_stale_threshold_ms`
- [ ] `scheduling_mice_threshold_ms` > 0

**Tester:**
- [ ] `stats_enabled` is `true`
- [ ] `stats_snapshot_interval_seconds` >= 60
- [ ] `heartbeat_interval_seconds` matches builder's `scheduling_poll_interval_ms` (±1s)
- [ ] `score_endpoint_enabled` is `true`
- [ ] `optimized_docker_enabled` is `true` (for cache locality)

### Validation Script

```bash
#!/bin/bash
# validate_smart_scheduling_config.sh

BUILDER_CONF="conf/builder.conf"
TESTER_CONF="conf/tester.conf"

# Check builder config
if grep -q "^smart_scheduling_enabled=true" $BUILDER_CONF; then
    echo "[OK] Smart scheduling enabled"
else
    echo "[WARN] Smart scheduling disabled"
fi

# Check weights sum
w1=$(grep "^scheduling_weight_pressure=" $BUILDER_CONF | cut -d= -f2)
w2=$(grep "^scheduling_weight_duration=" $BUILDER_CONF | cut -d= -f2)
w3=$(grep "^scheduling_weight_image=" $BUILDER_CONF | cut -d= -f2)
w4=$(grep "^scheduling_weight_package=" $BUILDER_CONF | cut -d= -f2)
w5=$(grep "^scheduling_weight_age=" $BUILDER_CONF | cut -d= -f2)
sum=$(echo "$w1 + $w2 + $w3 + $w4 + $w5" | bc)
echo "[INFO] Weight sum: $sum (should be ~1.0)"

# Check polling vs staleness
poll=$(grep "^scheduling_poll_interval_ms=" $BUILDER_CONF | cut -d= -f2)
stale=$(grep "^scheduling_stale_threshold_ms=" $BUILDER_CONF | cut -d= -f2)
if [ "$poll" -lt "$stale" ]; then
    echo "[OK] Poll interval ($poll) < stale threshold ($stale)"
else
    echo "[ERROR] Poll interval >= stale threshold!"
fi

# Check tester config
if grep -q "^stats_enabled=true" $TESTER_CONF; then
    echo "[OK] Stats enabled on tester"
else
    echo "[ERROR] Stats disabled - smart scheduling will use defaults!"
fi
```

---

## Advanced Configuration Topics

### Dynamic Weight Adjustment (Future)

Currently weights are static (configured once). Future enhancement could support:
- **Time-based weights**: Different weights for peak vs off-peak hours
- **Adaptive weights**: Adjust based on observed metrics (e.g., increase age weight if starvation detected)
- **Per-test-type weights**: Different scoring for SQL vs HA vs performance tests

**Example (future):**
```properties
# Peak hours (prioritize throughput)
scheduling_weights.peak=0.55,0.25,0.10,0.05,0.05

# Off-peak hours (prioritize fairness)
scheduling_weights.offpeak=0.35,0.20,0.15,0.10,0.20
```

---

### Multi-Cluster Configuration (Future)

For very large deployments, consider sharding:
- **Cluster A**: Short tests (mice-only)
- **Cluster B**: Long tests (elephants-only)
- **Cluster C**: Flaky tests (isolation)

**Example (future):**
```properties
# Builder A (mice cluster)
scheduling_mice_threshold_ms=30000
scheduling_elephant_routing=cluster_b_url

# Builder B (elephant cluster)
scheduling_mice_routing=cluster_a_url
```

---

## Document Metadata

- **Version:** 1.0
- **Last Updated:** 2025-11-10
- **Related Documents:**
  - [SMART_SCHEDULING_ARCHITECTURE.md](SMART_SCHEDULING_ARCHITECTURE.md) - Architecture and design
  - [SMART_SCHEDULING_TESTING_GUIDE.md](SMART_SCHEDULING_TESTING_GUIDE.md) - Testing procedures
  - [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md) - Image caching details

---

## Quick Reference Card

### Minimal Smart Scheduling Configuration

**Builder (`conf/builder.conf`):**
```properties
smart_scheduling_enabled=true
```

**Tester (`conf/tester.conf`):**
```properties
stats_enabled=true
score_endpoint_enabled=true
```

All other parameters will use sensible defaults.

---

### Default Values Summary

| Parameter | Default | Location |
|-----------|---------|----------|
| `smart_scheduling_enabled` | `false` | builder.conf |
| `scheduling_mice_threshold_ms` | `20000` | builder.conf |
| `scheduling_weight_pressure` | `0.45` | builder.conf |
| `scheduling_weight_duration` | `0.25` | builder.conf |
| `scheduling_weight_image` | `0.15` | builder.conf |
| `scheduling_weight_package` | `0.05` | builder.conf |
| `scheduling_weight_age` | `0.10` | builder.conf |
| `scheduling_poll_interval_ms` | `5000` | builder.conf |
| `scheduling_stale_threshold_ms` | `30000` | builder.conf |
| `stats_enabled` | `true` | tester.conf |
| `stats_snapshot_interval_seconds` | `300` | tester.conf |
| `heartbeat_interval_seconds` | `5` | tester.conf |
| `score_endpoint_enabled` | `true` | tester.conf |

---

## Contact and Support

For questions or issues with smart scheduling configuration:

1. Check logs: `$BUILDER_WORK/logs/builder.log`, `$TESTER_WORK/logs/tester.log`
2. Review this document's troubleshooting section
3. Consult [SMART_SCHEDULING_TESTING_GUIDE.md](SMART_SCHEDULING_TESTING_GUIDE.md)
4. Review architecture in [SMART_SCHEDULING_ARCHITECTURE.md](SMART_SCHEDULING_ARCHITECTURE.md)
