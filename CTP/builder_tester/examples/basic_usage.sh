#!/bin/bash

# Basic example: Testing a single commit with multiple tests
# This script demonstrates the simplest usage of the Builder-Tester system

echo "Basic Builder-Tester Example"
echo "============================"
echo ""
echo "This example shows how to test a single commit with multiple test cases."
echo ""

# Configuration
BUILDER_URL="http://localhost:8089"
TESTER_URL="http://localhost:8090"

echo "Prerequisites:"
echo "--------------"
echo "1. Ensure GITHUB_TOKEN is set: export GITHUB_TOKEN=your_token"
echo "2. Start the Tester: ./bin/start_tester.sh"
echo "3. Start the Builder: ./bin/start_builder.sh"
echo ""

echo "Example 1: Single commit, single test"
echo "-------------------------------------"
cat <<'EOF'
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["6ea587e"],
    "tests": ["shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh"],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIp": "localhost",
    "buildType": "debug"
  }'
EOF

echo ""
echo "Example 2: Multiple commits, single test"
echo "----------------------------------------"
cat <<'EOF'
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["6ea587e", "abc123", "def456"],
    "tests": ["shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh"],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIp": "localhost",
    "buildType": "debug"
  }'
EOF

echo ""
echo "Example 3: Single commit, multiple tests"
echo "----------------------------------------"
cat <<'EOF'
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["6ea587e"],
    "tests": [
      "shell/_01_utility/_08_csql/cases/1001.sh",
      "shell/_01_utility/_08_csql/cases/1002.sh",
      "shell/_01_utility/_08_csql/cases/1003.sh"
    ],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIp": "localhost",
    "buildType": "debug"
  }'
EOF

echo ""
echo "Checking Results:"
echo "----------------"
echo "1. Check build status:"
echo "   curl http://localhost:8089/status"
echo ""
echo "2. View test reports:"
echo "   curl http://localhost:8089/report"
echo ""
echo "3. Check logs:"
echo "   tail -f ~/cubrid-testtools/CTP/builder_tester/log/system/builder.log"
echo ""
echo "4. View specific request logs:"
echo "   ls ~/cubrid-testtools/CTP/builder_tester/log/requests/"
echo ""

echo "Tips:"
echo "-----"
echo "- Use 'buildType': 'debug' for debug builds or 'release' for release builds"
echo "- The callbackUrl receives results when all tests complete"
echo "- Check health endpoints to verify services are running:"
echo "  curl http://localhost:8089/health"
echo "  curl http://localhost:8090/health"