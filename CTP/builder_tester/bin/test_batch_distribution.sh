#!/bin/bash

# Example demonstrating build-based batch distribution
# Each batch = one build + all tests

BUILDER_HOST="${BUILDER_HOST:-localhost}"
BUILDER_PORT="${BUILDER_PORT:-8089}"

echo "Build-Based Batch Testing Example"
echo "================================="
echo ""
echo "This example shows how builds (not individual tests) are distributed to workers."
echo ""

# Example 1: 3 commits, 5 tests, 2 workers
echo "Example 1: 3 commits × 5 tests across 2 workers"
echo "-------------------------------------------------"
cat <<EOF | curl -X POST "http://${BUILDER_HOST}:${BUILDER_PORT}/build" \
  -H "Content-Type: application/json" \
  -d @-
{
  "commits": ["6ea587e", "1609a3a", "8dae125"],
  "tests": [
    "shell/_01_utility/_08_csql/cases/1001.sh",
    "shell/_01_utility/_08_csql/cases/1002.sh",
    "shell/_01_utility/_08_csql/cases/1003.sh",
    "shell/_01_utility/_08_csql/cases/1004.sh",
    "shell/_01_utility/_08_csql/cases/1005.sh"
  ],
  "callbackUrl": "http://localhost:8089/callback",
  "workerIps": ["localhost", "192.168.1.10"],
  "buildType": "debug"
}
EOF

echo ""
echo ""
echo "Expected Distribution:"
echo "----------------------"
echo "Batch 1: Commit 6ea587e + all 5 tests → localhost (priority)"
echo "Batch 2: Commit 1609a3a + all 5 tests → 192.168.1.10"
echo "Batch 3: Commit 8dae125 + all 5 tests → localhost"
echo ""
echo "Total: 3 containers, 15 test executions (3 commits × 5 tests)"
echo ""
echo "Check builder.log to verify:"
echo "  grep 'Assigning batch' ~/cubrid-testtools/CTP/builder_tester/log/system/builder.log"
