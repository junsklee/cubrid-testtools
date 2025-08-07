#!/bin/bash
#
# Test script for the standalone bisect workflow
# This tests the standalone mode implementation without requiring actual Docker execution
#

# Configuration
STANDALONE_HOST="${STANDALONE_HOST:-localhost}"
STANDALONE_PORT="${STANDALONE_PORT:-8091}"
CALLBACK_URL="${CALLBACK_URL:-http://localhost:8080/bisect/result}"

# Build cleanup control
AUTO_DELETE_BUILDS="${AUTO_DELETE_BUILDS:-true}"

# Test data - using commit range that should exist in CUBRID repository
SUSPECTED_START_COMMIT="b7160e2"  # First suspected bad commit (older)
SUSPECTED_END_COMMIT="f89705b"   # Last suspected bad commit (newer)

# Display configuration
echo "Standalone Bisect Test Script"
echo "============================="
echo "Standalone Service: http://${STANDALONE_HOST}:${STANDALONE_PORT}/bisect"
echo "Callback URL: ${CALLBACK_URL}"
echo "Suspected commit range: ${SUSPECTED_START_COMMIT}...${SUSPECTED_END_COMMIT}"
echo "Auto-delete builds: ${AUTO_DELETE_BUILDS}"
echo

# JSON request payload for standalone mode
read -r -d '' JSON_PAYLOAD << EOF
{
  "suspectedStartCommit": "${SUSPECTED_START_COMMIT}",
  "suspectedEndCommit": "${SUSPECTED_END_COMMIT}",
  "buildType": "debug",
  "tests": [
    "shell/_06_issues/_12_2h/bug_bts_7583/cases/bug_bts_7583.sh",
    "shell/_06_issues/_14_1h/bug_bts_13331/cases/bug_bts_13331.sh"
  ],
  "callbackUrl": "${CALLBACK_URL}"
}
EOF

echo "Testing standalone service health..."
HEALTH_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" \
  "http://${STANDALONE_HOST}:${STANDALONE_PORT}/health")

# Extract HTTP status code
HEALTH_STATUS=$(echo "$HEALTH_RESPONSE" | tail -n1 | cut -d: -f2)
HEALTH_BODY=$(echo "$HEALTH_RESPONSE" | sed '$d')

echo "Health Response: $HEALTH_BODY"
echo "Health Status: $HEALTH_STATUS"

if [ "$HEALTH_STATUS" != "200" ]; then
    echo
    echo "Standalone service is not running or not healthy!"
    echo "Please start the service with: ./script/run_standalone.sh start"
    exit 1
fi

echo
echo "Sending standalone bisect request..."
echo

# Send the request
RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d "$JSON_PAYLOAD" \
  "http://${STANDALONE_HOST}:${STANDALONE_PORT}/bisect")

# Extract HTTP status code
HTTP_STATUS=$(echo "$RESPONSE" | tail -n1 | cut -d: -f2)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "Response: $BODY"
echo "HTTP Status: $HTTP_STATUS"

if [ "$HTTP_STATUS" = "202" ]; then
    echo
    echo "Request accepted. Results will be posted to: ${CALLBACK_URL}"
    echo "Monitor standalone service logs for progress."
    
    # Extract task ID if available
    TASK_ID=$(echo "$BODY" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    if [ -n "$TASK_ID" ]; then
        echo "Task ID: $TASK_ID"
    fi
    
    echo
    echo "Standalone bisect test completed successfully!"
else
    echo
    echo "Request failed!"
    exit 1
fi