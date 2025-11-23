# Smart Scheduling System - Manual Testing Guide

## Overview

This guide provides step-by-step instructions for manually testing the Smart Scheduling system implementation. The system replaces the legacy round-robin test distribution with intelligent, multi-resource-aware scheduling that considers node capacity, cache locality, and predicted test resource demands.

## Prerequisites

Before testing, ensure you have:

- **Builder node** with compiled Builder-Tester system
- **2+ Tester nodes** running and accessible
- Access to configuration files in `conf/`
- Access to logs in both builder and tester work directories
- Basic understanding of the test suite structure

## Test Environment Setup

### 1. Verify Compilation

```bash
cd /path/to/CTP/builder_tester
./bin/compile.sh
```

Expected output: "Compilation successful!" and JAR creation confirmation.

### 2. Configure Builder for Smart Scheduling

Edit `conf/builder.conf`:

```properties
# Enable smart scheduling
smart_scheduling_enabled=true

# Scheduling algorithm parameters
scheduling_mice_threshold_ms=20000           # Tests ≤20s are "mice", >20s are "elephants"
scheduling_weight_pressure=0.45              # Weight for resource pressure (bin-packing)
scheduling_weight_duration=0.25              # Weight for predicted test duration
scheduling_weight_image=0.15                 # Weight for Docker image cache locality
scheduling_weight_package=0.05               # Weight for build package cache locality
scheduling_weight_age=0.10                   # Weight for queue aging (fairness)

# Node polling and staleness
scheduling_poll_interval_ms=5000             # How often to poll tester /health endpoints
scheduling_stale_threshold_ms=30000          # Mark nodes stale after 30s without heartbeat

# Existing builder config (keep as-is)
cubrid_git_url=https://github.com/CUBRID/cubrid.git
# ... other settings
```

### 3. Configure Testers for Metrics Collection

Edit `conf/tester.conf` on each tester node:

```properties
# Enable metrics collection and stats store
stats_enabled=true
stats_snapshot_interval_seconds=300          # Write snapshot every 5 minutes

# Node capacity and health reporting
heartbeat_interval_seconds=5                 # Report health every 5 seconds
score_endpoint_enabled=true                  # Enable /score endpoint for predictions

# Existing tester config (keep as-is)
max_concurrent_tests=6
executor_type=optimized-docker
# ... other settings
```

### 4. Clear Previous Test Statistics (Optional, for Clean Test)

To start with no historical data:

```bash
# On each tester node
rm -f $TESTER_WORK/profiles/test_stats.jl.gz
rm -f $TESTER_WORK/profiles/test_stats.snapshot.json.gz
```

## Manual Test Scenarios

### Test 1: Verify Metrics Collection (Phase 1)

**Goal**: Confirm that testers are collecting per-test observations.

**Steps**:

1. Start a single tester:
   ```bash
   ./bin/start-tester.sh
   ```

2. Run a small test batch through the builder (or trigger directly on tester):
   ```bash
   # Example: Run 5-10 tests
   ./bin/run-tests.sh --commit <commit-sha> --tests shell/sql/basic/*
   ```

3. Check that observations are being recorded:
   ```bash
   # On the tester node
   zcat $TESTER_WORK/profiles/test_stats.jl.gz | head -5
   ```

**Expected Output**:
```json
{"v":1,"ts":"2025-11-10T12:34:56Z","testKey":"shell/sql/basic/test_01.sh","commit":"abc1234",...}
{"v":1,"ts":"2025-11-10T12:35:10Z","testKey":"shell/sql/basic/test_02.sh","commit":"abc1234",...}
```

**Verification Checklist**:
- [ ] File `test_stats.jl.gz` exists and contains JSON lines
- [ ] Each observation has: `testKey`, `commit`, `duration_ms`, `cpu_pct_mean`, `mem_mb_mean`, `io_mb_s_mean`
- [ ] `docker_image_cached` and `package_cached` flags are present
- [ ] Timestamps are sequential and recent

---

### Test 2: Verify Stats Store and Prediction (Phase 2)

**Goal**: Confirm TestStatsStore loads, aggregates, and makes predictions.

**Steps**:

1. Run the same test multiple times to build history:
   ```bash
   # Run the same test 10 times
   for i in {1..10}; do
     ./bin/run-tests.sh --commit <commit-sha> --tests shell/sql/basic/test_01.sh
   done
   ```

