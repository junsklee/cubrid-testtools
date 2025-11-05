# Builder Tester Documentation

Complete documentation for the CUBRID Builder-Tester system.

## Quick Start

- [Main README](../README.md) - System overview and getting started
- [MULTI_NODE_TESTING.md](MULTI_NODE_TESTING.md) - Multi-node testing setup and usage

## New Features (Nov 2024)

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
4. New workload system: [WORKLOAD_DISTRIBUTION_DESIGN.md](WORKLOAD_DISTRIBUTION_DESIGN.md)

### For Operations/QA
1. Setup: [Main README](../README.md)
2. Multi-node: [MULTI_NODE_TESTING.md](MULTI_NODE_TESTING.md)
3. Performance: [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md)
4. Ccache: [CCACHE_GUIDE.md](CCACHE_GUIDE.md)

### For Troubleshooting
1. Sequential builds: [SEQUENTIAL_BUILD_IMPLEMENTATION.md](SEQUENTIAL_BUILD_IMPLEMENTATION.md) (see Troubleshooting section)
2. Performance: [PERFORMANCE_OPTIMIZATION.md](PERFORMANCE_OPTIMIZATION.md)
3. Docker: [DOCKER_OPTIMIZATION.md](DOCKER_OPTIMIZATION.md)

## Recent Changes

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

- **v3.0** (Nov 2024) - Sequential builds with workload distribution
- **v2.0** (Aug 2024) - Multi-node testing and enhanced reporting
- **v1.0** (Earlier) - Initial builder-tester implementation
