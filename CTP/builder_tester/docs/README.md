# Builder Tester Documentation

Complete documentation for the CUBRID Builder-Tester system.

## Quick Start

- [Main README](../README.md) - System overview and getting started
- [MULTI_NODE_TESTING.md](MULTI_NODE_TESTING.md) - Multi-node testing setup and usage
- **[IO_FIRST_SCHEDULING_IMPLEMENTATION.md](IO_FIRST_SCHEDULING_IMPLEMENTATION.md)** - Complete context for I/O-first scheduling implementation (November 2025)

## New Features (Nov 2024)

### Smart Scheduling System (November 2024)
- **[SMART_SCHEDULING_ARCHITECTURE.md](SMART_SCHEDULING_ARCHITECTURE.md)** - Complete architecture and design documentation
- **[SMART_SCHEDULING_TESTING_GUIDE.md](SMART_SCHEDULING_TESTING_GUIDE.md)** - Manual testing procedures and validation
- **[SMART_SCHEDULING_CONFIG.md](SMART_SCHEDULING_CONFIG.md)** - Configuration reference and tuning guide

**Key improvements:**
- Intelligent multi-resource-aware test scheduling (replaces round-robin)
- Per-node test history and resource demand prediction
- Cache locality optimization (Docker images, build packages)
- Mice/elephants queue separation for optimal throughput
- **I/O-first scheduling** with separate read/write bandwidth tracking (November 2025)
- 10-30% higher throughput, 15-25% faster mean completion time
- 10-20% higher cache hit rates

### Sequential Build & Workload Distribution
- **[SEQUENTIAL_BUILD_IMPLEMENTATION.md](SEQUENTIAL_BUILD_IMPLEMENTATION.md)** - Implementation summary of sequential builds
- **[WORKLOAD_DISTRIBUTION_DESIGN.md](WORKLOAD_DISTRIBUTION_DESIGN.md)** - Comprehensive design document with all scenarios

**Key improvements:**
- Builds now run sequentially (one at a time) to maximize ccache hit ratio
- Worker nodes can now build, not just test
- Smart test distribution minimizes network transfers
- Handles all N builds × M nodes scenarios optimally

## Architecture

Directory: [architecture/](architecture/)

- **[ARCHITECTURE_INDEX.md](architecture/ARCHITECTURE_INDEX.md)** - Navigation guide for architecture docs
- **[DOCKER_BUILD_ARCHITECTURE.md](architecture/DOCKER_BUILD_ARCHITECTURE.md)** - Complete architectural overview
- **[FILE_STRUCTURE.md](architecture/FILE_STRUCTURE.md)** - Code organization and file locations
- **[QUICK_REFERENCE.md](architecture/QUICK_REFERENCE.md)** - Quick lookup guide for key classes and algorithms

## Performance & Optimization

- **[DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md)** - Docker build optimizations
- **[DOCKER_OPTIMIZATION_SUMMARY.md](DOCKER_OPTIMIZATION_SUMMARY.md)** - Summary of Docker improvements
- **[PERFORMANCE_OPTIMIZATION.md](PERFORMANCE_OPTIMIZATION.md)** - General performance optimizations
- **[CCACHE_GUIDE.md](CCACHE_GUIDE.md)** - Compiler cache setup and usage
- **[REFACTORING_SUMMARY.md](REFACTORING_SUMMARY.md)** - Historical refactoring notes

### CUBRID installation locations in Docker
- **Optimized images** install CUBRID into `/opt/cubrid` (canonical).
- **Optimized runtime** presents `CUBRID=/root/CUBRID` by bind-mounting `/opt/cubrid` onto `/root/CUBRID` (with a symlink fallback), so logs/configs consistently use `/root/CUBRID/...` and match test normalization.
- **Standard (non-optimized) runtime** exports `CUBRID` to the per-test extracted directory (e.g., under `/tmp/cubrid_install`).
- **Host installs (non-Docker)** via `CubridInstaller` use `$HOME/CUBRID`.

