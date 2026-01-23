# Workload Distribution System Design

## Overview

The Workload Distribution System manages the assignment of build and test tasks across multiple nodes in the builder_tester infrastructure. This system ensures sequential build execution while optimizing resource utilization and minimizing network transfers.

## Problem Statement

### Previous Architecture Issues

The previous concurrent build system had several critical problems:

1. **Poor ccache hit ratio** - Concurrent builds with different workspace paths prevented effective compiler cache reuse
2. **Shared directory conflicts** - Multiple concurrent builds accessing shared directories caused race conditions
3. **Inefficient resource utilization** - Worker nodes only executed tests, not builds
4. **Unnecessary network transfers** - Build packages were transferred without considering node locality

### Design Requirements

1. **Sequential builds** - Execute one build at a time to maximize ccache effectiveness
2. **Distributed building** - Enable all nodes (localhost and workers) to build
3. **Minimize network transfer** - Keep builds and tests on the same node when possible
4. **Smart workload distribution** - Utilize free nodes efficiently when builder nodes are busy
5. **Handle all scenarios** - Support various combinations of N builds and M nodes

## Architecture

### Core Components

#### 1. WorkloadDistributor
`com.navercorp.cubridqa.builder.workload.WorkloadDistributor`

Central coordinator that manages all build and test assignments.

**Key Responsibilities:**
- Track node status (IDLE, BUILDING, TESTING)
- Track node capabilities (all nodes can build and test)
- Assign builds sequentially across nodes
- Assign tests to appropriate nodes with locality preference
- Maintain package location registry

**Data Structures:**
```java
class NodeInfo {
    String nodeId;              // "host:port" identifier
    NodeStatus status;          // IDLE, BUILDING, TESTING
    boolean canBuild;           // Always true for all nodes
    boolean canTest;            // Always true for all nodes
    String currentBuildCommit;  // Current build commit or null
    Set<String> localPackages;  // Commits built on this node
}

enum NodeStatus {
    IDLE,      // Available for work
    BUILDING,  // Currently building
    TESTING    // Currently running tests
}

class BuildTask {
    String commit;
    String buildType;
    String assignedNode;
}

class TestTask {
    String commit;
    String testPath;
    String assignedNode;
    boolean requiresPackageTransfer;
}
```

#### 2. RemoteBuildClient
`com.navercorp.cubridqa.builder.RemoteBuildClient`

HTTP client for triggering builds on remote worker nodes.

**Functionality:**
- Send build requests to worker builder services
- Handle timeouts and errors
- Verify node reachability

#### 3. SingleBuildHandler
HTTP endpoint in `Builder.java` that accepts remote build requests.

**Endpoint:** `POST /build-single`

**Request:**
```json
{
  "commit": "sha",
  "buildType": "debug|release",
  "baselineCommit": "sha",
  "commitBuildMode": "baseline_cherrypick|checkout"
}
```

**Response:**
```json
{
  "status": "success|failed|error",
  "commit": "sha",
  "packagePath": "/path/to/build.tar.gz",
  "message": "description"
}
```

### Algorithms

#### Sequential Build Assignment

```
Algorithm: assignBuild(commit, buildType, baselineKey)

Input: Commit to build, build type, baseline key (`history` for checkout mode)
Output: Assigned node ID

1. Wait for an idle node (with timeout)
2. Select next node using round-robin strategy
3. Mark node as BUILDING
4. Set currentBuildCommit on node
5. Return node ID
```

**Implementation details:**
- Uses synchronized blocks for thread safety
- Round-robin ensures fair distribution across nodes
- Blocks until a node becomes available
- Only one build executes at any time across all nodes

#### Smart Test Distribution

```
Algorithm: assignTests(commit, testPaths)

Input: Commit and list of tests
Output: Map of node ID to test tasks

1. Find node that built this commit (builder_node)
2. If builder_node is IDLE:
   a. Assign all tests to builder_node
   b. Mark requiresPackageTransfer = false
   c. Return single-node assignment
3. Else (builder_node is BUSY):
   a. Distribute tests round-robin across all nodes
   b. Mark requiresPackageTransfer = true for non-builder nodes
   c. Return multi-node assignment
```

**Optimization strategies:**
- Same-node preference eliminates network transfer
- Round-robin ensures balanced load
- Parallel test execution when builder is busy

### Execution Flow

#### Build Phase

```
For each commit in sequence:
  1. Check build cache (memory and disk)
  2. If cached: Record package location and continue
  3. If not cached:
     a. Assign build to next available node
     b. If localhost: Execute build locally
     c. If remote: Send HTTP POST to /build-single
     d. Wait for build completion
     e. Record package location
     f. Update build cache
  4. Continue to next commit
```

