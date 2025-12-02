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

#### `scheduling_elephant_weight`

**Type:** Double (0.0 - 1.0)
**Default:** `0.80`

**Description:**
Probability that the scheduler attempts to place an elephant (long test) before a mouse (short test) on each scheduling cycle. The remaining probability (e.g., `0.20` when set to `0.80`) is automatically assigned to mice. Higher values aggressively prioritize long tests to reduce makespan, while lower values favor quick turnaround for short tests.

**Example:**
```properties
# Balanced mix (70% elephants, 30% mice)
scheduling_elephant_weight=0.70

# Aggressive elephant priority (90% elephants)
scheduling_elephant_weight=0.90
```

**Tuning Guidelines:**
- **Increase** if critical-path tests (long runs) should start as early as possible.
- **Decrease** if short tests queue up or SLA for small tests is more important.
- Combine with `scheduling_mice_threshold_ms` adjustments for finer grained control.

**Notes:**
- Values outside `[0.0, 1.0]` are clamped automatically.

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
**Since:** v2 Production Hardening (November 2025)

**Description:**
Base safety margin for IOPS headroom checks. Only used when `use_iops_predictions=true`.

**Example:**
```properties
# Conservative IOPS margins
scheduling_margin_iops_base=0.15

# Aggressive IOPS margins (requires adequate node capacity)
scheduling_margin_iops_base=0.05
```

**Note:** See `use_iops_predictions` below for enabling/disabling IOPS-based scheduling.

#### `use_iops_predictions`

**Type:** Boolean (true/false)
**Default:** `false` (disabled)
**Since:** November 2025

**Description:**
Master switch for IOPS predictions in smart scheduling. When enabled, the scheduler fetches IOPS predictions from the tester's `/score` endpoint and enforces IOPS capacity limits when assigning tests. When disabled, IOPS predictions are ignored and tests use default `predictedIops=0`, allowing all tests to be scheduled regardless of IOPS capacity.

**Default is `false`** until node IOPS capacity is properly calibrated (see `node_iops_capacity` in tester.conf).

**Example:**
```properties
# Disable IOPS predictions (default - safe for initial deployment)
use_iops_predictions=false

# Enable IOPS predictions (requires node_iops_capacity >= 25000)
use_iops_predictions=true
```

**Behavior When Disabled (use_iops_predictions=false):**
- IOPS predictions from `/score` endpoint are **ignored**
- Tests use default `predictedIops=0.0` from TestInstance
- IOPS headroom check: `node.getIops() <= 0 || node.getFreeIops() >= 0` → **PASSES**
- All tests can be scheduled regardless of node's IOPS capacity
- **Recommended for initial deployment**

**Behavior When Enabled (use_iops_predictions=true):**
- IOPS predictions from `/score` endpoint are **used**
- Tests get actual IOPS values (e.g., 2,500-5,000 per test)
- IOPS headroom check enforces capacity limits with safety margins
- Only tests that fit within node's free IOPS capacity can be scheduled
- **Requires sufficient node IOPS capacity** (see `node_iops_capacity` in tester.conf)

**When to Enable:**
1. After node IOPS capacity is properly configured (≥ 25,000 for 6 concurrent tests)
2. After IOPS predictions have been collected and validated
3. When IOPS is a genuine bottleneck that needs management

**Capacity Planning:**
Required IOPS capacity = (Number of Tests) × (Avg Test IOPS) × (1 + Safety Margin)

Example for 6 tests with 3,500 IOPS average and 12.8% margin:
```
6 tests × 3,500 IOPS × 1.128 = 23,688 IOPS needed
→ Set node_iops_capacity=25000 in tester.conf
→ Then enable: use_iops_predictions=true
```

**Related Configuration:**
- `scheduling_margin_iops_base` - Base IOPS safety margin (used when enabled)
- `scheduling_margin_confidence_factor` - Confidence-based margin scaling
- `node_iops_capacity` in tester.conf - Node's IOPS capacity limit

**Troubleshooting:**
If all tests are blocked with "No eligible nodes" after enabling:
1. Check node IOPS capacity: `curl http://tester:8090/health | grep iops`
2. Verify capacity is sufficient (≥ 25,000 for 6 tests)
3. Temporarily disable: `use_iops_predictions=false`
4. Increase `node_iops_capacity` in tester.conf and restart tester

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

### Adaptive Concurrency Controls

#### `max_concurrent_tests_heavy_queue`

**Type:** Integer (tests)
**Default:** Falls back to `max_concurrent_tests` (legacy)

