#!/bin/bash
#
# Single commit test script for the bisect workflow
# Tests only one commit to verify consumer is working and executing test scripts
#

# Configuration
PRODUCER_HOST="${PRODUCER_HOST:-localhost}"
PRODUCER_PORT="${PRODUCER_PORT:-8089}"
WORKER_IP="${WORKER_IP:-$(hostname -I | awk '{print $1}')}"
CALLBACK_URL="${CALLBACK_URL:-http://localhost:8080/bisect/result}"

# Build cleanup control
# Set to "false" to preserve build files for inspection
AUTO_DELETE_BUILDS="${AUTO_DELETE_BUILDS:-true}"

# Single commit test - using same commit for start and end to test only one commit
# This reduces test time and verifies consumer functionality
SINGLE_TEST_COMMIT="f89705b"  # Single commit to test
SUSPECTED_START_COMMIT="$SINGLE_TEST_COMMIT"
SUSPECTED_END_COMMIT="$SINGLE_TEST_COMMIT"

# Display configuration
echo "Single Commit Bisect Test Script"
echo "================================"
echo "Producer: http://${PRODUCER_HOST}:${PRODUCER_PORT}/bisect"
echo "Worker IP: ${WORKER_IP}"
echo "Callback URL: ${CALLBACK_URL}"
echo "Testing single commit: ${SINGLE_TEST_COMMIT}"
echo "Auto-delete builds: ${AUTO_DELETE_BUILDS}"
echo

# JSON request payload - using only one simple test to verify consumer execution
read -r -d '' JSON_PAYLOAD << EOF
{
  "suspectedStartCommit": "${SUSPECTED_START_COMMIT}",
  "suspectedEndCommit": "${SUSPECTED_END_COMMIT}",
  "buildType": "debug",
  "workerIp": "${WORKER_IP}",
  "tests": [
    "shell/_06_issues/_12_2h/bug_bts_7583/cases/bug_bts_7583.sh"
  ],
  "callbackUrl": "${CALLBACK_URL}",
  "autoDeleteBuilds": ${AUTO_DELETE_BUILDS},
  "originIp": "$(hostname -I | awk '{print $1}')"
}
EOF

echo "Sending single commit bisect request..."
echo

# Send the request
RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d "$JSON_PAYLOAD" \
  "http://${PRODUCER_HOST}:${PRODUCER_PORT}/bisect")

# Extract HTTP status code
HTTP_STATUS=$(echo "$RESPONSE" | tail -n1 | cut -d: -f2)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "Response: $BODY"
echo "HTTP Status: $HTTP_STATUS"

if [ "$HTTP_STATUS" = "202" ]; then
    echo
    echo "Request accepted. Results will be posted to: ${CALLBACK_URL}"
    echo "Monitor producer logs for progress."
    
    # Extract task ID if available
    TASK_ID=$(echo "$BODY" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    if [ -n "$TASK_ID" ]; then
        echo "Task ID: $TASK_ID"
    fi
    
    echo
    echo "This test verifies that the consumer:"
    echo "1. Receives the build package from producer"
    echo "2. Installs CUBRID"
    echo "3. Executes the test script from cubrid-testcases-private-ex"
    echo "4. Returns the result"
else
    echo
    echo "Request failed!"
fi 