See: [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md#cubrid-installation-paths-optcubrid-vs-rootcubrid).
## Features

Directory: [features/](features/)

- **[MULTIPART_IMPLEMENTATION.md](features/MULTIPART_IMPLEMENTATION.md)** - Multipart upload implementation
- **[REPORT_SERVER_ENHANCEMENTS.md](features/REPORT_SERVER_ENHANCEMENTS.md)** - Report server improvements
- **[TEST_LOGGING_FIXED.md](features/TEST_LOGGING_FIXED.md)** - Test logging fixes

## Configuration

Directory: [configuration/](configuration/)

See configuration README for details on:
- `builder.conf` - Builder service configuration
- `tester.conf` - Tester service configuration
- Environment variables
- Docker settings

## Usage

Directory: [usage/](usage/)

See usage README for:
- API endpoints
- Example requests
- Common workflows
- Troubleshooting

## Document Index by Topic

### For Developers
1. Start: [DOCKER_BUILD_ARCHITECTURE.md](architecture/DOCKER_BUILD_ARCHITECTURE.md)
2. Code structure: [FILE_STRUCTURE.md](architecture/FILE_STRUCTURE.md)
3. Quick reference: [QUICK_REFERENCE.md](architecture/QUICK_REFERENCE.md)
4. Smart scheduling: [SMART_SCHEDULING_ARCHITECTURE.md](SMART_SCHEDULING_ARCHITECTURE.md)
5. Workload system: [WORKLOAD_DISTRIBUTION_DESIGN.md](WORKLOAD_DISTRIBUTION_DESIGN.md)

### For Operations/QA
1. Setup: [Main README](../README.md)
2. Multi-node: [MULTI_NODE_TESTING.md](MULTI_NODE_TESTING.md)
3. Smart scheduling config: [SMART_SCHEDULING_CONFIG.md](SMART_SCHEDULING_CONFIG.md)
4. Smart scheduling testing: [SMART_SCHEDULING_TESTING_GUIDE.md](SMART_SCHEDULING_TESTING_GUIDE.md)
5. Performance: [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md)
6. Ccache: [CCACHE_GUIDE.md](CCACHE_GUIDE.md)

### For Troubleshooting
1. Sequential builds: [SEQUENTIAL_BUILD_IMPLEMENTATION.md](SEQUENTIAL_BUILD_IMPLEMENTATION.md) (see Troubleshooting section)
2. Performance: [PERFORMANCE_OPTIMIZATION.md](PERFORMANCE_OPTIMIZATION.md)
3. Docker: [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md)

## Recent Changes

### November 2024 - Smart Scheduling System
- ✅ Intelligent multi-resource-aware test scheduling
- ✅ Per-node test history and prediction (WAL + snapshot persistence)
- ✅ Extended /health and /score endpoints
- ✅ Multi-resource scoring with cache locality
- ✅ Mice/elephants queue separation (SJF + bin-packing)
- ✅ BuilderTask integration with config toggle
- ✅ 10-30% throughput improvement, 15-25% faster mean completion

### November 2025 - I/O-First Scheduling Enhancement
- ✅ Separate read/write I/O bandwidth tracking and prediction
- ✅ I/O-dominant scoring with configurable weights
- ✅ Dimension-specific safety margins for read/write I/O
- ✅ Enhanced /health endpoint with read/write capacity/utilization
- ✅ Backward compatible with legacy `ioMbPerSec` (splits 50/50)

See [SMART_SCHEDULING_ARCHITECTURE.md](SMART_SCHEDULING_ARCHITECTURE.md) for details.

### November 2024 - Sequential Build Implementation
- ✅ Sequential builds (one at a time) for maximum ccache efficiency
- ✅ Distributed building across all nodes
- ✅ Smart test distribution (same-node preference)
- ✅ New WorkloadDistributor system
- ✅ Remote build triggering via HTTP

See [SEQUENTIAL_BUILD_IMPLEMENTATION.md](SEQUENTIAL_BUILD_IMPLEMENTATION.md) for details.

### October 2024 - Docker Optimizations
- Optimized Docker image layering
- Improved build caching
- Reduced image sizes

See [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md) for details.

### August 2024 - Multi-node Testing
- Support for multiple worker nodes
- Improved test distribution
- Enhanced logging and reporting

See [MULTI_NODE_TESTING.md](MULTI_NODE_TESTING.md) for details.

## Contributing

When adding new features or making changes:

1. **Update relevant documentation** in the appropriate directory
2. **Add summary to this index** if it's a major change
3. **Include examples** in your documentation
4. **Test your changes** before documenting

### Documentation Structure

```
docs/
├── README.md (this file)           # Master index
├── architecture/                   # System architecture docs
├── configuration/                  # Configuration guides
├── features/                       # Feature-specific docs
├── usage/                          # Usage guides
└── *.md                           # Top-level guides (multi-node, ccache, etc.)
```

## Support

For issues or questions:
- Check the relevant documentation first
- Review troubleshooting sections
- Check logs in `~/cubrid-testtools/CTP/builder_tester/log/`
- Report issues with full logs and configuration

## Version History

- **v4.1** (Nov 2025) - I/O-first scheduling with separate read/write tracking
- **v4.0** (Nov 2024) - Smart scheduling system with multi-resource awareness
- **v3.0** (Nov 2024) - Sequential builds with workload distribution
- **v2.0** (Aug 2024) - Multi-node testing and enhanced reporting
- **v1.0** (Earlier) - Initial builder-tester implementation
