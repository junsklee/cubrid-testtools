#!/bin/bash

# Test client for the report callback functionality
# This script sends a test request and uses the callback URL to receive and view results

BUILDER_HOST="${BUILDER_HOST:-localhost}"
BUILDER_PORT="${BUILDER_PORT:-8089}"
CALLBACK_PORT="${CALLBACK_PORT:-8089}"

# Generate a unique test ID
TEST_ID="test_$(date +%s)"

echo "========================================="
echo "CUBRID Builder-Tester Report Test Client"
echo "========================================="
echo ""
echo "Builder endpoint: http://${BUILDER_HOST}:${BUILDER_PORT}"
echo "Callback URL: http://${BUILDER_HOST}:${CALLBACK_PORT}/callback"
echo "Test ID: ${TEST_ID}"
echo ""

# Create test request with callback URL
cat > /tmp/test_request_${TEST_ID}.json <<EOF
{
  "commits": ["6ea587e", "a1b2c3d"],
  "tests": [
    "shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh",
    "shell/_01_utility/_38_csql/csql_basic/cases/basic_test.sh"
  ],
  "callbackUrl": "http://${BUILDER_HOST}:${CALLBACK_PORT}/callback",
  "workerIp": "localhost",
  "buildType": "debug"
}
EOF

echo "Sending build request..."
echo "Request payload:"
cat /tmp/test_request_${TEST_ID}.json | python3 -m json.tool
echo ""

# Send the request
RESPONSE=$(curl -s -X POST "http://${BUILDER_HOST}:${BUILDER_PORT}/build" \
  -H "Content-Type: application/json" \
  -d @/tmp/test_request_${TEST_ID}.json)

echo "Response:"
echo "${RESPONSE}" | python3 -m json.tool

# Extract task ID if available
TASK_ID=$(echo "${RESPONSE}" | python3 -c "import sys, json; print(json.load(sys.stdin).get('taskId', ''))" 2>/dev/null)

if [ -n "${TASK_ID}" ]; then
    echo ""
    echo "Task ID: ${TASK_ID}"
    echo ""
    echo "You can check the status at: http://${BUILDER_HOST}:${BUILDER_PORT}/status"
    echo ""
    echo "When the build completes, the results will be sent to the callback URL."
    echo "You can view all reports at: http://${BUILDER_HOST}:${BUILDER_PORT}/report"
    echo ""
    echo "To view a specific report: http://${BUILDER_HOST}:${BUILDER_PORT}/report?id=<request_id>"
else
    echo ""
    echo "Failed to get task ID from response"
fi

# Clean up
rm -f /tmp/test_request_${TEST_ID}.json

echo ""
echo "Test client completed."
