# Ccache Support in Builder-Tester System

## Overview

The Builder-Tester system now includes full support for ccache (compiler cache) to significantly speed up repeated builds. This feature caches compilation results and reuses them when the same compilation is detected again.

## Benefits

- **Faster Rebuilds**: Typically 5-10x faster for unchanged code
- **Reduced CPU Usage**: Reuses previous compilation results
- **Persistent Cache**: Cache persists across builds and restarts
- **Docker Support**: Works seamlessly in both Docker and direct build modes

## Configuration

Add these settings to `conf/builder.conf`:

```properties
# Ccache configuration (compiler cache for faster rebuilds)
ccache_enabled=true
ccache_dir=~/ccache
ccache_max_size=15G
ccache_compilercheck=content
ccache_hardlink=true

# Parallel build configuration
parallel_jobs=0  # 0 = auto-detect from CPU cores
```

### Configuration Options

- **ccache_enabled**: Enable/disable ccache (default: true)
- **ccache_dir**: Directory to store cache (default: ~/ccache)
- **ccache_max_size**: Maximum cache size (default: 15G)
- **ccache_compilercheck**: How ccache checks compiler changes (default: content)
- **ccache_hardlink**: Use hard links to save space (default: true)
- **parallel_jobs**: Number of parallel compilation jobs (0 = auto)

## Setup

### 1. Install ccache

```bash
# Automatic installation
./bin/manage_ccache.sh install

# Or manual installation
# Ubuntu/Debian:
sudo apt-get install ccache

# CentOS/RHEL:
sudo yum install ccache

# macOS:
brew install ccache
```

### 2. Initialize ccache

```bash
# Set up ccache directory and configuration
./bin/manage_ccache.sh setup
```

### 3. Verify Installation

```bash
# Check ccache status
./bin/manage_ccache.sh status
```

## Usage

Once configured, ccache works automatically:

1. **First Build**: Populates the cache
   - Build time: Normal
   - Cache misses: 100%

2. **Subsequent Builds**: Uses cached results
   - Build time: Significantly reduced
   - Cache hits: High percentage for unchanged code

## Management Commands

The `manage_ccache.sh` script provides several management commands:

```bash
# Install ccache on the system
./bin/manage_ccache.sh install

# Set up ccache directory and configuration
./bin/manage_ccache.sh setup

# Show ccache status and basic statistics
./bin/manage_ccache.sh status

# Show detailed statistics
./bin/manage_ccache.sh stats

# Clear the cache
./bin/manage_ccache.sh clear
```

## Docker Integration

When using Docker builds (`use_docker=true`), the system:

1. Automatically mounts the ccache directory as a volume
2. Sets appropriate environment variables (CC, CXX, CCACHE_DIR)
3. Reports ccache statistics before and after builds
4. Uses devtoolset-8 if available in the container

## Direct Build Integration

For direct (non-Docker) builds, the system:

1. Sets ccache environment variables
2. Initializes ccache before building
3. Reports statistics after completion

## Monitoring Cache Performance

### Build Logs

Ccache statistics are included in build logs:

```
Ccache status before build:
cache directory                     /ccache
primary config                      /ccache/ccache.conf
stats zero time                     Thu Jan  1 00:00:00 1970
cache hit (direct)                   1234
cache hit (preprocessed)              567
cache miss                            890
...

Ccache status after build:
cache hit (direct)                   1456
cache hit (preprocessed)              678
cache miss                            891
...
```

### Cache Hit Rate

Monitor your cache hit rate to ensure optimal performance:

```bash
./bin/manage_ccache.sh stats | grep "cache hit rate"
```

A good cache hit rate is typically above 70% for incremental builds.

## Best Practices

1. **Cache Size**: Set an appropriate cache size based on your project:
   - Small projects: 2-5GB
   - Medium projects: 5-10GB
   - Large projects: 10-20GB+

2. **Cache Location**: Use a fast SSD for the cache directory

3. **Regular Maintenance**: Periodically clean old cache entries:
   ```bash
   ./bin/manage_ccache.sh clear  # Full clear
   # Or
   ccache --cleanup  # Remove old entries
   ```

4. **Shared Cache**: For team environments, consider a shared cache directory (with appropriate permissions)

## Troubleshooting

### Cache Not Being Used

1. Check if ccache is enabled:
   ```bash
   grep ccache_enabled conf/builder.conf
   ```

2. Verify ccache is installed:
   ```bash
   which ccache
   ```

3. Check cache directory permissions:
   ```bash
   ls -la ~/ccache
   ```

### Low Cache Hit Rate

1. Check compiler flags consistency
2. Ensure source files aren't being modified unnecessarily
3. Verify ccache_compilercheck setting

### Cache Directory Full

1. Increase max_size in configuration
2. Clear cache and rebuild:
   ```bash
   ./bin/manage_ccache.sh clear
   ```

## Performance Metrics

Example performance improvements with ccache:

| Build Type | Without Ccache | With Ccache (cold) | With Ccache (warm) |
|------------|---------------|-------------------|-------------------|
| Full Build | 30 minutes    | 30 minutes        | 5 minutes         |
| Incremental| 10 minutes    | 10 minutes        | 1-2 minutes       |
| No Changes | 10 minutes    | 10 minutes        | < 1 minute        |

## Additional Optimizations

The system also includes these performance improvements from CircleCI best practices:

1. **Parallel Compilation**: Uses `-j` flag with optimal CPU core count
2. **Devtoolset-8 Support**: Automatically uses devtoolset-8 when available
3. **Git Optimizations**: Reduced checkout noise with `advice.detachedHead=false`
4. **Resource Management**: Proper cleanup of build artifacts and temporary files

## Environment Variables

These environment variables are automatically set when ccache is enabled:

- `CC="ccache gcc"`
- `CXX="ccache g++"`
- `CCACHE_DIR=/path/to/cache`
- `CCACHE_COMPILERCHECK=content`
- `CCACHE_HARDLINK=1`
- `CCACHE_MAXSIZE=5G`
- `MAKEFLAGS=-jN` (where N is the number of parallel jobs)
