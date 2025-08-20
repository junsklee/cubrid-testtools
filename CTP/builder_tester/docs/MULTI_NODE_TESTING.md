- **Remote Testers**: Build packages are served via HTTP from the Builder node
  - Builder exposes packages at: `http://<builder-ip>:<port>/download/build/<filename>`
  - Testers automatically download packages when needed
  - Downloaded packages are cached to avoid re-downloading

## Setup Instructions

### 1. Start Multiple Tester Nodes
On each tester machine:
```bash
export GITHUB_TOKEN=your_github_token
cd ~/cubrid-testtools/CTP/builder_tester
./bin/start_tester.sh
```

### 2. Start Builder Node
On the builder machine:
```bash
export GITHUB_TOKEN=your_github_token
cd ~/cubrid-testtools/CTP/builder_tester
./bin/start_builder.sh
```

### 3. Submit Build Request with Multiple Nodes
```bash
./bin/test_client_multi_node.sh
```

Or using curl directly:
```bash
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["6ea587e"],
    "tests": ["test1.sh", "test2.sh", "test3.sh"],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIps": ["192.168.1.10", "192.168.1.11", "192.168.1.12"],
    "buildType": "debug"
  }'
```

## Monitoring

### Builder Logs
The builder.log will show test distribution:
```
[INFO] Building 2 commits for 6 tests across 3 tester node(s)
[INFO] Assigning test shell/_01_utility/test1.sh (commit 6ea587e) to tester node 192.168.1.10
[INFO] Assigning test shell/_01_utility/test2.sh (commit 6ea587e) to tester node 192.168.1.11
[INFO] Assigning test shell/_01_utility/test3.sh (commit 6ea587e) to tester node 192.168.1.12
[INFO] Assigning test shell/_01_utility/test4.sh (commit 6ea587e) to tester node 192.168.1.10
[INFO] Worker 192.168.1.10 assigned 2 tests
[INFO] Worker 192.168.1.11 assigned 2 tests
[INFO] Worker 192.168.1.12 assigned 2 tests
```

### Build Package Transfer
For remote testers, you'll see:
```
[INFO] Using HTTP URL for remote tester 192.168.1.10: http://10.0.0.5:8089/download/build/cubrid_6ea587e.tar.gz
[INFO] Using local file path for tester localhost: /tmp/builder_work/build_6ea587e/cubrid_6ea587e.tar.gz
```

### Tester Logs
Each tester will show when it downloads packages and serves logs:
```
[INFO] Build package is a URL: http://10.0.0.5:8089/download/build/cubrid_6ea587e.tar.gz
[INFO] Downloading build package from: http://10.0.0.5:8089/download/build/cubrid_6ea587e.tar.gz
[INFO] Download progress: 25%
[INFO] Download progress: 50%
[INFO] Download progress: 75%
[INFO] Build package downloaded successfully: /tmp/tester_work/test_123/cubrid_6ea587e.tar.gz
[INFO] Serving log file request: /log/docker_opt_6ea587e_test1.log
[INFO] Found log file at: /home/qahome/cubrid-testtools/CTP/builder_tester/log/requests/req_20250820_232327_192d/tests/docker_opt_6ea587e_test1.log
```

## Performance Benefits

### Example Scenario
- 100 tests to run
- Each test takes ~1 minute
- Single node: 100 minutes (with concurrency=1)
- 3 nodes: ~33 minutes (3x faster)
- With concurrency=6 per node: ~6 minutes (dramatic improvement)

## Troubleshooting

### Common Issues

1. **Tester not reachable**
   - Error: `Tester at 192.168.1.10:8090 is not reachable`
   - Solution: Ensure tester service is running and port is accessible

2. **Build package download fails**
   - Error: `Failed to download build package. HTTP response: 404`
   - Solution: Check Builder is running and work directory contains the build

3. **Uneven test distribution**
   - This is normal for small test counts
   - Distribution becomes more even with larger test sets

### Network Requirements
- Builder → Tester: Port 8090 (configurable)
- Tester → Builder: Port 8089 (for downloading packages)
- Ensure firewall rules allow these connections

## Advanced Configuration

### Mixed Local and Remote Testers
```json
{
  "workerIps": ["localhost", "192.168.1.10", "192.168.1.11"]
}
```
- localhost tests use local file paths (fastest)
- Remote tests use HTTP download (automatic)

### Large Build Packages
For large build packages (>100MB):
- First download may take time
- Subsequent tests use cached package
- Cache automatically cleans old packages (keeps last 5)

## Implementation Details

### Changes Made
1. **Builder.java**
   - Added `/download/build/{filename}` endpoint for serving packages
   - Support for `workerIps` array in requests
   - Backward compatibility for `workerIp` (singular)

2. **BuilderTask.java**
   - Round-robin test distribution algorithm
   - Detection of local vs remote testers
   - HTTP URL generation for remote testers
   - Enhanced logging for test assignments
   - **Remote log retrieval**: Fetches test execution logs from remote testers using HTTP

3. **Tester.java**
   - Automatic detection of URL vs file path
   - HTTP download capability with progress logging
   - Package caching to avoid re-downloads
   - Cache management (auto-cleanup)
   - **HTTP log endpoint**: `/log/{filename}` serves test execution logs to builders

### Design Decisions
- **HTTP Transfer**: Chosen for simplicity and portability
- **Round-Robin**: Simple, effective, ensures even distribution
- **Caching**: Reduces network load for repeated tests
- **Backward Compatibility**: Ensures existing setups continue working

## Best Practices

1. **Node Selection**
   - Use machines with similar specs for balanced performance
   - Place tester nodes close to Builder (network-wise)

2. **Concurrency Settings**
   - Set `max_concurrent_tests` in tester.conf based on node capacity
   - Builder automatically queries tester concurrency settings

3. **Monitoring**
   - Watch builder.log for distribution patterns
   - Monitor network usage during package transfers
   - Check tester logs for download/execution issues

4. **Scaling**
   - Start with 2-3 nodes and measure improvement
   - Add more nodes if tests are CPU/IO bound
   - Consider network bandwidth for many nodes

## Limitations

1. **Static Node List**: Nodes must be specified at request time
2. **No Dynamic Load Balancing**: Uses simple round-robin, not load-aware
3. **No Automatic Retry**: If a node fails, tests aren't redistributed
4. **Package Transfer Overhead**: First test on each remote node has download delay

## Future Enhancements (Not Implemented)
- Dynamic node discovery
- Load-aware distribution
- Automatic failover and retry
- Compressed package transfer
- P2P package sharing between testers