**Description:**
Hard cap on the number of concurrent tests a tester runs while any heavy test is still in flight. Heavy tests are defined using the same thresholds as the scheduler: predicted duration ≥ `scheduling_mice_threshold_ms` **and** total predicted I/O ≥ `scheduling_io_heavy_threshold` MB/s. As long as a heavy test is running locally, new requests beyond this limit are queued (blocked) until a slot opens or the heavy test finishes.

**Guidelines:**
- Set to the number of tests your node can safely run when heavy jobs are present.
- Keep equal to the old `max_concurrent_tests` value for backward-compatible behavior.
- Lower values reduce risk of I/O collapse during the heaviest parts of a run.

#### `max_concurrent_tests_post_heavy`

**Type:** Integer (tests)
**Default:** Falls back to `max_concurrent_tests_heavy_queue`

**Description:**
Upper bound once all heavy tests finish. After the heavy backlog clears, the tester allows up to this many concurrent executions. This lets you reclaim throughput for the tail of the run without overloading disks during the ramp-up.

**Guidelines:**
- Choose a value that reflects the node’s comfortable peak concurrency.
- Can be the same as the heavy-queue limit if you do not need adaptive behavior.
- HTTP server threads and `/health` reporting use this value so the builder sees peak capacity.

#### `max_concurrent_tests` (legacy fallback)

**Type:** Integer (tests)
**Default:** `4`

**Description:**
Original single-limit knob. When the dual-mode parameters are omitted, both heavy and post-heavy limits fall back to this value. Keep it around for older automation or as a sane default, but prefer the dedicated knobs above for new deployments.

---

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

### Node Capacity Configuration

#### `node_iops_capacity`

**Type:** Double (IOPS)
**Default:** `25000` (moderate SATA SSD baseline)
**Range:** `3000` to `1000000` depending on storage type
**Since:** November 2025

**Description:**
Configurable IOPS capacity of the node, used by smart scheduling to determine how many tests can run concurrently. This value is reported in the `/health` endpoint and used by the builder's IOPS headroom checks when `use_iops_predictions=true`.

The default value of 25,000 IOPS is appropriate for SATA SSD storage and allows ~6 concurrent tests with typical IOPS predictions (2,500-5,000 per test).

**Example:**
```properties
# Conservative (HDD or shared VM storage)
node_iops_capacity=15000

# Moderate SATA SSD (default, recommended for most setups)
node_iops_capacity=25000

# Aggressive NVMe SSD
node_iops_capacity=35000

# Future-proof high-end storage
node_iops_capacity=50000
```

**Storage Type Recommendations:**

| Storage Type | Typical IOPS Range | Recommended Setting | Tests Supported* |
|--------------|-------------------|---------------------|------------------|
| HDD (7200 RPM) | 80-120 | 15,000 | 3-4 tests |
| HDD (15K RPM) | 150-200 | 15,000 | 3-4 tests |
| SATA SSD | 10,000-90,000 | 25,000 | 6 tests |
| NVMe SSD (Gen3) | 100,000-500,000 | 35,000 | 8 tests |
| NVMe SSD (Gen4) | 500,000-1,000,000 | 50,000 | 10+ tests |
| Cloud VM (shared) | 3,000-16,000 | 15,000 | 3-4 tests |

\* Based on average test prediction of 3,500 IOPS with 12.8% safety margins

**Capacity Planning Formula:**
```
Required Capacity = (Target Concurrent Tests) × (Avg Test IOPS) × (1 + Safety Margin)

Example for 6 concurrent tests:
  Avg test IOPS: 3,500
  Safety margin: 0.05 (base) + 0.10 × (1 - 0.22) = 12.8%
  Required: 6 × 3,500 × 1.128 = 23,688 IOPS
  Setting: 25,000 IOPS (provides 5.5% overhead)
```

**Verification:**
```bash
# Check reported capacity
curl -s http://localhost:8090/health | python3 -c \
  "import sys,json; print('IOPS:', json.load(sys.stdin)['capacity']['iops'])"

# Check tester logs
tail -50 bin/tester_output.log | grep "IOPS"
# Should show: "... 25000.0 IOPS"
```

