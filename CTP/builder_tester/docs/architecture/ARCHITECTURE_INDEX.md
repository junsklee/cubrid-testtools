# CUBRID Docker Build System - Documentation Index

This directory contains comprehensive documentation of the CUBRID test tools Docker build system architecture. Start with one of these guides based on your needs:

## Latest Feature: Smart Scheduling System (November 2024)

**NEW**: The system now includes intelligent multi-resource-aware test scheduling! See:
- **[../SMART_SCHEDULING_ARCHITECTURE.md](../SMART_SCHEDULING_ARCHITECTURE.md)** - Complete architecture
- **[../SMART_SCHEDULING_CONFIG.md](../SMART_SCHEDULING_CONFIG.md)** - Configuration reference
- **[../SMART_SCHEDULING_TESTING_GUIDE.md](../SMART_SCHEDULING_TESTING_GUIDE.md)** - Testing guide

## Documentation Files

### 1. DOCKER_BUILD_ARCHITECTURE.md (24KB) - START HERE FOR COMPREHENSIVE OVERVIEW
**Best for**: Understanding the complete system architecture, execution flows, and all components

Contents:
- System overview and architecture
- Docker build triggering and execution (section 1)
- Worker node management and configuration (section 2)
- Workload distribution across nodes (section 3)
- Builder_tester component structure (section 4)
- Workload distribution logic and algorithms (section 5)
- Ccache shared directory management (section 6)
- Detailed execution flow examples (section 7)
- Configuration file references (section 8)
- Concurrent execution safety mechanisms (section 9)
- Summary and key concepts

**Key sections**:
- Docker build process with retry logic
- Round-robin test distribution algorithm
- Worker node types and priorities
- Ccache optimization strategy

### 2. QUICK_REFERENCE.md (7.5KB) - START HERE FOR QUICK LOOKUP
**Best for**: Finding specific information quickly, key classes, configurations, and algorithms

Contents:
- Key file paths and entry points
- Core classes and their main methods
- Concurrent execution model
- Configuration keys summary
- Request/response JSON formats
- Key algorithms with code snippets
- Worker node communication details
- Build and Docker image caching strategies
- Error handling and fallback chains
- Performance tuning parameters
- Monitoring endpoints

**Quick lookup tables**:
- Builder service configuration
- Tester service configuration
- Test distribution algorithm
- Run mode semantics
- Docker build process steps

### 3. FILE_STRUCTURE.md (13KB) - START HERE FOR FINDING CODE
**Best for**: Locating specific Java files, understanding code organization, finding entry points

Contents:
- Complete Java source file structure
- Configuration file locations
- Log directory organization
- Temporary work directories
- Build artifacts locations
- Documentation files list
- Binary/compiled files
- Important file locations by function
- Class hierarchy diagrams
- Service entry points and dependencies

**Sections**:
- All Java source files organized by package
- Configuration files (builder.conf, tester.conf)
- Log storage structure
- Work directories for builds and tests
- Related external files (CUBRID, test cases)

---

## Quick Navigation

### Understanding how builds work
1. Read: DOCKER_BUILD_ARCHITECTURE.md section 1
2. Reference: QUICK_REFERENCE.md "Docker Build Process" and "Key Algorithms"
3. Code: Find files in FILE_STRUCTURE.md "For Understanding Build Flow"

### Understanding worker node management
1. Read: DOCKER_BUILD_ARCHITECTURE.md section 2
2. Reference: QUICK_REFERENCE.md "Worker Node Management"
3. Code: Find files in FILE_STRUCTURE.md "For Understanding Test Distribution"

### Understanding test distribution
1. **NEW Smart Scheduling**: Read [../SMART_SCHEDULING_ARCHITECTURE.md](../SMART_SCHEDULING_ARCHITECTURE.md)
2. Legacy round-robin: DOCKER_BUILD_ARCHITECTURE.md section 3
3. Reference: QUICK_REFERENCE.md "Test Distribution Algorithm"
4. Code: BuilderTask.java (distributeTestsWithSmartScheduling or distributeTestsLegacy)

### Understanding concurrent execution
1. Read: DOCKER_BUILD_ARCHITECTURE.md section 9
2. Reference: QUICK_REFERENCE.md "Concurrent Execution"
3. Code: Find locations in FILE_STRUCTURE.md "Important File Locations by Function"

### Understanding ccache optimization
1. Read: DOCKER_BUILD_ARCHITECTURE.md section 6
2. Reference: QUICK_REFERENCE.md "Ccache Optimization"
3. See also: [../CCACHE_GUIDE.md](../CCACHE_GUIDE.md) for additional details

### Finding a specific class or method
1. Use: FILE_STRUCTURE.md "Important File Locations by Function"
2. Get absolute paths and line numbers
3. Reference: QUICK_REFERENCE.md "Core Classes and Methods"

---

## Key Concepts Summary

### System Architecture
- **Builder Service**: HTTP server at port 8089, orchestrates builds and test distribution
- **Tester Service**: HTTP server at port 8090, executes tests on worker nodes
- **Communication**: HTTP JSON-based requests/responses
- **Concurrency**: Fixed thread pools with configurable size

### Docker Build System
- **Execution**: Docker containers with mounted volumes
- **Strategy**: Cherry-pick commit onto baseline with fallback
- **Caching**: Build packages cached in-memory and on disk
- **Optimization**: Package only _install/CUBRID, use git references