2. Wait for snapshot interval (5 minutes) or trigger tester restart to force snapshot write:
   ```bash
   ./bin/stop-tester.sh
   ./bin/start-tester.sh
   ```

3. Check snapshot file:
   ```bash
   zcat $TESTER_WORK/profiles/test_stats.snapshot.json.gz | jq .
   ```

**Expected Output**:
```json
{
  "v": 1,
  "testKey": "shell/sql/basic/test_01.sh",
  "counts": 10,
  "duration_ms": {"p50": 8200, "p95": 9500, "ewma": 8350},
  "cpu_pct": {"avg": 45.2, "p95": 78.0},
  "mem_mb": {"avg": 420, "p95": 650},
  ...
}
```

**Verification Checklist**:
- [ ] Snapshot file contains aggregated statistics per test
- [ ] `counts` matches number of test runs
- [ ] EWMA values are reasonable
- [ ] Percentiles (P50, P95) are present

---

### Test 3: Verify Health Endpoint Extensions (Phase 3)

**Goal**: Confirm testers report capacity, utilization, and cache state.

**Steps**:

1. Ensure tester is running with tests in progress:
   ```bash
   # Start some long-running tests
   ./bin/run-tests.sh --commit <commit-sha> --tests shell/sql/ha/* &
   ```

2. Query the /health endpoint:
   ```bash
   curl -s http://<tester-ip>:8090/health | jq .
   ```

**Expected Output**:
```json
{
  "v": 1,
  "nodeId": "192.168.1.101:8090",
  "ts": "2025-11-10T12:36:05Z",
  "concurrency": {
    "max": 6,
    "running": 3,
    "queued": 0
  },
  "capacity": {
    "cpu_pct": 800.0,
    "mem_mb": 32768,
    "io_mb_s": 500,
    "iops": 10000,
    "net_mb_s": 1000
  },
  "utilization": {
    "cpu_pct": 240.0,
    "mem_mb": 8500,
    "io_mb_s": 85,
    "iops": 1200,
    "net_mb_s": 15
  },
  "images": {
    "present": ["cubrid-test:abc1234_def5678", "cubrid-test:xyz9999_aaa0000"],
    "cache_size": 8
  },
  "packages": {
    "present": ["cubrid_abc1234.tar.gz", "cubrid_xyz9999.tar.gz"]
  },
  "flags": {
    "degraded": false,
    "disk_pressure": false
  }
}
```

**Verification Checklist**:
- [ ] All fields present: v, nodeId, ts, concurrency, capacity, utilization, images, packages, flags
- [ ] `running` count matches actual running tests
- [ ] `capacity` reflects node hardware (CPU cores × 100, RAM in MB)
- [ ] `utilization` shows current resource usage
- [ ] `images.present` lists cached Docker images
- [ ] `packages.present` lists cached build packages

---

### Test 4: Verify Score Endpoint (Phase 3)

**Goal**: Confirm testers can predict resource demands for a batch of tests.

**Steps**:

1. Prepare a test request JSON:
   ```bash
   cat > /tmp/score_request.json <<EOF
   {
     "tests": [
       "shell/sql/basic/test_01.sh",
       "shell/sql/ha/test_replication.sh",
       "shell/sql/performance/test_large_insert.sh"
     ],
     "commit": "abc1234",
     "baseline": "def5678"
   }
   EOF
   ```

2. POST to /score endpoint:
   ```bash
   curl -X POST -H "Content-Type: application/json" \
     -d @/tmp/score_request.json \
     http://<tester-ip>:8090/score | jq .
   ```

**Expected Output**:
```json
{
  "predictions": {
    "shell/sql/basic/test_01.sh": {
      "tpred_ms": 8350,
      "cpu_pct": 45.2,
      "mem_mb": 420,
      "io_mb_s": 8.5,
      "iops": 150,
      "net_mb_s": 2.1,
      "confidence": 0.95
    },
    "shell/sql/ha/test_replication.sh": {
      "tpred_ms": 45000,
      "cpu_pct": 120.0,
      "mem_mb": 1800,
      "io_mb_s": 35.0,
      "iops": 2500,
      "net_mb_s": 50.0,
      "confidence": 0.85
    },
    "shell/sql/performance/test_large_insert.sh": {
      "tpred_ms": 30000,
      "cpu_pct": 60.0,
      "mem_mb": 512,
      "io_mb_s": 15.0,
      "iops": 800,
      "net_mb_s": 5.0,
      "confidence": 0.25
    }
  }
}
```

