# Predictive Demand Tracking Architecture

## Overview

This document describes the **implemented** architecture for tracking predicted resource demands from test scheduling through execution, enabling accurate real-time utilization reporting at the tester nodes.

**Status:** ✅ **IMPLEMENTED** (2025-11-11)

## Problem Statement & Solution

**Previous State:**
- ❌ Scheduler (BuilderTask) had predictions but didn't send them to testers
- ❌ HealthHandler reported utilization using conservative fixed estimates (50% CPU, 512 MB per test)
- ❌ Led to either over-conservative scheduling (wasted capacity) or potential oversubscription

**Current State (Implemented):**
- ✅ Test requests include "predicted" field with actual resource demands
- ✅ RunningTestTracker tracks predicted demands for all running tests
- ✅ HealthHandler reports accurate utilization (sum of running test demands)
- ✅ NodeDirectory validates resource headroom before scheduling (10% safety margin)
- ✅ Multi-layer protection against node oversubscription

## New: Peak Resource Telemetry (Implemented)

- Test observations now persist **per-run peaks** for CPU%, memory MB, I/O MB/s, IOPS, and net MB/s alongside averages and percentiles.
- Peaks are carried through WAL → snapshot (`test_stats.snapshot.json.gz`) → `latest.json.gz`, so new nodes importing stats keep the maxima.
- Benefits: schedulers can conservatively gate elephant tests on peak footprints while still using averages to pack mice; operators can debug bursty tests without replaying raw logs.
- Backward compatibility: peak fields are optional on import; legacy snapshots and `latest.json.gz` still load with zeroed peaks.
- Tuning: peak blending into predictions is configurable via `predictor_peak_blend_*` in `conf/builder.conf` (defaults now: cpu=0.45, mem=0.55, io=0.45, iops=0.45, net=0.35). The value represents the max fraction of the gap between mean and peak to blend in (scaled down as confidence rises). Lower if peak bias slows runs or increases queueing; raise (<=1.0) if spikes still overload nodes.
  - Verbose example: `cpu_mean=80%`, `cpu_peak=140%`, confidence=0.5, `predictor_peak_blend_cpu_max=0.45`. Gap = 60. Blended add = `0.45 * (1 - 0.5) * 60 = 13.5`. Predicted CPU ≈ 80 + 13.5 = 93.5%. If the blend were 0.0 → 80%; if 1.0 → 110% (half the gap added because confidence=0.5).

## Simple Explanation: How Predictive Demand Tracking Works

### The Problem: "How Busy is This Server?"

Imagine you're a **restaurant manager** scheduling waiters to serve tables. You need to know:
- How many waiters are currently busy?
- How much work can each waiter handle?
- Can I assign a new table to this waiter?

**Old Way (Broken):**
```
Manager: "How busy are you?"
Waiter: "I'm serving 3 tables"  ← But we don't know how much work each table needs!

Manager: "OK, I'll assume each table needs 50% of your time"
         → Assigns 2 more tables
         → Waiter gets overwhelmed! ❌
```

**New Way (Fixed):**
```
Manager: "How busy are you?"
Waiter: "I'm serving 3 tables:
         - Table 1: needs 30% of my time (light order)
         - Table 2: needs 80% of my time (big party)
         - Table 3: needs 20% of my time (quick snack)
         Total: 130% - I'm overloaded!"  ← Accurate! ✅

Manager: "OK, I won't assign more tables to you"
```

### The Solution: Track Actual Predictions

Instead of guessing "each test uses 50% CPU", we:
1. **Predict** how much each test will need (from historical data)
2. **Send** that prediction with the test request
3. **Track** all running tests and their predictions
4. **Sum** the predictions to get accurate utilization

### Complete Example: A Day in the Life

**Scenario:** A tester node with 8 CPU cores, 16 GB RAM, running multiple tests

#### Step 1: Scheduler Predicts Test Demands

The scheduler (BuilderTask) asks the Predictor: "How much will this test need?"