### Test Distribution
- **Smart Scheduling (NEW)**: Multi-resource-aware scoring with cache locality
- **Legacy Algorithm**: Round-robin using global test index (still available)
- **Strategy**: Mice/elephants separation (SJF + bin-packing)
- **Fairness**: Aging mechanism prevents starvation
- **Pools**: Per-worker thread pools for concurrent test execution
- **Toggle**: Configurable via `smart_scheduling_enabled` in builder.conf

### Caching Strategy
- **Build Cache**: In-memory ConcurrentHashMap with disk backup
- **Docker Images**: LRU cache of pre-built images per commit+baseline
- **Ccache**: Shared host directory with deterministic version for consistency

### Configuration
- **Builder**: builder.conf (4 concurrent builds by default)
- **Tester**: tester.conf (6 concurrent tests per node by default)
- **Both**: Support Docker and direct execution modes
- **Optimization**: Ccache enabled with 15GB limit

---

## File Location Shortcuts

### Most Important Files
```
Builder service entry point:
  src/com/navercorp/cubridqa/builder/Builder.java

Tester service entry point:
  src/com/navercorp/cubridqa/builder/Tester.java

Build orchestration:
  src/com/navercorp/cubridqa/builder/BuilderTask.java

Docker build execution:
  src/com/navercorp/cubridqa/builder/DockerBuildManager.java

Test orchestration:
  src/com/navercorp/cubridqa/builder/tester/TestOrchestrator.java

Configuration files:
  conf/builder.conf
  conf/tester.conf
```

### Most Important Algorithms
```
Test distribution (round-robin):
  FILE: BuilderTask.java
  LINES: 137-176
  REFERENCE: QUICK_REFERENCE.md "Test Distribution Algorithm"

Run mode semantics (retry/repeat):
  FILE: TestOrchestrator.java
  LINES: 45-226
  REFERENCE: QUICK_REFERENCE.md "Run Mode Semantics"

Docker command construction:
  FILE: DockerBuildManager.java
  LINES: 118-197
  REFERENCE: DOCKER_BUILD_ARCHITECTURE.md section 1.2

Build script generation:
  FILE: DockerBuildManager.java
  LINES: 687-959
  REFERENCE: DOCKER_BUILD_ARCHITECTURE.md section 1.2
```

---

## How to Use These Documents

### If you have 5 minutes
- Read: ARCHITECTURE_INDEX.md (this file)
- Reference: QUICK_REFERENCE.md

### If you have 20 minutes
- Read: QUICK_REFERENCE.md completely
- Scan: DOCKER_BUILD_ARCHITECTURE.md sections 1, 2, 3

### If you have 1 hour
- Read: DOCKER_BUILD_ARCHITECTURE.md completely
- Keep: FILE_STRUCTURE.md open for reference
- Cross-check: QUICK_REFERENCE.md for key algorithms

### If you need to modify the code
- Find the file: FILE_STRUCTURE.md
- Understand the flow: DOCKER_BUILD_ARCHITECTURE.md
- Get line numbers: QUICK_REFERENCE.md
- Make changes with confidence

---

## Related Documentation

Also see these files in the same directory:
- **[../CCACHE_GUIDE.md](../CCACHE_GUIDE.md)**: Detailed ccache configuration
- **DOCKER_OPTIMIZATION_SUMMARY.md**: Docker optimization notes
- **README.md**: Original project README
- **PERFORMANCE_OPTIMIZATION.md**: Performance tuning guide
- **REFACTORING_SUMMARY.md**: Refactoring history and changes

---

## Questions Answered by These Documents

Q: How are Docker builds triggered?
A: See DOCKER_BUILD_ARCHITECTURE.md section 1.1, or QUICK_REFERENCE.md "Build Execution Flow"

Q: How are tests distributed to worker nodes?
A: **NEW**: See [../SMART_SCHEDULING_ARCHITECTURE.md](../SMART_SCHEDULING_ARCHITECTURE.md) for smart scheduling, or DOCKER_BUILD_ARCHITECTURE.md section 3 for legacy round-robin

Q: What are the concurrent execution models?
A: See DOCKER_BUILD_ARCHITECTURE.md section 9, or QUICK_REFERENCE.md "Concurrent Execution"

Q: How does ccache work?
A: See DOCKER_BUILD_ARCHITECTURE.md section 6, or QUICK_REFERENCE.md "Ccache Optimization"

Q: Where is a specific Java class?
A: See FILE_STRUCTURE.md "Core Java Source Files" section

Q: What configuration parameters are available?
A: See QUICK_REFERENCE.md "Configuration Keys" section, or [../SMART_SCHEDULING_CONFIG.md](../SMART_SCHEDULING_CONFIG.md) for smart scheduling options

Q: How do I enable smart scheduling?
A: Set `smart_scheduling_enabled=true` in builder.conf. See [../SMART_SCHEDULING_CONFIG.md](../SMART_SCHEDULING_CONFIG.md) for details

Q: How do builds fallback if Docker is unavailable?
A: See QUICK_REFERENCE.md "Error Handling & Fallback" section

Q: What are the HTTP endpoints?
A: See DOCKER_BUILD_ARCHITECTURE.md section 1.1 or QUICK_REFERENCE.md "Monitoring" section

Q: How is test execution retried?
A: See QUICK_REFERENCE.md "Run Mode Semantics" or DOCKER_BUILD_ARCHITECTURE.md section 1.1

Q: How are worker nodes validated?
A: See DOCKER_BUILD_ARCHITECTURE.md section 2.1 or QUICK_REFERENCE.md "Worker Node Management"

---

Generated on: November 10, 2025
Documentation version: 2.0 (includes Smart Scheduling System)
System analyzed: CUBRID Test Tools builder_tester component
Branch: builder_tester_refactor_tester