**Verification Checklist**:
- [ ] Predictions returned for all requested tests
- [ ] Each prediction has: tpred_ms, cpu_pct, mem_mb, io_mb_s, iops, net_mb_s, confidence
- [ ] Tests with history have high confidence (>0.7)
- [ ] New/unseen tests have lower confidence and use bootstrap defaults
- [ ] Duration predictions match TestStats EWMA values

---

### Test 5: Verify Node Directory and Polling (Phase 4)

**Goal**: Confirm builder polls testers and maintains cluster state.

**Steps**:

1. Start multiple testers (at least 2):
   ```bash
   # On tester1
   ./bin/start-tester.sh --port 8090

   # On tester2
   ./bin/start-tester.sh --port 8090
   ```

2. Configure builder with both testers:
   ```properties
   # In builder.conf
   test_node_ips=192.168.1.101:8090,192.168.1.102:8090
   ```

3. Start a build job and monitor builder logs:
   ```bash
   tail -f $BUILDER_WORK/logs/builder.log | grep -E "(NodeDirectory|health|heartbeat)"
   ```

**Expected Log Output**:
```
[INFO] NodeDirectory: Polling 2 nodes for health updates
[INFO] NodeDirectory: Updated snapshot for 192.168.1.101:8090 (healthy, 2/6 slots used)
[INFO] NodeDirectory: Updated snapshot for 192.168.1.102:8090 (healthy, 4/6 slots used)
[INFO] NodeDirectory: 2 healthy nodes, 0 stale nodes
```

**Verification Checklist**:
- [ ] Builder polls all configured testers every 5 seconds
- [ ] NodeDirectory maintains snapshots for each node
- [ ] Stale detection works (stop a tester, verify marked stale after 30s)
- [ ] Eligible node filtering excludes stale/degraded nodes

---

### Test 6: Verify Smart Scheduling Algorithm (Phase 4 & 5)

**Goal**: Confirm scheduler makes intelligent placement decisions.

**Steps**:

1. Set up heterogeneous test workload:
   - Mix of short tests (mice, <20s) and long tests (elephants, >20s)
   - Mix of cached and uncached Docker images

2. Run a build with smart scheduling enabled:
   ```bash
   ./bin/run-builder.sh --commit <commit1>,<commit2> --tests shell/sql/**
   ```

3. Monitor builder logs for scheduling decisions:
   ```bash
   tail -f $BUILDER_WORK/logs/builder.log | grep -E "(SchedulerService|Assignment|Score)"
   ```

**Expected Log Output**:
```
[INFO] SchedulerService: Offered 247 tests to ready queue (mice: 198, elephants: 49)
[INFO] SchedulerService: Assigned mouse test shell/sql/basic/test_01.sh to 192.168.1.101:8090 (score: 0.23)
[INFO] SchedulerService:   Score breakdown: pressure=0.15, duration=0.05, image=0.00, package=0.00, age=0.03
[INFO] SchedulerService: Assigned elephant test shell/sql/ha/test_replication.sh to 192.168.1.102:8090 (score: 0.67)
[INFO] SchedulerService:   Score breakdown: pressure=0.45, duration=0.20, image=0.15, package=0.05, age=0.00
```

**Verification Checklist**:
- [ ] Tests are separated into mice and elephants queues
- [ ] Mice (short tests) are scheduled first with SJF ordering
- [ ] Image cache locality affects scoring (cached images get lower scores)
- [ ] Multi-resource pressure calculated correctly (max of CPU, mem, IO fractions)
- [ ] Queue aging boosts score for long-waiting tests
- [ ] No node exceeds max_concurrent_tests limit

---

### Test 7: Compare Smart vs Legacy Distribution (A/B Test)

**Goal**: Quantitatively measure improvement over round-robin.

**Setup**: Two identical test runs, one with smart scheduling, one without.

**Steps**:

1. **Baseline run (legacy round-robin)**:
   ```bash
   # In builder.conf
   smart_scheduling_enabled=false

   # Run test suite and record metrics
   ./bin/run-builder.sh --commit <commit-sha> --tests shell/sql/** 2>&1 | tee legacy_run.log
   ```

