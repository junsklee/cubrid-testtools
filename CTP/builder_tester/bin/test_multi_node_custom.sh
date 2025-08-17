#!/bin/bash

# Custom test client for multiple tester nodes
# Testing with localhost (192.168.1.5) and remote tester (192.168.1.15:8090)

BUILDER_HOST="${BUILDER_HOST:-localhost}"
BUILDER_PORT="${BUILDER_PORT:-8089}"

echo "Multi-Node Test Configuration:"
echo "=============================="
echo "Builder: http://${BUILDER_HOST}:${BUILDER_PORT}"
echo "Local Tester: localhost:8090 (192.168.1.5)"
echo "Remote Tester: 192.168.1.15:8090 (junHA user)"
echo ""
echo "Sending build request with test distribution..."
echo ""

# Custom request with our specific configuration
cat <<EOF | curl -X POST "http://${BUILDER_HOST}:${BUILDER_PORT}/build" \
  -H "Content-Type: application/json" \
  -d @-
{
  "commits": ["6ea587e", "1609a3a"],
  "tests": [
    "shell/_01_utility/_08_csql/cases/1001.sh",
    "shell/_01_utility/_08_csql/cases/1002.sh",
    "shell/_01_utility/_08_csql/cases/1003.sh",
    "shell/_01_utility/_08_csql/cases/1004.sh",
    "shell/_01_utility/_08_csql/cases/1005.sh",
    "shell/_01_utility/_08_csql/cases/1006.sh"
  ],
  "callbackUrl": "http://localhost:8089/callback",
  "workerIps": ["localhost", "192.168.1.15:8090"],
  "buildType": "debug"
}
EOF

echo ""
echo ""
echo "Request sent! Expected test distribution:"
echo "========================================="
echo "Round-robin distribution across 2 nodes with 6 tests per commit (12 total tests):"
echo ""
echo "For commit 6ea587e:"
echo "- Test 1001.sh → localhost:8090 (Local)"
echo "- Test 1002.sh → 192.168.1.15:8090 (Remote)"
echo "- Test 1003.sh → localhost:8090 (Local)"
echo "- Test 1004.sh → 192.168.1.15:8090 (Remote)"
echo "- Test 1005.sh → localhost:8090 (Local)"
echo "- Test 1006.sh → 192.168.1.15:8090 (Remote)"
echo ""
echo "For commit 1609a3a:"
echo "- Test 1001.sh → localhost:8090 (Local)"
echo "- Test 1002.sh → 192.168.1.15:8090 (Remote)"
echo "- Test 1003.sh → localhost:8090 (Local)"
echo "- Test 1004.sh → 192.168.1.15:8090 (Remote)"
echo "- Test 1005.sh → localhost:8090 (Local)"
echo "- Test 1006.sh → 192.168.1.15:8090 (Remote)"
echo ""
echo "Total: 6 tests per node (12 tests total)"
echo ""
echo "Check the following logs to verify distribution and execution:"
echo "- Builder log: log/requests/req_*/builds/build_*.log"
echo "- Local tester: Check Docker containers and logs"
echo "- Remote tester: Check with junHA@192.168.1.15"
echo ""
echo "Monitor progress with:"
echo "  tail -f log/requests/req_$(date +%Y%m%d)_*/builds/build_*.log"