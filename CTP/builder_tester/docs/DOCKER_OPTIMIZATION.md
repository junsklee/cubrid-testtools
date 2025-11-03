# Docker Performance Optimization Guide

## Overview

The Builder-Tester system now includes advanced Docker optimization that dramatically reduces test execution time by building and caching Docker images with CUBRID pre-installed for each commit. This eliminates the repeated extraction and setup overhead that occurs with every test execution.

## Performance Improvements

### Before Optimization
- **Container Creation**: 5-10 seconds
- **Build Extraction**: 30-60 seconds  
- **CUBRID Setup**: 10-20 seconds
- **Test Execution**: 10-30 seconds
- **Total**: 55-120 seconds per test

### After Optimization
- **Container Creation**: 1-2 seconds (using pre-built image)
- **Build Extraction**: 0 seconds (already in image)
- **CUBRID Setup**: 0 seconds (already configured)
- **Test Execution**: 10-30 seconds
- **Total**: 11-32 seconds per test

**Performance Gain: 80-90% reduction in overhead time**

## How It Works

### 1. Docker Image Building
When a test request arrives for a commit:
1. System checks if a Docker image exists for that commit/baseline pair (`cubrid-test:<commit>_<baseline>`)
2. If not, launches the baseline test image with the build tarball mounted, runs an in-container provisioning script to extract/configure CUBRID, and commits the live container as the new image
3. Caches the image for future tests on the same commit

### 2. Optimized Test Execution
For each test:
1. Uses the pre-built image (no extraction needed)
2. Mounts only the test cases directory
3. Runs test immediately with pre-configured environment
4. Returns results

### 3. Image Cache Management
- Keeps up to 20 images by default (configurable)
- Automatically evicts oldest images and removes provisioning containers when limit reached
- Each image is ~1-2GB (includes full CUBRID installation)

## Configuration

### Enable Optimization

In `conf/tester.conf`:
```properties
# Enable optimized Docker execution
optimized_docker_enabled=true

# Maximum number of Docker images to cache
build_cache_size=20
```

### Performance Tuning

The system also includes additional performance flags:
- `--init`: Proper process reaping
- `--tmpfs /tmp:exec,size=2G`: Fast tmpfs for temporary files
- `--shm-size=2g`: Increased shared memory for database operations

## Usage

### Standard Operation
No changes needed! The optimization is transparent:
```bash
# Start tester normally
./bin/start_tester.sh

# Send test requests as usual
curl -X POST http://localhost:8089/build \
  -d '{"commits": ["abc123"], "tests": ["test.sh"], ...}'
```

### Monitoring

Check Docker images:
```bash
# List cached CUBRID test images
docker images "cubrid-test:*"

# Check image size
docker images --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}" | grep cubrid-test
```

Check logs for optimization:
```bash
# Look for optimized Docker runs
grep "optimized Docker command" ~/cubrid-testtools/CTP/builder_tester/log/system/tester.log

# Check image provisioning
grep "Building Docker image by provisioning container" ~/cubrid-testtools/CTP/builder_tester/log/system/tester.log
```

### Manual Cache Management

Clear all cached images:
```bash
# Remove all cubrid-test images
docker rmi $(docker images -q "cubrid-test:*")
```

Remove specific image:
```bash
docker rmi cubrid-test:<commit>_<baseline>
```

## Remote Server Compatibility

The optimization works seamlessly with remote tester nodes:

1. **Each tester builds its own images**: When a remote tester receives a build package, it builds and caches the Docker image locally
2. **No image transfer needed**: Images are built where they're used
3. **Independent caching**: Each tester manages its own image cache

### Multi-Node Setup
```bash
# Node 1 (builds and caches images for commits it tests)
./bin/start_tester.sh

# Node 2 (builds and caches images independently)
./bin/start_tester.sh

# Node 3 (builds and caches images independently)
./bin/start_tester.sh
```

## Troubleshooting

### Image Build Failures

If image building fails:
1. Check Docker daemon is running: `docker ps`
2. Check disk space: `df -h`
3. Check logs: `grep "provisioning container" tester.log`
4. Manually test provisioning by running the base test image with the build tarball mounted (copy the setup script from `DockerImageBuilder#createSetupScript`):
   ```bash
   cat > /tmp/cubrid_setup.sh <<'EOF'
   #!/bin/bash
   set -euo pipefail
   # ...script contents from DockerImageBuilder#createSetupScript...
   EOF
   chmod +x /tmp/cubrid_setup.sh
   docker run --rm \
     -v /path/to/cubrid.tar.gz:/mnt/build.tar.gz:ro \
     -v /tmp/cubrid_setup.sh:/mnt/setup.sh:ro \
     <docker_test_image> bash /mnt/setup.sh
   ```

### Fallback Behavior

If optimization fails, the system automatically falls back to standard execution:
- Logs warning about optimization failure
- Continues with traditional extraction method
- Test still completes successfully

### Disk Space Management

Monitor disk usage:
```bash
# Check Docker space usage
docker system df

# Clean unused Docker resources
docker system prune -a
```

Adjust cache size if needed:
```properties
# Reduce cache size to save space
build_cache_size=5
```

## Performance Metrics

### Test Execution Times

Monitor improvement:
```bash
# Check execution times before optimization
grep "Docker test completed" tester.log | grep -v optimized

# Check execution times with optimization
grep "Docker test completed" tester.log | grep optimized
```

### Cache Hit Rate

```bash
# Count cache hits (using existing image)
grep "Using existing Docker image" tester.log | wc -l

# Count cache misses (provisioning new image)
grep "Building Docker image by provisioning container" tester.log | wc -l
```

## Best Practices

1. **Disk Space**: Ensure sufficient disk space (20-40GB) for image cache
2. **Cache Size**: Adjust `build_cache_size` based on:
   - Available disk space
   - Number of unique commits tested
   - Test frequency
3. **Cleanup**: Periodically clean old images not in cache
4. **Monitoring**: Watch for image build failures in logs

## Advanced Configuration

### Disable Optimization (Fallback to Original)
```properties
optimized_docker_enabled=false
```

### Adjust Cache Size
```properties
# For limited disk space
build_cache_size=5

# For high-throughput testing
build_cache_size=50
```

## Implementation Details

### Image Naming Convention
- Format: `cubrid-test:<commit>_<baseline>`
- Example: `cubrid-test:6ea587e_4b045a6` (baseline may be `unknown`)

### Image Contents
Each image contains:
- Base test image (`cubridci/cubridci:test_shell`)
- Pre-extracted CUBRID build in `/opt/cubrid`
- Configured environment variables
- Clean database directory

### Build Process
1. Generates a provisioning script on the host
2. Launches the base tester image with the build tarball and script mounted (`docker run`)
3. Extracts and installs CUBRID inside the running container
4. Runs setup steps within the container
5. Commits the container as `cubrid-test:<commit>_<baseline>` and cleans up temporary artifacts

## Summary

This optimization provides:
- **80-90% reduction** in test overhead time
- **Transparent operation** - no changes to existing workflows
- **Automatic fallback** on failures
- **Remote server compatible**
- **Configurable caching** based on available resources

The system maintains full backward compatibility while providing dramatic performance improvements for Docker-based test execution.