```
Test: sql_partition_test
Predictor Response:
  - CPU: 75% (of one core)
  - Memory: 1024 MB
  - I/O Read: 10 MB/s
  - I/O Write: 5 MB/s
  - Duration: 45 seconds
  - Confidence: 85%
```

#### Step 2: Scheduler Sends Test Request with Predictions

The scheduler sends a test request to the tester node:

```json
{
  "testKey": "sql/_01_object/_09_partition/...",
  "commit": "b3a0c2d1234...",
  "predicted": {
    "cpuPct": 75.5,
    "memMb": 1024.0,
    "ioReadBytesPerSec": 10485760,   // 10 MB/s
    "ioWriteBytesPerSec": 5242880,    // 5 MB/s
    "durationMs": 45000,
    "confidence": 0.85
  }
}
```

#### Step 3: Tester Registers the Test

When the test starts, the tester:
1. Generates a unique test ID: `sql_partition_test@b3a0c2d@1699901234567@a3f9`
2. Extracts the predicted demand from the request
3. Registers it in the `RunningTestTracker`:

```
RunningTestTracker (in-memory map):
  "sql_partition_test@b3a0c2d@1699901234567@a3f9" → {
    testKey: "sql_partition_test",
    cpuPct: 75.5,
    memMb: 1024.0,
    ioReadBytesPerSec: 10485760,
    ioWriteBytesPerSec: 5242880,
    ...
  }
```

#### Step 4: Multiple Tests Run Concurrently

As more tests start, the tracker accumulates them:

```
Time 10:00 AM - Test A starts
RunningTestTracker:
  Test A: CPU 75%, Memory 1024 MB

Time 10:01 AM - Test B starts  
RunningTestTracker:
  Test A: CPU 75%, Memory 1024 MB
  Test B: CPU 50%, Memory 512 MB
  TOTAL: CPU 125%, Memory 1536 MB

Time 10:02 AM - Test C starts
RunningTestTracker:
  Test A: CPU 75%, Memory 1024 MB
  Test B: CPU 50%, Memory 512 MB
  Test C: CPU 100%, Memory 2048 MB
  TOTAL: CPU 225%, Memory 3584 MB
```

#### Step 5: Scheduler Polls Health Endpoint

Every second, the scheduler asks: "How busy are you?"

**Tester's /health endpoint:**
```json
{
  "utilization": {
    "cpu_millicores": 2250,        // 225% of one core = 2250 millicores
    "mem_bytes": 3758096384,        // 3584 MB
    "io_read_bytes_per_sec": 15728640,  // Sum of all tests
    "io_write_bytes_per_sec": 7864320,
    "test_count": 3
  },
  "capacity": {
    "cpu_millicores": 8000,         // 8 cores = 8000 millicores
    "mem_bytes": 17179869184,        // 16 GB
    "io_read_bytes_per_sec": 104857600,  // 100 MB/s capacity
    "io_write_bytes_per_sec": 52428800   // 50 MB/s capacity
  },
  "free": {
    "cpu_millicores": 5750,         // 8000 - 2250 = 5750 free
    "mem_bytes": 13421772800,       // 16 GB - 3.5 GB = 12.5 GB free
    ...
  }
}
```

#### Step 6: Scheduler Validates Before Assigning New Test

When a new test needs scheduling:

```
New Test D needs: CPU 100%, Memory 2048 MB

Scheduler checks Node A:
  - Free CPU: 5750 millicores (57.5% of one core)
  - Required: 100% of one core = 1000 millicores
  - With 10% safety margin: 1100 millicores needed
  
  ❌ REJECTED: 1100 > 575 (not enough free CPU)

Scheduler checks Node B:
  - Free CPU: 5000 millicores (50% of one core)
  - Required: 1100 millicores (with safety margin)
  
  ❌ REJECTED: 1100 > 500 (not enough free CPU)

Scheduler checks Node C:
  - Free CPU: 8000 millicores (100% of one core)
  - Required: 1100 millicores
  
  ✅ ACCEPTED: 1100 < 8000 (plenty of headroom)
```

#### Step 7: Test Completes and Unregisters

When Test A finishes:

```
Test A completes → Unregisters from tracker

RunningTestTracker:
  Test B: CPU 50%, Memory 512 MB
  Test C: CPU 100%, Memory 2048 MB
  TOTAL: CPU 150%, Memory 2560 MB  ← Utilization decreased!
```

The next health poll will show lower utilization, and the scheduler can assign more tests.

### Visual Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    SCHEDULER (BuilderTask)                      │
│                                                                 │
│  1. Query Predictor: "How much will this test need?"           │
│     → Gets: CPU 75%, Memory 1024 MB, I/O 10 MB/s read, etc.   │
│                                                                 │
│  2. Build test request with "predicted" field                  │
│                                                                 │
│  3. Select best node (has enough free resources)               │
│     └─ Checks: free_cpu >= predicted_cpu × 1.10               │
│                free_mem >= predicted_mem × 1.10                │
│                                                                 │
│  4. Send HTTP request to tester                                │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ HTTP POST /runTest
                             │ { "testKey": "...", "predicted": {...} }
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                    TESTER NODE                                  │
│                                                                 │
│  1. TestHandler receives request                                │
│                                                                 │
│  2. TestOrchestrator:                                           │
│     ├─ Generate test ID: "test@commit@time@random"             │
│     ├─ Extract predicted demand from request                   │
│     └─ Register: runningTestTracker.registerTestStart(...)     │
│                                                                 │
│  3. Execute test (runs in background)                          │
│                                                                 │
│  4. When test completes:                                        │
│     └─ Unregister: runningTestTracker.unregisterTestEnd(...)  │
│                                                                 │
│  5. HealthHandler (polled every second):                        │
│     ├─ Query: testOrchestrator.getCurrentUtilization()        │
│     ├─ Sum all running test predictions                        │
│     └─ Return: { "utilization": {...}, "capacity": {...} }     │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ HTTP GET /health (every 1 second)
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                    SCHEDULER (NodeDirectory)                    │
│                                                                 │
│  1. Parse health response                                       │
│                                                                 │
│  2. Calculate free resources:                                  │
│     free = capacity - utilization                              │
│                                                                 │
│  3. Store in NodeSnapshot                                       │
│                                                                 │
│  4. Use for eligibility checks when scheduling                  │
└─────────────────────────────────────────────────────────────────┘
```

### Why This is Better

**Old Way Problems:**
- ❌ Assumed every test uses 50% CPU, 512 MB memory (wrong!)
- ❌ Some tests use 10% CPU, others use 200% CPU
- ❌ Scheduler either wastes capacity (too conservative) or crashes nodes (too aggressive)

**New Way Benefits:**
- ✅ Each test reports its actual predicted needs
- ✅ Utilization = sum of real predictions (accurate!)
- ✅ Scheduler can safely pack tests without overloading
- ✅ 10% safety margin prevents edge cases

### What About Tests Without Predictions?

**Backward Compatibility:**
- If a test request has no `predicted` field → use conservative defaults
- Defaults: 50% CPU, 512 MB memory, 10 MB/s I/O
- System still works, just less accurate for those tests

**Example:**
```
Test with prediction:    CPU 75%, Memory 1024 MB  ← Accurate
Test without prediction: CPU 50%, Memory 512 MB   ← Conservative default
```

### The RunningTestTracker: A Simple In-Memory Map

Think of it like a **whiteboard** that tracks who's working:

```
┌─────────────────────────────────────────────────────┐
│           RunningTestTracker (Whiteboard)           │
├─────────────────────────────────────────────────────┤
│ Test ID                    │ CPU  │ Memory │ I/O     │
├────────────────────────────┼──────┼────────┼─────────┤
│ test_a@abc123@...@x1      │ 75%  │ 1024MB │ 10MB/s  │
│ test_b@def456@...@x2      │ 50%  │ 512MB  │ 5MB/s   │
│ test_c@ghi789@...@x3      │ 100% │ 2048MB │ 20MB/s  │
├────────────────────────────┼──────┼────────┼─────────┤
│ TOTAL                      │ 225% │ 3584MB │ 35MB/s  │
└─────────────────────────────────────────────────────┘
```

When a test starts → **Add row** to whiteboard
When a test ends → **Remove row** from whiteboard
When asked "how busy?" → **Sum all rows**

### Multi-Layer Protection

The system has **4 layers** to prevent node overload:

1. **Concurrency Limit:** "Max 10 tests at once" (simple count)
2. **Resource Headroom:** "Do you have enough CPU/memory?" (accurate check)
3. **Accurate Utilization:** Real-time sum of predictions (not guesses)
4. **Stale Node Detection:** Remove unreachable nodes from scheduling

**Example:**
```
Node has capacity for 10 tests (concurrency limit)
But 8 tests are running, each using 15% CPU
Total: 8 × 15% = 120% CPU (overloaded!)