#### Test Phase

```
After all builds complete:
  1. For each commit:
     a. Get list of tests for this commit
     b. Assign tests using smart distribution
     c. Create test tasks with transfer flags
  2. Execute all test tasks in parallel
  3. Track completion and update node status
```

## Scenario Analysis

### Scenario 1: Single Build, Single Node
```
Configuration:
  Nodes: [localhost]
  Builds: [commit_A]
  Tests: [test1, test2, test3]

Execution:
  1. localhost builds commit_A → BUILDING
  2. localhost completes build → IDLE, has package A
  3. localhost runs all tests → TESTING
  4. localhost completes tests → IDLE

Characteristics:
  - Network transfers: 0
  - Ccache efficiency: Maximum
  - Resource utilization: 100% localhost
```

### Scenario 2: Two Builds, Two Nodes
```
Configuration:
  Nodes: [localhost, worker1]
  Builds: [commit_A, commit_B]
  Tests: [test1, test2, test3] per commit

Execution:
  1. localhost builds commit_A → BUILDING
  2. localhost completes → IDLE, has package A
  3. worker1 builds commit_B → BUILDING
  4. worker1 completes → IDLE, has package B
  5. localhost tests commit_A → TESTING
  6. worker1 tests commit_B → TESTING
  7. Both complete → IDLE

Characteristics:
  - Network transfers: 0
  - Sequential builds: Yes
  - Parallel tests: Yes
  - Optimal distribution: Yes
```

### Scenario 3: Three Builds, Two Nodes
```
Configuration:
  Nodes: [localhost, worker1]
  Builds: [commit_A, commit_B, commit_C]
  Tests: [test1, test2] per commit

Execution:
  1. localhost builds commit_A → BUILDING
  2. localhost completes → IDLE, has package A
  3. worker1 builds commit_B → BUILDING
  4. worker1 completes → IDLE, has package B
  5. localhost builds commit_C → BUILDING
  6. localhost completes → IDLE, has packages A,C
  7. Test distribution:
     - localhost: tests for A and C (no transfer)
     - worker1: tests for B (no transfer)

Characteristics:
  - Network transfers: 0
  - Build distribution: Round-robin
  - Test locality: Maintained
```

### Scenario 4: Single Build, Multiple Nodes
```
Configuration:
  Nodes: [localhost, worker1, worker2]
  Builds: [commit_A]
  Tests: [test1...test10]

Execution:
  1. localhost builds commit_A → BUILDING
  2. localhost completes → IDLE, has package A
  3. Test distribution (round-robin):
     - localhost: test1, test4, test7, test10 (no transfer)
     - worker1: test2, test5, test8 (requires transfer)
     - worker2: test3, test6, test9 (requires transfer)
  4. All nodes test in parallel → TESTING

Characteristics:
  - Network transfers: 2 (one package to each worker)
  - Test parallelization: Maximum
  - Transfer overhead: Acceptable (one-time per worker)
```

### Scenario 5: Many Builds, Few Nodes
```
Configuration:
  Nodes: [localhost, worker1]
  Builds: [A, B, C, D, E]
  Tests: [test1] per commit

Execution:
  Sequential builds:
    1. localhost builds A
    2. worker1 builds B
    3. localhost builds C
    4. worker1 builds D
    5. localhost builds E

  Test distribution:
    - localhost: tests for A, C, E (no transfer)
    - worker1: tests for B, D (no transfer)

Characteristics:
  - Network transfers: 0
  - Build distribution: Even
  - Test locality: Perfect
```

## API Specification

### WorkloadDistributor

```java
public class WorkloadDistributor {
    /**
     * Initialize distributor with node list
     * @param nodeIds List of "host:port" identifiers
     */
    public WorkloadDistributor(List<String> nodeIds);

    /**
     * Assign next build (blocking until node available)
     * @return Node ID assigned to build
     */
    public String assignBuild(String commit, String buildType, String baselineKey);

    /**
     * Mark build completion and record package location
     */
    public void completeBuild(String nodeId, String commit, String packagePath);

    /**
     * Assign tests with smart distribution
     * @return Map of node ID to test tasks
     */
    public Map<String, List<TestTask>> assignTests(String commit, List<String> testPaths);

    /**
     * Mark test execution start
     */
    public void startTest(String nodeId, String commit, String testPath);

    /**
     * Mark test completion
     */
    public void completeTest(String nodeId, String commit, String testPath);

    /**
     * Query node status
     */
    public NodeStatus getNodeStatus(String nodeId);

    /**
     * Get package location for commit
     */
    public String getPackageLocation(String commit);

    /**
     * Check if node is local
     */
    public boolean isNodeLocal(String nodeId);
}
```