2. **Smart scheduling run**:
   ```bash
   # In builder.conf
   smart_scheduling_enabled=true

   # Run same test suite
   ./bin/run-builder.sh --commit <commit-sha> --tests shell/sql/** 2>&1 | tee smart_run.log
   ```

3. **Extract metrics**:
   ```bash
   # Total execution time
   grep "Total execution time" legacy_run.log
   grep "Total execution time" smart_run.log

   # Per-node utilization (check tester logs)
   grep "Average CPU utilization" $TESTER_WORK/logs/tester.log

   # Image cache hit rate
   grep "Docker image cache" $TESTER_WORK/logs/tester.log
   ```

**Expected Improvements**:
- [ ] **Throughput**: 10-30% more tests/hour with smart scheduling
- [ ] **Mean completion time**: Shorter for mice (SJF effect)
- [ ] **P95 tail latency**: Similar or better (no new blocking)
- [ ] **Node utilization balance**: Lower stddev across nodes
- [ ] **Cache hit rate**: Higher (locality-aware scheduling)

---

### Test 8: Edge Cases and Failure Modes

**Goal**: Verify scheduler handles edge cases gracefully.

#### 8.1 All Nodes Full
```bash
# Start tests to saturate all testers
# Then try to schedule more
```
Expected: Scheduler waits, logs "No eligible nodes", retries with backoff.

#### 8.2 Node Goes Offline Mid-Run
```bash
# Kill a tester during test execution
kill -9 <tester-pid>
```
Expected: NodeDirectory marks node stale after 30s, scheduler stops assigning to it.

#### 8.3 No Historical Data (Cold Start)
```bash
# Clear all stats, run new test
rm -f $TESTER_WORK/profiles/*.gz
```
Expected: Predictor uses bootstrap defaults (30s, 50% CPU, 512MB), confidence=0.25.

#### 8.4 Extremely Large Test (Elephant)
```bash
# Run a test predicted to take >5 minutes and use >50% node capacity
```
Expected: Scheduler assigns to node with most free capacity, may block other elephants.

#### 8.5 Queue Aging/Starvation Prevention
```bash
# Submit 100 mice tests, then 1 elephant, then 100 more mice
```
Expected: Elephant eventually gets aged boost, scheduled before newer mice.

---

## Performance Benchmarking

### Key Metrics to Track

1. **Throughput** (tests/hour):
   ```bash
   # From builder logs
   grep "Completed.*tests in" $BUILDER_WORK/logs/builder.log
   ```

2. **Mean & P95 Test Completion Time**:
   ```bash
   # Extract from TestObservations
   zcat $TESTER_WORK/profiles/test_stats.jl.gz | \
     jq -r '.duration_ms' | \
     awk '{sum+=$1; sumsq+=$1*$1; a[NR]=$1} END {
       asort(a);
       print "Mean:", sum/NR, "ms";
       print "P95:", a[int(NR*0.95)], "ms"
     }'
   ```

3. **Node Utilization Balance** (stddev of per-node CPU%):
   ```bash
   # Check /health utilization across all nodes
   for node in $TESTER_IPS; do
     curl -s http://$node/health | jq -r '.utilization.cpu_pct'
   done | awk '{sum+=$1; sumsq+=$1*$1} END {print sqrt(sumsq/NR - (sum/NR)^2)}'
   ```

4. **Image Cache Hit Rate**:
   ```bash
   zcat $TESTER_WORK/profiles/test_stats.jl.gz | \
     jq -r '.docker_image_cached' | \
     awk '{sum+=$1; count++} END {print sum/count*100"%"}'
   ```

### Target Improvements (vs Legacy)
- Throughput: +10-30%
- Mean completion (mice): -15-25%
- P95 tail: ≤ +5% (no regression)
- Utilization stddev: -20-40% (better balance)
- Cache hit rate: +10-20%

---

## Troubleshooting

### Issue: "No eligible nodes available" (logs filled with this)

**Diagnosis**: All nodes are saturated or marked stale.

**Fixes**:
1. Check tester health: `curl http://<tester-ip>:8090/health`
2. Verify `max_concurrent_tests` not too low
3. Check for disk pressure or degraded flags
4. Review `scheduling_stale_threshold_ms` (may be too aggressive)

---

