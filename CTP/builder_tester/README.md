# Builder-Tester System

A concurrent build and test system for CUBRID, designed to build multiple commits in parallel and run tests using Docker containers.

## Architecture

The system consists of two main services:

### Builder Service
- Receives requests to build a list of commits
- Builds multiple commits concurrently using Docker containers
- Uses `cubridci/cubridci:develop` Docker image for building
- Caches built packages to avoid rebuilding
- Sends built packages to Tester service

### Tester Service  
- Receives test requests with built packages
- Runs tests in isolated Docker containers
- Uses `cubridci/cubridci:test_shell` Docker image for testing
- Returns test results (pass/fail/error)

## Features

- **Concurrent Builds**: Build multiple commits in parallel (configurable max)
- **Docker Isolation**: Both building and testing run in Docker containers
- **Build Caching**: Avoid rebuilding commits that were already built
- **Error Recovery**: Continue with other commits even if one fails
- **Progress Tracking**: Check build progress via status endpoint
- **Pre-built Docker Images**: Uses official cubridci Docker images

## Requirements

- Java 8 or higher
- Docker
- CUBRID source repository
- Shell test cases repository
- GITHUB_TOKEN environment variable (for private repos)

## Installation

1. Clone the repository:
```bash
cd /Users/jun/cubrid-testtools/CTP/builder_tester
```

2. Compile the project:
```bash
./bin/compile.sh
```

3. Configure the services:
- Edit `conf/builder.conf` for Builder service
- Edit `conf/tester.conf` for Tester service

Key configuration options:
```properties
# Number of concurrent builds
max_concurrent_builds=4

# Docker images
docker_build_image=cubridci/cubridci:develop
docker_test_image=cubridci/cubridci:test_shell

# Source directories
cubrid_src_dir=~/cubrid
shell_tc_dir=~/cubrid-testcases-private-ex
```

## Usage

### Start the Services

1. Start the Tester service (on test machine):
```bash
export GITHUB_TOKEN=your_github_token
./bin/run_tester.sh
```

2. Start the Builder service:
```bash
export GITHUB_TOKEN=your_github_token
./bin/run_builder.sh
```

### Send Build Request

Use the test client or send HTTP POST request:

```bash
./bin/test_client.sh
```

Or manually:

```bash
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["commit1", "commit2", "commit3"],
    "tests": ["sql/test1.sh", "sql/test2.sh"],
    "callbackUrl": "http://your-server/callback",
    "workerIp": "localhost",
    "buildType": "debug"
  }'
```

### Check Status

```bash
# Check all active tasks
curl http://localhost:8089/status

# Check specific task
curl http://localhost:8089/status?taskId=YOUR_TASK_ID
```

### Health Check

```bash
# Builder health
curl http://localhost:8089/health

# Tester health
curl http://localhost:8090/health
```

## API Endpoints

### Builder Service (Port 8089)

#### POST /build
Submit build request for multiple commits.

Request:
```json
{
  "commits": ["commit1", "commit2"],
  "tests": ["test1.sh", "test2.sh"],
  "callbackUrl": "http://callback-url",
  "workerIp": "tester-ip",
  "buildType": "debug"
}
```

Response:
```json
{
  "status": "accepted",
  "taskId": "abc123",
  "message": "Build request received and processing"
}
```

#### GET /status
Check build progress.

#### GET /health
Service health check.

### Tester Service (Port 8090)

#### POST /test
Run test with provided build package.

Request:
```json
{
  "buildPackage": "/path/to/build.tar.gz",
  "testPath": "sql/test.sh",
  "testDir": "/path/to/test/dir",
  "testScript": "test.sh",
  "testName": "test",
  "expectedBuildVersion": "commit_hash"
}
```

Response:
```json
{
  "status": "pass|fail|error",
  "test": "test_name",
  "message": "optional message"
}
```

## Callback Format

When all builds and tests complete, the Builder sends results to the callback URL:

```json
{
  "taskId": "abc123",
  "results": [
    {
      "commit": "commit1",
      "test": "test1.sh",
      "status": "pass"
    },
    {
      "commit": "commit2",
      "test": "test1.sh",
      "status": "fail"
    }
  ],
  "timestamp": 1234567890
}
```

## Docker Configuration

The system uses pre-built Docker images by default:
- **Builder**: `cubridci/cubridci:develop`
- **Tester**: `cubridci/cubridci:test_shell`

To pull the images manually:
```bash
docker pull cubridci/cubridci:develop
docker pull cubridci/cubridci:test_shell
```

## Troubleshooting

### Docker not available
- Ensure Docker is installed and running
- Check Docker permissions: `docker ps`

### GITHUB_TOKEN not set
- Export the token: `export GITHUB_TOKEN=your_token`
- Required for accessing private repositories

### Build failures
- Check Builder logs for compilation errors
- Verify CUBRID source repository is accessible
- Ensure sufficient disk space for builds

### Test failures
- Check Tester logs for execution errors
- Verify test cases repository is accessible
- Ensure test environment is properly configured

## Development

### Project Structure
```
builder_tester/
├── src/com/navercorp/cubridqa/builder/
│   ├── Builder.java          # Main builder service
│   ├── BuilderTask.java      # Build task executor
│   ├── BuilderConfig.java    # Configuration
│   ├── Tester.java           # Tester service
│   ├── DockerBuildManager.java
│   ├── DockerTesterManager.java
│   └── DockerUtils.java
├── conf/
│   ├── builder.conf          # Builder configuration
│   └── tester.conf           # Tester configuration
├── bin/
│   ├── compile.sh            # Compilation script
│   ├── run_builder.sh        # Run builder
│   ├── run_tester.sh         # Run tester
│   └── test_client.sh        # Test client
└── lib/
    └── json.jar              # JSON library
```

### Key Differences from Bisect System

1. **No Git Bisect Logic**: Simplified to just build and test commits
2. **Concurrent Builds**: Multiple commits built in parallel
3. **Renamed Components**: Producer→Builder, Consumer→Tester
4. **Simplified Task**: No commit range validation or bisect operations
5. **Container Management**: Optimized for concurrent Docker operations
