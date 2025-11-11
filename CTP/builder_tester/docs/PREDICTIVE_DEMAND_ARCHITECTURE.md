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
    "ioMbPerSec": 15.2,
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
   - Default values: 30s duration, 50% CPU, 512 MB memory, 10 MB/s I/O, 200 IOPS, 5 MB/s network

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

**Current (Actual Predicted Demands):**
```java
if (testOrchestrator != null) {
    UtilizationSnapshot util = testOrchestrator.getCurrentUtilization();
    utilization.put("cpu_pct", util.getTotalCpuPct());
    utilization.put("mem_mb", util.getTotalMemMb());
    utilization.put("io_mb_s", util.getTotalIoMbPerSec());
    utilization.put("iops", util.getTotalIops());
    utilization.put("net_mb_s", util.getTotalNetMbPerSec());

    // Log if tests are using defaults
    if (util.hasDefaults()) {
        logger.log(Level.FINE, "{0}/{1} running tests using default predictions",
                new Object[]{util.getDefaultCount(), util.getTestCount()});
    }
}
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
           .put("ioMbPerSec", testInstance.getPredictedIoMbPerSec())
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
