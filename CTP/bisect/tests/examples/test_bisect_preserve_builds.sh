#!/bin/bash
#
# Test script for the bisect workflow with build preservation
# This script demonstrates how to keep build files for inspection
#

# Configuration
PRODUCER_HOST="${PRODUCER_HOST:-localhost}"
PRODUCER_PORT="${PRODUCER_PORT:-8089}"
WORKER_IP="${WORKER_IP:-$(hostname -I | awk '{print $1}')}"
CALLBACK_URL="${CALLBACK_URL:-http://localhost:8080/bisect/result}"

# Build cleanup control - PRESERVE BUILD FILES
AUTO_DELETE_BUILDS="false"

# Test data - using a simple single test for quick verification
SUSPECTED_START_COMMIT="bb2cc88"  # First suspected bad commit (older)
SUSPECTED_END_COMMIT="e4c8127"   # Last suspected bad commit (newer)

# Display configuration
echo "Bisect Test Script with Build Preservation"
echo "=========================================="
echo "Producer: http://${PRODUCER_HOST}:${PRODUCER_PORT}/bisect"
echo "Worker IP: ${WORKER_IP}"
echo "Callback URL: ${CALLBACK_URL}"
echo "Suspected commit range: ${SUSPECTED_START_COMMIT}...${SUSPECTED_END_COMMIT}"
echo "Auto-delete builds: ${AUTO_DELETE_BUILDS} (BUILD FILES WILL BE PRESERVED)"
echo

# JSON request payload with single test for faster execution
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

echo "Sending bisect request with build preservation..."
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
    echo "Build files will be preserved in the working directory under /tmp/bisect_work/"
    echo "Monitor producer logs for progress."
    
    # Extract task ID if available
    TASK_ID=$(echo "$BODY" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    if [ -n "$TASK_ID" ]; then
        echo "Task ID: $TASK_ID"
    fi
    
    echo
    echo "To check preserved build files after completion:"
    echo "find /tmp/bisect_work/ -name 'bisect_*' -type d -exec ls -la {} \;"
else
    echo
    echo "Request failed!"
fi