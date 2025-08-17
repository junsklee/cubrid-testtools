# Builder-Tester System - Final Implementation

## Core Design: Build-Based Batch Testing

### Batch Definition
**Each batch = One build (commit) + ALL test cases**

This is the fundamental unit of distribution. Tests are never split across workers for the same build.

## Architecture

```
Request: 3 commits × 10 tests = 30 total test executions

Builder:
  ├── Build commit A → build_a.tar.gz
  ├── Build commit B → build_b.tar.gz
  └── Build commit C → build_c.tar.gz

Distribution (2 workers):
  ├── Batch 1: {build_a.tar.gz + all 10 tests} → Worker 1 (localhost)
  ├── Batch 2: {build_b.tar.gz + all 10 tests} → Worker 2 (remote)
  └── Batch 3: {build_c.tar.gz + all 10 tests} → Worker 1 (localhost)

Execution:
  Worker 1: 2 containers (one per batch), 20 test executions
  Worker 2: 1 container, 10 test executions
```

## Key Implementation Details

### 1. Distribution Strategy
- **Unit**: Complete builds (not individual tests)
- **Priority**: Localhost first (minimize network transfers)
- **Method**: Round-robin across prioritized workers

### 2. Worker Priority
```java
// Sort workers: localhost first
List<String> sortedWorkers = new ArrayList<>();
List<String> remoteWorkers = new ArrayList<>();
for (String worker : workerIps) {
    if (isLocalTester(worker)) {
        sortedWorkers.add(worker); // Localhost first
    } else {
        remoteWorkers.add(worker);
    }
}
sortedWorkers.addAll(remoteWorkers); // Remote workers after
```

### 3. Batch Assignment
```java
// Each commit becomes one batch with ALL tests
for (String commit : commits) {
    String assignedWorker = sortedWorkers.get(workerIndex % sortedWorkers.size());
    // Send entire batch (commit + all tests) to assignedWorker
    workerIndex++;
}
```

## Request/Response Format

### Build Request
```json
{
  "commits": ["abc123", "def456"],
  "tests": ["test1.sh", "test2.sh", "test3.sh"],
  "workerIps": ["localhost", "192.168.1.10"],
  "buildType": "debug"
}
```

### Batch Distribution
```
Batch 1: commit abc123 + all 3 tests → localhost
Batch 2: commit def456 + all 3 tests → 192.168.1.10
```

### Batch Test Request (to Tester)
```json
{
  "buildPackage": "/path/to/abc123.tar.gz",
  "tests": ["test1.sh", "test2.sh", "test3.sh"],
  "commit": "abc123",
  "batchMode": true
}
```

## Performance Characteristics

### Example: 10 commits, 50 tests, 3 workers

#### Distribution
- Worker 1 (localhost): 4 batches
- Worker 2 (remote): 3 batches
- Worker 3 (remote): 3 batches

#### Resources
- **Containers**: 10 total (one per commit)
- **CUBRID installations**: 10 (one per container)
- **Network transfers**: 6 (only for remote workers)
- **Test executions**: 500 (10 × 50)

#### Timeline
- Parallel execution across 3 workers
- Approximate time: (10 batches / 3 workers) × batch_duration

## Implementation Files

### Modified Files
1. **BuilderTask.java**
   - Removed individual test distribution
   - Implemented build-based batch distribution
   - Localhost prioritization logic
   - `runBatchTests()` handles complete builds

2. **Builder.java**
   - HTTP endpoint for build package downloads
   - Support for multiple worker IPs
   - Reachability validation for all workers

3. **Tester.java**
   - Batch test endpoint (`/batch-test`)
   - Single container per batch
   - Sequential test execution within batch
   - Build package caching

## Removed/Deprecated Code
- `runTest()` method in BuilderTask.java (individual test execution)
- Test-level distribution logic
- Per-test round-robin assignment

## Configuration

### Builder Configuration
```ini
max_concurrent_builds=4    # Parallel build capacity
```

### Tester Configuration
```ini
max_concurrent_tests=4     # Actually: max concurrent batches/containers
```

## Usage Examples

### Example 1: Balanced Distribution
```bash
# 4 commits, 2 workers
# Result: 2 batches per worker
./bin/test_batch_distribution.sh
```

### Example 2: Localhost Priority
```bash
# 3 commits, 2 workers (one localhost)
# Result: localhost gets 2 batches, remote gets 1
```

## Monitoring

### Builder Logs
```
[INFO] Building 3 commits, each will run 10 tests
[INFO] Worker priority order: [localhost, 192.168.1.10]
[INFO] Assigning batch: commit abc123 (10 tests) to worker localhost
[INFO] Batch for commit abc123 using local build file on localhost
[INFO] Sending batch to localhost: commit abc123 with 10 tests
```

## Design Rationale

### Why Build-Based Distribution?
1. **Consistency**: All tests run against the same build
2. **Efficiency**: One CUBRID installation per build
3. **Simplicity**: Clear distribution unit
4. **Alignment**: Matches CI/CD patterns (CircleCI example)

### Why Localhost Priority?
1. **No network transfer**: Direct file access
2. **Faster startup**: No download time
3. **Resource efficiency**: Reduced network load
4. **Reliability**: No network failures

## Comparison with Incorrect Approaches

### ❌ Wrong: Test-Level Distribution
```
Test 1 from Commit A → Worker 1
Test 2 from Commit A → Worker 2  // Same build on different workers!
Test 3 from Commit A → Worker 1
```
Problems: Multiple containers for same build, redundant installations

### ✅ Correct: Build-Level Distribution
```
All tests from Commit A → Worker 1  // One container, one installation
All tests from Commit B → Worker 2  // Clean separation
```

## Best Practices

1. **Use localhost when available**: Always include localhost in workers
2. **Balance worker count**: Workers ≈ number of commits for optimal distribution
3. **Monitor distribution**: Check logs for batch assignments
4. **Plan capacity**: Each commit needs one container

## Summary

The final implementation provides:
- **Build-based batching**: One container per commit
- **Efficient distribution**: Localhost priority
- **Clean architecture**: Clear separation of concerns
- **Production ready**: Handles real-world testing scenarios

This design aligns with modern CI/CD practices where tests are run as complete suites against specific builds, not scattered across different versions.