Scheduler sees:
  - Concurrency: 2 slots free (8/10) ✓
  - CPU headroom: 0% free (120% used) ✗
  
Result: Scheduler WON'T assign more tests (even though slots are free)
```

## Architecture Components

### 1. Request Protocol Enhancement

**Location:** Builder → Tester HTTP API

**Current Request Schema:**
```json
{
  "testKey": "sql/_01_object/_09_partition/...",
  "commit": "b3a0c2d1234...",
  "baseline": "2629095478965573179",
  "buildPackage": "cubrid_b3a0c2d.tar.gz",
  "runMode": "until-pass",
  "minRuns": 1,
  "maxRuns": 3
}
```

**Enhanced Request Schema (v2):**
```json
{
  "testKey": "sql/_01_object/_09_partition/...",
  "commit": "b3a0c2d1234...",
  "baseline": "2629095478965573179",
  "buildPackage": "cubrid_b3a0c2d.tar.gz",
  "runMode": "until-pass",
  "minRuns": 1,
  "maxRuns": 3,

  // NEW: Predicted resource demands
  "predicted": {
    "durationMs": 45000,
    "cpuPct": 75.5,
    "memMb": 1024.0,
    "ioMbPerSec": 15.2,              // Legacy: total I/O (backward compatible)
    "ioReadBytesPerSec": 10485760,   // NEW: read I/O in bytes/sec (10 MB/s)
    "ioWriteBytesPerSec": 5242880,   // NEW: write I/O in bytes/sec (5 MB/s)
    "iops": 300.0,
    "netMbPerSec": 8.5,
    "confidence": 0.85
  }
}
```

**Backward Compatibility:**
- `predicted` field is optional
- If absent, tester uses conservative defaults (current behavior)
- Allows gradual rollout

### 2. Tester-Side Demand Tracking ✅

**Components Implemented:**

1. **PredictedDemand.java** - `src/com/navercorp/cubridqa/builder/tester/demand/PredictedDemand.java`
   - Immutable representation of predicted resource demands
   - Static factory `fromRequest(JSONObject)` parses from test request
   - Static factory `conservative()` provides default values
   - Validates and sanitizes all values (prevents NaN, negative, unrealistic values)
   - Default values: 30s duration, 50% CPU, 512 MB memory, 10 MB/s I/O (split 50/50 read/write), 200 IOPS, 5 MB/s network
   - **I/O-first enhancement (Nov 2025):** Separate `ioReadBytesPerSec` and `ioWriteBytesPerSec` fields
   - Backward compatible: Legacy `ioMbPerSec` automatically splits 50/50 if read/write not provided

2. **UtilizationSnapshot.java** - `src/com/navercorp/cubridqa/builder/tester/demand/UtilizationSnapshot.java`
   - Immutable snapshot of current utilization
   - Contains totals for all resource dimensions
   - Tracks count of tests using default predictions
   - Static factory `empty()` for zero utilization

3. **RunningTestTracker.java** - `src/com/navercorp/cubridqa/builder/tester/demand/RunningTestTracker.java`
   - Thread-safe tracker using `ConcurrentHashMap<String, RunningTestInfo>`
   - `registerTestStart(testId, testKey, demand)` - Adds test to tracker
   - `unregisterTestEnd(testId)` - Removes test from tracker
   - `getCurrentUtilization()` - Returns sum of all running test demands (lock-free)
   - `getRunningTestCount()` - Returns current test count
   - Comprehensive logging at FINE and WARNING levels

**Thread Safety Implementation:**
- ✅ ConcurrentHashMap for lock-free concurrent reads
- ✅ Atomic put/remove operations for register/unregister
- ✅ Lock-free iteration for utilization calculation
- ✅ No global locks or synchronization needed

### 3. TestOrchestrator Integration ✅

**File:** `src/com/navercorp/cubridqa/builder/tester/TestOrchestrator.java`

**Implemented Changes:**

1. **Added RunningTestTracker field** (line 37)
   ```java
   private final RunningTestTracker runningTestTracker = new RunningTestTracker();
   ```

2. **Enhanced runTestWithRetry()** (lines 58-68)
   - Generates unique test ID: `{testKey}@{commit}@{timestamp}@{random}`
   - Extracts predicted demand: `PredictedDemand.fromRequest(request)`
   - Registers test on start: `runningTestTracker.registerTestStart(testId, testKey, demand)`

3. **Guaranteed cleanup** (lines 255-259)
   ```java
   } finally {
       runningTestTracker.unregisterTestEnd(testId);
       runningTestCount.decrementAndGet();
   }
   ```

4. **Exposed utilization API** (lines 557-559)
   ```java
   public UtilizationSnapshot getCurrentUtilization() {
       return runningTestTracker.getCurrentUtilization();
   }
   ```

5. **Test ID generation** (lines 569-580)
   - Format: `testKey@commitShort@timestamp@random`
   - Sanitizes slashes for file-system compatibility
   - Example: `sql_partition_test@b3a0c2d@1699901234567@a3f9`

### 4. HealthHandler Updates ✅

**File:** `src/com/navercorp/cubridqa/builder/tester/HealthHandler.java`

**Implemented Changes (lines 76-100):**

**Previous (Conservative Fixed Estimates):**
```java
utilization.put("cpu_pct", runningTests * 50.0);  // ❌ Inaccurate
utilization.put("mem_mb", runningTests * 512.0);  // ❌ Inaccurate
```

**Current (Actual Predicted Demands with I/O-First Enhancement):**
```java
if (testOrchestrator != null) {
    UtilizationSnapshot util = testOrchestrator.getCurrentUtilization();
    utilization.put("cpu_millicores", (long) util.getTotalCpuMillicores());
    utilization.put("mem_bytes", util.getTotalMemBytes());
    utilization.put("io_read_bytes_per_sec", util.getTotalIoReadBytesPerSec());  // NEW: separate read
    utilization.put("io_write_bytes_per_sec", util.getTotalIoWriteBytesPerSec()); // NEW: separate write
    utilization.put("iops", util.getTotalIops());
    utilization.put("net_bytes_per_sec", util.getTotalNetBytesPerSec());

    // Log if tests are using defaults
    if (util.hasDefaults()) {
        logger.log(Level.FINE, "{0}/{1} running tests using default predictions",
                new Object[]{util.getDefaultCount(), util.getTestCount()});
    }
}
```

**Capacity Reporting (I/O-First Enhancement):**
```java
JSONObject capacity = new JSONObject();
capacity.put("cpu_millicores", (int) (nodeCapacity.getCpuPct() / 100.0 * 1000.0));
capacity.put("mem_bytes", (long) (nodeCapacity.getMemMb() * 1024 * 1024));
capacity.put("io_read_bytes_per_sec", nodeCapacity.getIoReadCapacityBytesPerSec());  // NEW
capacity.put("io_write_bytes_per_sec", nodeCapacity.getIoWriteCapacityBytesPerSec()); // NEW
capacity.put("iops", (long) nodeCapacity.getIops());
capacity.put("net_bytes_per_sec", (long) (nodeCapacity.getNetMbPerSec() * 1024 * 1024));
```

**Safety Headroom Reporting (I/O-First Enhancement):**
```java
double ioReadCap = capacity.optDouble("io_read_bytes_per_sec", 0);
double ioWriteCap = capacity.optDouble("io_write_bytes_per_sec", 0);
double headroom = config.getIoSafetyHeadroomRatio(); // Default 15%
JSONObject safety = new JSONObject();
safety.put("io_read_keep_free", (long) (ioReadCap * headroom));
safety.put("io_write_keep_free", (long) (ioWriteCap * headroom));
response.put("safety_headroom", safety);
```

**Behavior:**
- ✅ No tests running → utilization = 0
- ✅ Tests with predictions → sum actual predicted demands
- ✅ Tests without predictions → sum conservative defaults (50% CPU, 512 MB)
- ✅ Mixed scenario → sum both types appropriately

### 5. BuilderTask Integration ✅

**File:** `src/com/navercorp/cubridqa/builder/BuilderTask.java`

**Implemented Changes:**

1. **Added testKey field to all requests** (line 1282)
   ```java
   .put("testKey", testPath)  // Required for test tracking
   ```

2. **Overloaded runTest() method** (lines 1216-1222)
   - Original: `runTest(commit, package, path, worker, baseline, type)`
   - New: `runTest(..., TestInstance testInstance)` - accepts predictions
   - Legacy calls use null for testInstance (backward compatible)

3. **Prediction injection** (lines 1298-1311)
   ```java
   if (testInstance != null && testInstance.getConfidence() > 0.0) {
       JSONObject predicted = new JSONObject()
           .put("durationMs", testInstance.getPredictedDurationMs())
           .put("cpuPct", testInstance.getPredictedCpuPct())
           .put("memMb", testInstance.getPredictedMemMb())
           .put("ioMbPerSec", testInstance.getPredictedIoMbPerSec())  // Legacy (backward compatible)
           .put("ioReadBytesPerSec", (long) (testInstance.getPredictedIoReadMbPerSec() * 1024 * 1024))  // NEW
           .put("ioWriteBytesPerSec", (long) (testInstance.getPredictedIoWriteMbPerSec() * 1024 * 1024)) // NEW
           .put("iops", testInstance.getPredictedIops())
           .put("netMbPerSec", testInstance.getPredictedNetMbPerSec())
           .put("confidence", testInstance.getConfidence());
       testRequest.put("predicted", predicted);
   }
   ```

4. **Smart scheduling integration** (line 2301)
   - Passes `assignment.getTest()` to runTest()
   - Automatically includes predictions for smart-scheduled tests

**Behavior:**
- ✅ Smart scheduling → sends predictions (if confidence > 0)
- ✅ Legacy scheduling → no predictions (tester uses defaults)
- ✅ Backward compatible with old testers (ignore unknown JSON fields)

### 6. NodeDirectory Resource Headroom Validation ✅

**File:** `src/com/navercorp/cubridqa/builder/scheduler/NodeDirectory.java`

**Implemented Changes (lines 117-171):**

The NodeDirectory now validates that nodes have sufficient free resources before scheduling tests, preventing oversubscription.

**getEligibleNodes() Enhancement:**
```java
public List<NodeSnapshot> getEligibleNodes(TestInstance test) {
    return getHealthyNodes().stream()
            .filter(s -> s.getAvailableConcurrency() > 0)
            .filter(s -> hasResourceHeadroom(s, test))  // NEW: Resource validation
            .collect(Collectors.toList());
}
```

**hasResourceHeadroom() Method:**
- Checks if node has sufficient free resources for test's predicted demands
- Applies 10% safety margin: `required = predicted × 1.10`
- Validates all resource dimensions:
  - CPU: `node.getFreeCpuPct() >= test.getPredictedCpuPct() × 1.10`
  - Memory: `node.getFreeMemMb() >= test.getPredictedMemMb() × 1.10`
  - I/O, IOPS, Network: similar validation
- For tests with no predictions (confidence = 0), allows scheduling based on concurrency only
- Logs detailed information when nodes lack headroom

**Multi-Layer Protection:**
1. **Concurrency Limit:** Max concurrent tests per node
2. **Resource Headroom:** Predicted demands + 10% safety margin
3. **Accurate Utilization:** Sum of running test predictions from /health endpoint
4. **Stale Node Detection:** Removes unreachable nodes from scheduling

This ensures that increasing `max_concurrent_tests` won't overwhelm nodes—the scheduler will stop assigning tests when resource headroom is exhausted, even if concurrency slots remain.

## Test ID Generation ✅

**Implementation:** `TestOrchestrator.generateTestId()` (lines 569-580)

**Format:**
```
{testKey_sanitized}@{commit_short}@{timestamp}@{random}
```

**Components:**
- `testKey_sanitized`: Test path with slashes replaced by underscores for file-system safety
- `commit_short`: First 7 characters of commit hash
- `timestamp`: Current time in milliseconds (ensures uniqueness)
- `random`: 4-character hex string for additional collision prevention

**Example:**
```
sql_partition_test_basic@b3a0c2d@1699901234567@a3f9
```

**Properties:**
- ✅ Unique across all concurrent executions (timestamp + random)
- ✅ Human-readable for debugging
- ✅ Contains test, commit, and timing information
- ✅ File-system safe (no special characters)

## Data Flow

### Scheduling & Execution Flow

```
1. BuilderTask (Scheduler)
   ├─ Query Predictor for test predictions
   ├─ Create TestInstance with predicted demands
   ├─ SchedulerService selects best node
   └─ Build test request with "predicted" field
        ↓