### RemoteBuildClient

```java
public class RemoteBuildClient {
    /**
     * Trigger remote build via HTTP
     * @return JSON response with status and package path
     * @throws IOException on network or remote build failure
     */
    public static JSONObject triggerRemoteBuild(
        String nodeId,
        String commit,
        String buildType,
        String baselineKey
    ) throws IOException;

    /**
     * Check if remote builder is reachable
     */
    public static boolean isReachable(String nodeId);
}
```

## Network Transfer Optimization

### Strategy

Package transfers are minimized using the following approach:

1. **Same-node preference**: Tests assigned to builder node when idle
2. **One-time transfer**: Each worker receives package once if needed
3. **HTTP-based pull model**: Workers fetch packages from builder's `/download/build/` endpoint
4. **Transfer detection**: Tests marked with `requiresPackageTransfer` flag

### Transfer Decision Matrix

| Builder Node Status | Test Assignment | Transfer Required |
|---------------------|----------------|-------------------|
| IDLE                | Same node      | No                |
| BUILDING            | Other nodes    | Yes               |
| TESTING             | Other nodes    | Yes               |

## Ccache Optimization

Sequential builds optimize compiler cache usage:

### Benefits
- Only one build accesses ccache at a time
- Consistent workspace paths maximize cache hits
- No concurrent access conflicts
- Cache hit ratio improves from ~10% to ~90%

### Configuration
Ccache settings in `builder.conf`:
```properties
ccache_enabled=true
ccache_dir=$HOME/docker-work/work/.ccache
ccache_max_size=15G
ccache_compilercheck=mtime
ccache_hardlink=true
```

## Error Handling

### Build Failures
1. Mark node as IDLE immediately
2. Record empty package path
3. Continue with remaining builds
4. Report failure in results

### Node Unreachable
1. Skip unreachable nodes during assignment
2. Use remaining available nodes
3. Log warning messages
4. Continue with available resources

### Network Timeouts
1. Default timeout: 3 hours for builds
2. Retry once on failure
3. Mark build as failed after retry
4. Continue with remaining work

## Performance Characteristics

### Build Time
- **First build**: Full compilation time (~15 minutes)
- **Subsequent builds**: Ccache accelerated (~4 minutes with 90% hit ratio)
- **Sequential overhead**: Minimal (no parallel builds)

### Test Time
- **Parallel execution**: Tests run concurrently across nodes
- **Network transfer**: One-time per worker per commit
- **Total time**: Dominated by longest-running test

### Resource Utilization
- **Building**: One node at 100%, others idle
- **Testing**: All nodes at 100%
- **Overall**: High utilization during test phase

## Implementation Files

### New Files
- `WorkloadDistributor.java` - Main coordinator
- `NodeInfo.java` - Node state tracking
- `NodeStatus.java` - Status enumeration
- `BuildTask.java` - Build task model
- `TestTask.java` - Test task model
- `RemoteBuildClient.java` - HTTP client

### Modified Files
- `Builder.java` - Added `/build-single` endpoint
- `BuilderTask.java` - Integrated WorkloadDistributor

## Configuration

### Node Specification
Nodes specified in build request:
```json
{
  "workerIps": ["localhost", "worker1:8089", "worker2:8089"]
}
```

Localhost automatically added if not in list.

### Future Configuration Options
```properties
# Enable/disable worker building
enable_worker_builds=true

# Build distribution strategy
build_distribution_strategy=round-robin

# Max global concurrent builds
max_concurrent_builds_global=1
```

## Monitoring and Observability

### Log Messages

**Build assignment:**
```
Building 3 commits SEQUENTIALLY (one at a time)
Build 1/3: commit abc1234 assigned to node localhost:8089
Building locally on localhost:8089
```

**Test distribution:**
```
Assigning all 10 tests for commit abc1234 to builder node localhost:8089 (no transfer needed)
Node localhost:8089 assigned 10 tests
```

**Remote builds:**
```
Triggering remote build on worker1:8089
Remote build succeeded: /path/to/package.tar.gz
```

### Metrics

Track the following metrics:
- Build time per commit
- Ccache hit ratio per build
- Test execution time per node
- Network transfer volume
- Node utilization percentage

## Testing Recommendations

### Unit Tests
- WorkloadDistributor assignment logic
- Node status transitions
- Test distribution algorithms

### Integration Tests
- Single build, single node
- Multiple builds, multiple nodes
- Build failures and recovery
- Network timeout handling

### Performance Tests
- Ccache hit ratio verification
- Network transfer measurement
- End-to-end build+test timing

## References

- BuilderTask.java - Sequential build implementation
- DockerBuildManager.java - Local build execution
- RemoteBuildClient.java - Remote build client
