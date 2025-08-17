#!/bin/bash

# Test client for multiple tester nodes
# This demonstrates the new functionality to distribute tests across multiple tester nodes

BUILDER_HOST="${BUILDER_HOST:-localhost}"
BUILDER_PORT="${BUILDER_PORT:-8089}"

# Example request with multiple tester nodes
# Adjust the IP addresses to match your actual tester nodes
cat <<EOF | curl -X POST "http://${BUILDER_HOST}:${BUILDER_PORT}/build" \
  -H "Content-Type: application/json" \
  -d @-
{
  "commits": ["6ea587e", "1609a3a"],
  "tests": [
    "shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh",
    "shell/_01_utility/_08_csql/cases/1001.sh",
    "shell/_01_utility/_08_csql/cases/1002.sh",
    "shell/_01_utility/_08_csql/cases/1003.sh",
    "shell/_01_utility/_08_csql/cases/1004.sh",
    "shell/_01_utility/_08_csql/cases/1005.sh"
  ],
  "callbackUrl": "http://localhost:8089/callback",
  "workerIps": ["localhost", "192.168.1.10", "192.168.1.11"],
  "buildType": "debug"
}
EOF

echo ""
echo "Request sent. Check builder.log for test distribution details."
echo "You should see each test being assigned to different tester nodes in round-robin fashion."
