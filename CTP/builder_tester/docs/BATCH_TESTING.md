# Batch Testing Implementation (Build-Based Distribution)

## Overview
The Builder-Tester system implements **build-based batch testing**, where each batch consists of:
- **One build** (from a single commit)
- **All test cases** to run against that build
- **One container** to execute all tests sequentially

This approach maximizes efficiency by running all tests for a build in a single container, minimizing setup overhead.

## Key Concept: Batch = One Build + All Tests

### Distribution Unit
- **NOT**: Individual tests distributed across workers
- **YES**: Complete builds (with all tests) distributed across workers

### Example
Request with 3 commits and 10 tests:
- **Batch 1**: Commit A build + all 10 tests → Worker 1
- **Batch 2**: Commit B build + all 10 tests → Worker 2  
- **Batch 3**: Commit C build + all 10 tests → Worker 1

Total: 3 batches, 30 test executions (3 commits × 10 tests)

## Distribution Strategy

### Localhost Priority
Workers are sorted to prioritize localhost:
```
Original: ["remote1", "localhost", "remote2"]
Sorted:   ["localhost", "remote1", "remote2"]
```

This minimizes network transfers since localhost can use the build file directly.

### Round-Robin Assignment
Commits are distributed round-robin across sorted workers:
```
4 commits, 2 workers (localhost + remote):
- Commit 1 → localhost (no transfer needed)
- Commit 2 → remote (HTTP download)
- Commit 3 → localhost (no transfer needed)
- Commit 4 → remote (HTTP download)
```

## Request Flow

### 1. Build Request
```json
{
  "commits": ["abc123", "def456", "ghi789"],
  "tests": ["test1.sh", "test2.sh", "test3.sh", "test4.sh", "test5.sh"],
  "workerIps": ["localhost", "192.168.1.10"],
  "buildType": "debug",
  "callbackUrl": "http://localhost:8089/callback"
}
```

### 2. Builder Processing
```
1. Build all 3 commits in parallel
2. Create 3 batches:
   - Batch 1: {commit: "abc123", build: "abc123.tar.gz", tests: [all 5 tests]}
   - Batch 2: {commit: "def456", build: "def456.tar.gz", tests: [all 5 tests]}
   - Batch 3: {commit: "ghi789", build: "ghi789.tar.gz", tests: [all 5 tests]}
3. Distribute batches:
   - Batch 1 → localhost
   - Batch 2 → 192.168.1.10
   - Batch 3 → localhost
```

### 3. Tester Execution
Each tester receives a complete batch and:
1. Downloads/accesses the build package
2. Starts ONE container
3. Installs CUBRID ONCE
4. Runs ALL tests sequentially
5. Returns results for all tests

## Performance Analysis

### Example: 4 commits, 20 tests, 2 workers

#### Container Usage
- **Total batches**: 4 (one per commit)
- **Containers needed**: 4 (one per batch)
- **Distribution**: 2 per worker

#### Execution Timeline
```
Worker 1 (localhost):
  Batch 1: Commit A + 20 tests → Container 1
  Batch 3: Commit C + 20 tests → Container 3

Worker 2 (remote):
  Batch 2: Commit B + 20 tests → Container 2
  Batch 4: Commit D + 20 tests → Container 4

Total time: ~2 batch durations (parallel execution)
```

#### Efficiency Gains
- **Setup cost**: 4 CUBRID installations (vs 80 with individual test containers)
- **Container overhead**: 4 containers (vs 80)
- **Network transfers**: 2 build packages (only for remote worker)

## Batch Request Format

### Request to Tester
```json
{
  "buildPackage": "/path/to/build.tar.gz",  // or HTTP URL for remote
  "tests": [
    "shell/_01_utility/test1.sh",
    "shell/_01_utility/test2.sh",
    "shell/_01_utility/test3.sh",
    // ... all tests for this build
  ],
  "commit": "abc123",
  "commitShort": "abc123",
  "expectedBuildVersion": "abc123",
  "batchMode": true,
  "keepAlive": false
}
```

### Response from Tester
```json
{
  "status": "completed",
  "commit": "abc123",
  "results": [
    {"test": "shell/_01_utility/test1.sh", "status": "pass", "message": ""},
    {"test": "shell/_01_utility/test2.sh", "status": "fail", "message": ""},
    {"test": "shell/_01_utility/test3.sh", "status": "pass", "message": ""},
    // ... results for all tests
  ]
}
```

