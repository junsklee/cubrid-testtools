# Bisect Tool Architecture

## Overview

The CUBRID Bisect Tool is a distributed system for automatically finding the first commit that causes test failures using git bisect. It consists of Java-based producer and consumer services that communicate via HTTP.

## System Components

```
┌─────────────────┐         ┌─────────────────────┐         ┌──────────────────┐
│                 │         │                     │         │                  │
│  QAHome/Client  │────────▶│   Producer Node     │────────▶│  Consumer Node   │
│                 │  HTTP   │                     │  HTTP   │                  │
│                 │         │ - Git Repository    │         │ - Test Executor  │
│                 │         │ - Build Environment │         │ - CUBRID Runtime │
│                 │         │ - Bisect Logic      │         │                  │
│                 │         │                     │         │                  │
└─────────────────┘         └─────────────────────┘         └──────────────────┘
        ▲                             │
        │                             │
        └─────────────────────────────┘
              HTTP Callback
```

## Service Architecture

### Producer Service (BisectProducer.java)
- **Purpose**: Coordinates the bisect process
- **Port**: 8089 (configurable)
- **Endpoints**:
  - `POST /bisect` - Receives bisect requests
  - `GET /health` - Health check endpoint
- **Responsibilities**:
  - Manages git bisect operations
  - Builds CUBRID at each commit
  - Sends test requests to consumers
  - Aggregates results and sends callbacks

### Consumer Service (BisectConsumer.java)
- **Purpose**: Executes tests with provided builds
- **Port**: 8090 (configurable)
- **Endpoints**:
  - `POST /test` - Receives test execution requests
  - `GET /health` - Health check endpoint
- **Responsibilities**:
  - Extracts and installs CUBRID builds
  - Executes shell tests
  - Returns pass/fail results

## Request Flow

### 1. Client → Producer: Bisect Request
```json
{
  "suspectedStartCommit": "e4c8127",
  "suspectedEndCommit": "bb2cc88",
  "buildType": "debug",
  "workerIp": "10.0.0.2",
  "tests": ["shell/test1.sh", "shell/test2.sh"],
  "callbackUrl": "http://client/result",
  "autoDeleteBuilds": true
}
```

### 2. Producer Processing
- Finds parent of `suspectedStartCommit` as the "good" commit
- Starts git bisect between good and bad commits
- For each bisect step:
  - Builds CUBRID
  - Creates build package
  - Sends to consumer for testing

### 3. Producer → Consumer: Test Request
```json
{
  "buildPackage": "/path/to/build.tar.gz",
  "testPath": "shell/test1.sh",
  "testDir": "/path/to/tests",
  "testScript": "test1.sh",
  "testName": "test1"
}
```

### 4. Consumer → Producer: Test Result
```json
{
  "status": "pass|fail|error",
  "test": "test1",
  "message": "optional error message"
}
```

### 5. Producer → Client: Callback with Results
```json
{
  "suspectedStartCommit": "e4c8127",
  "suspectedEndCommit": "bb2cc88",
  "workerIp": "10.0.0.2",
  "generatedAt": "2025-08-05T10:30:00Z",
  "tests": [{
    "name": "shell/test1.sh",
    "status": "found|error",
    "firstBadCommit": "xyz789",
    "author": "developer@cubrid.com",
    "runtimeMs": 180000
  }]
}
```

## Key Components

### BisectTask.java
- Executes individual bisect operations
- Creates judge scripts for git bisect
- Handles build packaging and distribution
- Manages test result aggregation

### BisectConfig.java
- Handles configuration file parsing
- Provides default values
- Validates required settings

## Design Decisions

1. **Direct HTTP Communication**: No message queue dependencies for simplicity
2. **Stateless Consumer**: Can scale horizontally by adding more consumer nodes
3. **Producer Coordination**: Single producer manages git bisect state
4. **Async Processing**: Client gets immediate acknowledgment while bisect runs
5. **Build Once Per Commit**: Producer builds once and distributes to consumers
6. **Variable Build Cleanup**: Optional preservation of build artifacts for debugging

## Error Handling

- **Build Failures**: Logged and reported as errors in results
- **Test Timeouts**: 30-minute timeout per test execution
- **Network Failures**: Retries and fallbacks to error status
- **Git Issues**: Automatic cleanup and reset on failures

## Performance Considerations

- Binary search minimizes builds: log₂(N) for N commits
- Parallel test execution across multiple consumer nodes
- Build caching potential (not currently implemented)
- Configurable concurrent bisect operations

## Security Considerations

- No authentication (assumes trusted network)
- Consumer validates build package paths
- Temporary directories cleaned after use
- Process isolation for test execution
