#!/bin/bash

# Test script to verify single commit distribution fix
# This script simulates a single commit with multiple tests and multiple workers

echo "==============================================="
echo "Single Commit Distribution Test"
echo "==============================================="

# Test configuration
BUILDER_URL="http://localhost:8089"
CALLBACK_URL="http://localhost:8089/callback"

# Single commit test with multiple workers
echo "Testing single commit with multiple workers..."

# Create test request with one commit and multiple tests
cat > /tmp/single_commit_test.json << 'EOF'
{
  "commits": ["b8505c4d1bc0eae850e974eb35249224ce53a1c9"],
  "tests": [
    "shell/_06_issues/_12_2h/bug_bts_9521_1/cases/bug_bts_9521_1.sh",
    "shell/_06_issues/_17_1h/cbrd_20759/hide_utls_loaddb_password/cases/hide_utls_loaddb_password.sh",
    "shell/_06_issues/_21_1h/cbrd_23856/cases/cbrd_23856.sh",
    "shell/_35_cherry/issue_21654_server_side_loaddb/cbrd_23338_monetary/cases/cbrd_23338_monetary.sh",
    "shell/_10_plcsql/bug_fix/cbrd_25894/cases/cbrd_25894.sh"
  ],
  "callbackUrl": "http://localhost:8089/callback",
  "workerIps": ["localhost", "192.168.1.15:8090"],
  "buildType": "release"
}
EOF

echo "Request JSON:"
cat /tmp/single_commit_test.json
echo ""

# Send the request
echo "Sending request to Builder..."
curl -X POST $BUILDER_URL/build \
  -H "Content-Type: application/json" \
  -d @/tmp/single_commit_test.json \
  -w "\nHTTP Status: %{http_code}\n"

echo ""
echo "==============================================="
echo "Check the builder logs to verify distribution:"
echo "tail -f ~/cubrid-testtools/CTP/builder_tester/log/system/builder.log"
echo ""
echo "Look for these key log messages:"
echo "1. 'Single commit detected - ensuring even distribution'"
echo "2. 'Test distribution summary' showing even distribution"
echo "3. Each worker should get ~2 tests (6 tests / 3 workers)"
echo "==============================================="
