#!/bin/bash
#
# Two-commit fast path test for the bisect workflow
# Mirrors the latest test structure (see test_single_commit.sh)
# Uses adjacent commits provided by the user to exercise the fast path
#

# Configuration
PRODUCER_HOST="${PRODUCER_HOST:-localhost}"
PRODUCER_PORT="${PRODUCER_PORT:-8089}"
WORKER_IP="${WORKER_IP:-$(hostname -I | awk '{print $1}')}"
CALLBACK_URL="${CALLBACK_URL:-http://localhost:8080/bisect/result}"

# Build cleanup control
# Set to "false" to preserve build files for inspection
AUTO_DELETE_BUILDS="${AUTO_DELETE_BUILDS:-true}"

# Two commits for fast path (provided)
SUSPECTED_START_COMMIT="bc6b0aa"
SUSPECTED_END_COMMIT="f9ad752"

# Display configuration
echo "Two-Commit Fast Path Test"
echo "=========================="
echo "Producer: http://${PRODUCER_HOST}:${PRODUCER_PORT}/bisect"
echo "Worker IP: ${WORKER_IP}"
echo "Callback URL: ${CALLBACK_URL}"
echo "Suspected commit range: ${SUSPECTED_START_COMMIT}...${SUSPECTED_END_COMMIT}"
echo "Auto-delete builds: ${AUTO_DELETE_BUILDS}"
echo

# JSON request payload
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

echo "Sending bisect request (two-commit fast path)..."
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
    TASK_ID=$(echo "$BODY" | grep -o '"taskId":"[^\"]*"' | cut -d'"' -f4)
    if [ -n "$TASK_ID" ]; then
        echo "Task ID: $TASK_ID"
    fi
else
    echo
    echo "Request failed!"
    exit 1
fi