## Configuration

### Builder (builder.conf)
```ini
max_concurrent_builds=4    # Parallel builds
```

### Tester (tester.conf)
```ini
max_concurrent_tests=4      # Max concurrent containers/batches
```

## Logging

### Builder Logs
```
[INFO] Building 3 commits, each will run 10 tests
[INFO] Total test executions: 30 (across 2 worker nodes)
[INFO] Worker priority order: [localhost, 192.168.1.10]
[INFO] Assigning batch: commit abc123 (10 tests) to worker localhost
[INFO] Assigning batch: commit def456 (10 tests) to worker 192.168.1.10
[INFO] Assigning batch: commit ghi789 (10 tests) to worker localhost
[INFO] Batch for commit abc123 using local build file on localhost
[INFO] Batch for commit def456 will download build from http://10.0.0.5:8089/download/build/def456.tar.gz
[INFO] Sending batch to localhost: commit abc123 with 10 tests
[INFO] Batch completed for commit abc123 on localhost: 10 test results
```

### Tester Logs
```
[INFO] Batch test request: 10 tests for commit abc123
[INFO] Running batch tests in Docker container
[INFO] Executing batch Docker command for 10 tests
[INFO] Test result for test1: OK
[INFO] Test result for test2: NOK
[INFO] Test result for test3: OK
// ... results for all 10 tests
[INFO] Batch test completed
```

## Docker Execution

### Generated Batch Script
```bash
#!/bin/bash
set -e

# Extract CUBRID build (ONCE)
echo "Extracting CUBRID build..."
mkdir -p /tmp/cubrid_install
tar -xzf /workspace/build.tar.gz -C /tmp/cubrid_install

# Setup environment (ONCE)
CUBRID_ROOT=$(find /tmp/cubrid_install -name "bin" -type d | head -1 | xargs dirname)
export CUBRID=$CUBRID_ROOT
export PATH=$CUBRID_ROOT/bin:$PATH
export LD_LIBRARY_PATH=$CUBRID_ROOT/lib:$LD_LIBRARY_PATH

# Run ALL tests for this build
echo "Running 10 tests..."

echo "[Test 1/10] Running test1..."
cd /testcases/shell/_01_utility/cases
bash test1.sh || true
cp test1.result /workspace/ 2>/dev/null || true

echo "[Test 2/10] Running test2..."
cd /testcases/shell/_01_utility/cases
bash test2.sh || true
cp test2.result /workspace/ 2>/dev/null || true

# ... continue for all 10 tests ...

echo "All tests completed"
```

## Comparison with Previous Implementation

### Wrong Implementation (Test-Level Distribution)
- Distributed individual tests across workers
- Mixed tests from different commits on same worker
- Created many small batches

### Correct Implementation (Build-Level Distribution)
- Distributes complete builds to workers
- Each worker handles all tests for assigned builds
- Creates one batch per build

## Best Practices

### 1. Worker Configuration
- **Localhost first**: Always prioritize to avoid transfers
- **Balanced workers**: Use similar-spec machines
- **Network proximity**: Place workers close to builder

### 2. Batch Size
- **All tests per build**: Don't split tests for a commit
- **One container per build**: Maximize reuse
- **Sequential execution**: Simple and reliable

### 3. Resource Planning
```
Workers needed = ceil(number_of_commits / desired_parallelism)
Containers total = number_of_commits
Containers per worker = commits / workers (rounded)
```

## Example Scenarios

### Scenario 1: CI Pipeline
```
10 commits from PR, 50 tests, 5 workers
- Each worker gets 2 commits
- Each commit runs all 50 tests in one container
- Total: 10 containers, 500 test executions
```

### Scenario 2: Nightly Build
```
1 commit, 1000 tests, 1 worker
- Single batch with all tests
- One container handles everything
- Sequential but efficient execution
```

### Scenario 3: Quick Validation
```
2 commits, 5 tests, 2 workers
- Each worker gets 1 commit
- Perfect parallelization
- Minimal overhead
```

## Summary

The build-based batch testing approach:
- ✅ **One container per build** (not per test)
- ✅ **All tests run together** for each build
- ✅ **Localhost priority** to minimize transfers
- ✅ **Efficient distribution** at build level
- ✅ **Aligns with CI patterns** (like CircleCI example)

This design matches real-world CI/CD practices where tests are run against specific builds, not scattered across different versions.