### Issue: Tests all going to one node (no load balancing)

**Diagnosis**: Scoring function may be misconfigured or all nodes identical.

**Fixes**:
1. Check that testers report different utilization in /health
2. Verify weight configuration in builder.conf
3. Increase `scheduling_weight_pressure` to favor spreading
4. Check NodeDirectory logs for snapshot updates

---

### Issue: Low cache hit rates (no locality benefit)

**Diagnosis**: Image penalty weight too low or images not being reported.

**Fixes**:
1. Verify /health endpoint includes `images.present` list
2. Increase `scheduling_weight_image` (e.g., 0.20 instead of 0.15)
3. Ensure Optimized Docker executor is enabled
4. Check Docker image naming matches `cubrid-test:<commit>_<baseline>` pattern

---

### Issue: Predictions always use defaults (low confidence)

**Diagnosis**: TestStatsStore not persisting or loading correctly.

**Fixes**:
1. Check that `stats_enabled=true` in tester.conf
2. Verify write permissions on `$TESTER_WORK/profiles/` directory
3. Check tester logs for WAL replay errors
4. Manually inspect snapshot file for test entries

---

## Rollback Procedure

If smart scheduling causes issues, immediately revert to legacy mode:

1. Edit `conf/builder.conf`:
   ```properties
   smart_scheduling_enabled=false
   ```

2. Restart builder (no tester restart needed)

3. Verify legacy mode in logs:
   ```bash
   tail -f $BUILDER_WORK/logs/builder.log | grep "LEGACY work-queue distribution"
   ```

Legacy round-robin distribution will resume immediately.

---

## Next Steps After Testing

Once all tests pass:

1. **Production Rollout**:
   - Deploy to staging environment for 1-2 weeks
- Monitor metrics dashboard
- Gradually tune weights based on workload characteristics

2. **Optimization**:
  - Adjust mice/elephants threshold based on observed P50 duration
  - Tune scoring weights for your specific test mix
  - Consider enabling optional features (speculation, pull mode)

3. **Documentation**:
  - Update team runbooks with new config options
  - Document expected metrics and alerting thresholds
  - Create troubleshooting playbook for common issues

---

## Requeue & Retry Capacity (November 2025)

- Builder requeues capacity/concurrency 409 rejections instead of marking them complete.
- Retry metadata (`retryAttempt`, `isRetry`) is sent to testers; testers enforce a separate retry cap (`max_concurrent_tests_retry`, default 5, `-1` for unlimited).
- `/health` reports `retryRunning` and `maxRetry` to observe retry slots.
- Config knobs:
  - `builder.conf`: `requeue_max_attempts` (`-1` = unlimited until nodes accept or are idle).
  - `tester.conf`: `max_concurrent_tests_retry` to prevent starving new work.
- Validation:
  - Run `RequeueLogicTest`: `java -cp "build:lib/builder-tester.jar:lib/json.jar" com.navercorp.cubridqa.builder.test.RequeueLogicTest`
  - Trigger 409s and watch logs/health for retries filling retry slots.

---

## Appendix: Quick Reference

### Configuration File Locations
- Builder: `/home/qahome/cubrid-testtools/CTP/builder_tester/conf/builder.conf`
- Tester: `/home/qahome/cubrid-testtools/CTP/builder_tester/conf/tester.conf`

### Log Locations
- Builder logs: `$BUILDER_WORK/logs/builder.log`
- Tester logs: `$TESTER_WORK/logs/tester.log`
- Test stats: `$TESTER_WORK/profiles/test_stats.jl.gz`
- Snapshot: `$TESTER_WORK/profiles/test_stats.snapshot.json.gz`

### Key Endpoints
- Tester health: `http://<tester-ip>:8090/health`
- Tester scoring: `http://<tester-ip>:8090/score` (POST)
- Tester test submit: `http://<tester-ip>:8090/test` (POST)

### Useful Commands
```bash
# Compile
./bin/compile.sh

# View test stats
zcat $TESTER_WORK/profiles/test_stats.jl.gz | jq . | less

# View snapshot
zcat $TESTER_WORK/profiles/test_stats.snapshot.json.gz | jq . | less

# Monitor scheduling decisions
tail -f $BUILDER_WORK/logs/builder.log | grep "SchedulerService"

# Check node health
curl -s http://<tester-ip>:8090/health | jq .
```
