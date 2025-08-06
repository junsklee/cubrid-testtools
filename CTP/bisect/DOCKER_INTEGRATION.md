# Docker Integration for CUBRID Bisect Tool

## Overview

The bisect tool now supports Docker-based builds for better isolation and consistency across different environments. This ensures that CUBRID builds are performed in a controlled environment regardless of the host system configuration.

## Architecture

### Two Docker Images

1. **cubrid-bisect-builder:latest** (from cubridci develop branch)
   - Based on CentOS 6 with devtoolset-8
   - Used by the Producer for building CUBRID
   - Includes all build dependencies (gcc, cmake, ninja, etc.)

2. **cubrid-bisect-tester:latest** (from cubridci test_shell branch)  
   - Based on Rocky Linux 8
   - Used by the Consumer for testing CUBRID (optional)
   - Includes test framework and shell test dependencies

## Setup

### Prerequisites
- Docker installed and running
- Access to GitHub for cloning cubridci repository

### Initial Setup

Run the setup script to build Docker images:

```bash
./script/setup_docker.sh
```

This script will:
1. Clone/update the cubridci repository to ~/cubridci
2. Build the builder image from the develop branch
3. Build the tester image from the test_shell branch

## Configuration

### Producer Configuration

Edit `conf/bisect_producer.conf`:

```properties
# Enable Docker-based builds
use_docker=true

# Docker image names (built by setup_docker.sh)
docker_build_image=cubrid-bisect-builder:latest
```

### Consumer Configuration (Optional)

Edit `conf/bisect_consumer.conf`:

```properties
# Enable Docker-based test execution (optional)
use_docker=false

# Docker test image (if use_docker=true)
docker_test_image=cubrid-bisect-tester:latest
```

## How It Works

### Producer (Docker Build)

When Docker is enabled, the producer:

1. Checks out the commit to test in the CUBRID source repository
2. Creates a Docker container from the builder image
3. Mounts the source code as read-only
4. Builds CUBRID inside the container
5. Outputs the build package to the host filesystem
6. Sends the build package to the consumer for testing

### Consumer (Optional Docker Testing)

The consumer can optionally use Docker for test execution:
- Currently, the consumer works fine without Docker
- Docker support for consumer is available but not required

## Manual Docker Build

You can manually build a specific commit using Docker:

```bash
./script/docker_build.sh <commit_hash> <output_dir> [build_args]

# Example:
./script/docker_build.sh abc123def /tmp/builds "-g ninja -m debug build"
```

## Advantages

1. **Consistency**: Same build environment regardless of host OS
2. **Isolation**: No interference with host system libraries
3. **Reproducibility**: Builds are reproducible across different machines
4. **Clean Environment**: Each build starts with a clean environment
5. **Version Control**: Docker images can be versioned and updated independently

## Fallback Mode

If Docker is not available or disabled:
- Producer falls back to direct builds on the host
- Consumer continues to work as before
- No functionality is lost, just less isolation

## Troubleshooting

### Docker Not Available
- Ensure Docker daemon is running: `docker version`
- Check Docker permissions: `docker ps`

### Image Build Failures
- Ensure cubridci repository is accessible
- Check network connectivity to Docker Hub
- Verify sufficient disk space for Docker images

### Build Failures in Container
- Check build logs in the work directory
- Ensure source code is compatible with CentOS 6 environment
- Verify all submodules are properly initialized

## Performance Considerations

- Docker builds may be slightly slower due to container overhead
- First build pulls base images (one-time cost)
- Subsequent builds use cached images
- Build artifacts are stored on host filesystem for fast access

## Future Enhancements

- Support for custom Docker images
- Parallel builds in multiple containers
- Docker Compose for multi-node setups
- Container orchestration for large-scale bisecting