2. HTTP Request → Tester /runTest
        ↓
3. TestHandler → TestOrchestrator
   ├─ Extract predicted demand from request
   ├─ Generate unique test ID
   ├─ Register with RunningTestTracker
   ├─ Execute test (Direct/Docker/Optimized)
   └─ Unregister from RunningTestTracker (finally block)
```

### Health Reporting Flow

```
1. Scheduler: Poll /health on all tester nodes
        ↓
2. HealthHandler
   ├─ Query TestOrchestrator.getCurrentUtilization()
   │   └─ RunningTestTracker sums predicted demands
   ├─ Build response with actual utilization
   └─ Return JSON
        ↓
3. NodeDirectory
   ├─ Parse utilization from response
   ├─ Compute free resources (capacity - utilization)
   └─ Store in NodeSnapshot
        ↓
4. SchedulerService
   └─ Use NodeSnapshot.getFreeCpuPct() etc. for eligibility checks
```

## Error Handling

### Missing Predictions
- **Scenario:** Test request has no `predicted` field
- **Action:** Use conservative defaults
- **Log Level:** FINE (expected for legacy scheduling)

### Invalid Predictions
- **Scenario:** Negative values, NaN, or unrealistic demands
- **Action:** Reject and use conservative defaults
- **Log Level:** WARNING

### Tracker Inconsistency
- **Scenario:** Test ends but wasn't registered
- **Action:** Log warning, continue gracefully
- **Log Level:** WARNING

### Resource Leaks
- **Scenario:** Test crashes without calling unregister
- **Action:** Implement timeout-based cleanup (optional enhancement)
- **Mitigation:** Use try-finally blocks religiously

## Performance Considerations

### Memory Overhead
- **Per Running Test:** ~200 bytes (RunningTestInfo object)
- **Typical Load:** 100 concurrent tests = 20 KB
- **Impact:** Negligible

### CPU Overhead
- **Register/Unregister:** O(1) ConcurrentHashMap operations
- **Utilization Calculation:** O(n) sum over running tests
- **Health Polling:** 1 Hz → 100 tests = 100 iterations/sec
- **Impact:** Negligible (< 0.1% CPU)

### Locking
- ConcurrentHashMap provides lock-free reads
- Writes (register/unregister) use segment locks
- No global lock required

## Backward Compatibility

### Phase 1: Tester Updates
- Deploy RunningTestTracker
- Update TestOrchestrator to use tracker
- Update HealthHandler to report actual utilization
- **Impact:** Tester still accepts old requests (no `predicted` field)

### Phase 2: Scheduler Updates
- Update BuilderTask to send predictions
- **Impact:** Old testers ignore `predicted` field (unknown JSON keys ignored)

### Phase 3: Cleanup
- Remove conservative fallback logic after full rollout
- Add validation to require `predicted` field

## Testing Strategy

### Unit Tests
1. **RunningTestTracker:**
   - Register/unregister single test
   - Concurrent registrations
   - Utilization calculation accuracy
   - Empty tracker (zero utilization)

2. **PredictedDemand Parsing:**
   - Valid JSON with all fields
   - Missing optional fields (use defaults)
   - Invalid values (validation)

### Integration Tests
1. **End-to-End Flow:**
   - Send test request with predictions
   - Verify /health reports correct utilization during execution
   - Verify utilization returns to zero after completion

2. **Concurrent Tests:**
   - Run 10 tests in parallel
   - Verify utilization sums correctly
   - Verify all tests unregister properly

3. **Mixed Predictions:**
   - Run tests with and without predictions
   - Verify correct fallback behavior

### Manual Testing
1. **Single Test Execution:**
   - Check /health before, during, and after test
   - Verify utilization matches prediction

2. **Load Test:**
   - Run max_concurrent_tests tests
   - Verify node doesn't accept more tests (eligibility check)
   - Monitor actual resource usage vs predicted

## Monitoring & Observability

### Metrics to Track
- **Prediction Accuracy:** Actual utilization vs predicted
- **Tracker Size:** Number of registered tests over time
- **Fallback Rate:** % of tests using conservative defaults

### Logging
- Register: `FINE` - "Registered test {testId} with predicted CPU={cpuPct}%"
- Unregister: `FINE` - "Unregistered test {testId} after {duration}ms"
- Missing Prediction: `FINE` - "Test {testKey} has no predictions, using defaults"
- Tracker Inconsistency: `WARNING` - "Attempted to unregister unknown test {testId}"

## Future Enhancements

### Actual vs Predicted Tracking
- Compare actual observed usage (from stats) with predicted
- Feed back to Predictor for model improvement
- Detect systematic under/over-prediction

### Adaptive Defaults
- Learn conservative defaults from historical data
- Per-test-category defaults (e.g., SQL vs HA tests)

### Dynamic Safety Margins
- Adjust 10% safety margin based on prediction confidence
- Higher confidence → smaller margin
- Lower confidence → larger margin

### Timeout-Based Cleanup
- Detect tests that run longer than predicted
- Automatically unregister "zombie" entries
- Alert on stuck tests

## Implementation Status

### Core Components ✅
- [x] **PredictedDemand.java** - Immutable demand representation with validation
- [x] **UtilizationSnapshot.java** - Immutable utilization snapshot
- [x] **RunningTestTracker.java** - Thread-safe concurrent test tracking

### Integration Points ✅
- [x] **TestOrchestrator** - Tracker integration, test ID generation, lifecycle management
- [x] **HealthHandler** - Actual utilization reporting from tracker
- [x] **BuilderTask** - Prediction injection in test requests
- [x] **NodeDirectory** - Resource headroom validation with 10% safety margin

### Quality Assurance ✅
- [x] **Compilation** - All 82 Java files compile successfully
- [x] **Documentation** - Architecture doc updated with implementation details
- [ ] **Testing** - Manual and integration testing (NEXT STEP)

### Files Modified
- `src/com/navercorp/cubridqa/builder/tester/demand/PredictedDemand.java` (NEW)
- `src/com/navercorp/cubridqa/builder/tester/demand/UtilizationSnapshot.java` (NEW)
- `src/com/navercorp/cubridqa/builder/tester/demand/RunningTestTracker.java` (NEW)
- `src/com/navercorp/cubridqa/builder/tester/TestOrchestrator.java` (MODIFIED)
- `src/com/navercorp/cubridqa/builder/tester/HealthHandler.java` (MODIFIED)
- `src/com/navercorp/cubridqa/builder/BuilderTask.java` (MODIFIED)
- `src/com/navercorp/cubridqa/builder/scheduler/NodeDirectory.java` (MODIFIED)

---

**Document Version:** 2.0 (Implementation Complete)
**Last Updated:** 2025-11-11
**Status:** ✅ Implemented, Ready for Testing
**Author:** Smart Scheduling Implementation Team