**Tuning Guidelines:**
- **Start conservative**: Use 15,000 or 25,000 initially
- **Monitor utilization**: Track actual IOPS usage during test runs
- **Adjust upward**: If tests consistently use < 60% of capacity
- **Adjust downward**: If tests show I/O contention or slowdowns
- **Match hardware**: Align with actual storage capabilities (don't over-estimate)

**Effect on Scheduling:**
- **Higher capacity** → More tests can run concurrently (if IOPS predictions enabled)
- **Lower capacity** → Fewer concurrent tests, but safer from I/O contention
- **No effect if** `use_iops_predictions=false` in builder.conf (default)

**Interaction with Builder Configuration:**
This parameter works in conjunction with builder.conf settings:
- `use_iops_predictions` - Must be `true` to enforce IOPS limits
- `scheduling_margin_iops_base` - Safety margin applied to predictions
- `scheduling_margin_confidence_factor` - Additional margin for low-confidence tests

**Troubleshooting:**
If tests aren't being assigned after enabling IOPS predictions:
1. Check current capacity: `curl http://tester:8090/health | grep iops`
2. Calculate required capacity (see formula above)
3. If current < required, increase `node_iops_capacity`
4. Restart tester: `bash bin/start_tester.sh`
5. Verify new capacity in /health endpoint

**Historical Note:**
Prior to November 2025, IOPS capacity was hardcoded to 10,000 in NodeCapacity.java, which was too low for most workloads. The configurable parameter allows proper tuning for different storage types and concurrency targets.

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
max_concurrent_tests_heavy_queue=8              # Conservative cap while heavy jobs run
max_concurrent_tests_post_heavy=12              # Relax once heavy backlog drains
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
max_concurrent_tests_heavy_queue=3              # Keep heavy tests from starving laptop
max_concurrent_tests_post_heavy=4               # Allow one extra slot once heavy tests finish
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
max_concurrent_tests_heavy_queue=6
max_concurrent_tests_post_heavy=6
optimized_docker_enabled=true                   # Reduce overhead
```

**Monitoring:**
- Track per-node CPU/memory utilization (target: 80-95%)
- Monitor for starved tests (wait time P95 > threshold)

---

### Scenario 5: IOPS-Aware Scheduling (Recommended for Production)

**Goal:** Enable full IOPS-based scheduling with properly configured capacity.

**Prerequisites:**
1. Tests have been running for at least a few days (IOPS observations collected)
2. Node storage type is known (HDD, SATA SSD, NVMe SSD)
3. Ready to enforce IOPS capacity limits

**Configuration:**

**Step 1 - Configure Node IOPS Capacity (tester.conf):**
```properties
# Tester
stats_enabled=true
score_endpoint_enabled=true

# Set based on storage type (see table in node_iops_capacity section)
# For SATA SSD supporting 6 concurrent tests:
node_iops_capacity=25000

# For NVMe SSD supporting 8 concurrent tests:
# node_iops_capacity=35000

# For slow VM or HDD supporting 3-4 concurrent tests:
# node_iops_capacity=15000
```

**Step 2 - Enable IOPS Predictions (builder.conf):**
```properties
# Builder
smart_scheduling_enabled=true
use_iops_predictions=true                       # Enable IOPS enforcement

# Use reduced margins since we're now enforcing limits
scheduling_margin_iops_base=0.05                # 5% base (was 0.25)
scheduling_margin_confidence_factor=0.10        # 10% for low confidence (was 0.50)

# Other standard settings
scheduling_weight_pressure=0.45
scheduling_weight_duration=0.25
scheduling_weight_image=0.15
scheduling_weight_package=0.05
scheduling_weight_age=0.10
```

**Step 3 - Restart Services:**
```bash
# Restart tester to apply new node_iops_capacity
bash bin/start_tester.sh

# Verify capacity
curl -s http://tester:8090/health | grep iops
# Should show: "iops": 25000

# Builder doesn't need restart (config read per-request)
```

**Step 4 - Validate:**
```bash
# Run a test with 6 tests
# All should be assigned if capacity is sufficient

# Check builder logs for IOPS headroom checks
grep "IOPS" builder.log

# Monitor for "No eligible nodes" warnings
# If present, capacity may be too low
```

**Capacity Verification Formula:**
```
Tests that fit = node_iops_capacity / (avg_test_iops × (1 + margin))

Example with node_iops_capacity=25000:
  Avg test IOPS: 3,500
  Margin: 0.05 + 0.10 × (1 - 0.22) = 12.8%
  Tests that fit: 25,000 / (3,500 × 1.128) = 6.3 ✓
```

**Monitoring:**
- Track IOPS utilization in test observations
- Monitor "No eligible nodes" frequency in builder logs
- Alert if capacity < 20% free during peak (indicates undersizing)

**Rollback Plan:**
```properties
# Disable IOPS predictions if issues arise
use_iops_predictions=false
# All tests will schedule regardless of IOPS
```

**Benefits:**
- Prevents IOPS oversubscription and I/O contention
- Better utilization of node I/O capacity
- Predictable test performance (no I/O throttling)

**When to Adjust:**
- **Increase capacity** if tests frequently blocked
- **Decrease capacity** if observing I/O contention or slowdowns
- **Disable** if IOPS not actually a bottleneck for your workload

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

### Issue: Tests blocked with "No eligible nodes" after enabling IOPS predictions

**Symptoms:**
- Tests stop being assigned after 1-2 are scheduled
- Builder logs show "No eligible nodes after 10 attempts, waiting..."
- `/health` endpoint shows IOPS capacity (e.g., 10,000)
- `/score` endpoint returns IOPS predictions (e.g., 2,500-5,000 per test)
- Node appears healthy but scheduler rejects tests

**Diagnosis:**
IOPS capacity exhaustion - node capacity too low for predicted test demands.

**Example Calculation:**
```
Node capacity: 10,000 IOPS
Test predictions: 2,500-5,000 IOPS per test
Safety margins: 12.8% (5% base + 10% × (1 - 0.22 confidence))

Test 1: 2,500 × 1.128 = 2,820 IOPS required
Test 2: 5,000 × 1.128 = 5,640 IOPS required
Total: 8,460 IOPS (fits within 10,000) ✓

Test 3: 3,500 × 1.128 = 3,948 IOPS required
Total so far: 12,408 IOPS > 10,000 capacity ✗
→ Test 3 and beyond are rejected
```

**Fix Option 1 - Increase Node Capacity (Recommended):**
```properties
# In tester.conf
node_iops_capacity=25000    # Increased from 10000

# Restart tester
bash bin/start_tester.sh

# Verify
curl http://tester:8090/health | grep iops
# Should show: "iops": 25000
```

**Fix Option 2 - Disable IOPS Predictions (Temporary):**
```properties
# In builder.conf
use_iops_predictions=false   # Temporarily disable

# No restart needed (builder reads config per-request)
```

**Verification:**
```bash
# Run test with 6 tests
# With node_iops_capacity=25000:
#   25,000 / (3,500 × 1.128) = 6.3 tests fit ✓

# Check builder logs - should see all 6 tests assigned
grep "Assignment.*/" builder.log
# Should show: Assignment 1/6, 2/6, ... 6/6
```

**Root Cause (Historical):**
Prior to November 2025, IOPS capacity was hardcoded to 10,000 in NodeCapacity.java. This value was:
- Too low for SATA SSD (typical: 10K-90K IOPS)
- Too high for HDD (typical: 80-120 IOPS)
- Did not match any hardware type well
- Prevented more than 2-3 tests from running concurrently

The configurable `node_iops_capacity` parameter was introduced to allow proper tuning for different storage types.

**Prevention:**
- Set `node_iops_capacity` based on actual storage type (see table in node_iops_capacity section)
- Use capacity planning formula before enabling IOPS predictions
- Start with `use_iops_predictions=false` until capacity is verified

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
max_concurrent_tests_heavy_queue=6
max_concurrent_tests_post_heavy=6
max_concurrent_tests=6                      # Legacy single limit fallback
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

- **Version:** 1.1
- **Last Updated:** 2025-11-19
- **Recent Changes:**
  - Added `use_iops_predictions` configuration parameter (builder.conf)
  - Added `node_iops_capacity` configuration parameter (tester.conf)
  - Added Scenario 5: IOPS-Aware Scheduling
  - Added troubleshooting case for IOPS capacity exhaustion
  - Documented IOPS capacity planning and storage type recommendations
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
| `node_iops_capacity` | `25000` | tester.conf |
| `use_iops_predictions` | `false` | builder.conf |
| `scheduling_margin_iops_base` | `0.25` (0.05 when predictions enabled) | builder.conf |

---

## Contact and Support

For questions or issues with smart scheduling configuration:

1. Check logs: `$BUILDER_WORK/logs/builder.log`, `$TESTER_WORK/logs/tester.log`
2. Review this document's troubleshooting section
3. Consult [SMART_SCHEDULING_TESTING_GUIDE.md](SMART_SCHEDULING_TESTING_GUIDE.md)
4. Review architecture in [SMART_SCHEDULING_ARCHITECTURE.md](SMART_SCHEDULING_ARCHITECTURE.md)
