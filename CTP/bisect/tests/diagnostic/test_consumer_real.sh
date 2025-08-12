#!/bin/bash

# Test script to check if the consumer is working properly with real data
# This test builds a real CUBRID package and sends it to the consumer

CONSUMER_HOST="${CONSUMER_HOST:-localhost}"
CONSUMER_PORT="${CONSUMER_PORT:-8090}"
PRODUCER_HOST="${PRODUCER_HOST:-localhost}"
PRODUCER_PORT="${PRODUCER_PORT:-8089}"
CUBRID_SRC="${CUBRID_SRC:-~/cubrid-src}"
SHELL_TC_DIR="${SHELL_TC_DIR:-~/cubrid-testcases-private-ex}"

echo "Testing Consumer with Real Data"
echo "================================"
echo "Consumer: http://${CONSUMER_HOST}:${CONSUMER_PORT}/test"
echo "Producer: http://${PRODUCER_HOST}:${PRODUCER_PORT}/bisect"
echo "CUBRID Source: ${CUBRID_SRC}"
echo "Shell TC Dir: ${SHELL_TC_DIR}"
echo

# Check if consumer is reachable
echo -n "1. Checking if consumer is listening on port ${CONSUMER_PORT}... "
if nc -z ${CONSUMER_HOST} ${CONSUMER_PORT} 2>/dev/null; then
    echo "OK"
else
    echo "FAILED - Consumer is not reachable!"
    echo "   Make sure the consumer is running on port ${CONSUMER_PORT}"
    exit 1
fi

# Check if producer is reachable
echo -n "2. Checking if producer is listening on port ${PRODUCER_PORT}... "
if nc -z ${PRODUCER_HOST} ${PRODUCER_PORT} 2>/dev/null; then
    echo "OK"
else
    echo "FAILED - Producer is not reachable!"
    echo "   Make sure the producer is running on port ${PRODUCER_PORT}"
    exit 1
fi

# Check if directories exist
echo -n "3. Checking if CUBRID source directory exists... "
if [ -d "${CUBRID_SRC}" ]; then
    echo "OK"
else
    echo "FAILED - CUBRID source directory not found: ${CUBRID_SRC}"
    exit 1
fi

echo -n "4. Checking if shell test cases directory exists... "
if [ -d "${SHELL_TC_DIR}" ]; then
    echo "OK"
else
    echo "FAILED - Shell test cases directory not found: ${SHELL_TC_DIR}"
    exit 1
fi

# Get current commit hash
echo -n "5. Getting current commit hash... "
cd "${CUBRID_SRC}"
COMMIT_HASH=$(git rev-parse HEAD 2>/dev/null)
if [ $? -eq 0 ]; then
    echo "OK (${COMMIT_HASH:0:7})"
else
    echo "FAILED - Could not get commit hash"
    exit 1
fi

# Create a simple test case for testing
echo -n "6. Creating test build package... "
WORK_DIR="/tmp/consumer_test_$$"
mkdir -p "${WORK_DIR}"

# Use the producer to build a package
BUILD_REQUEST=$(cat << EOF
{
  "suspectedStartCommit": "${COMMIT_HASH:0:7}",
  "suspectedEndCommit": "${COMMIT_HASH:0:7}",
  "buildType": "debug",
  "workerIp": "localhost",
  "tests": [
    "shell/_01_utility/_01_sqlx/bug_cubrid2125/cases/bug_cubrid2125.sh"
  ],
  "callbackUrl": "http://localhost:8080/bisect/result",
  "autoDeleteBuilds": false,
  "originIp": "$(hostname -I | awk '{print $1}')"
}
EOF
)

# Send build request to producer
BUILD_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d "$BUILD_REQUEST" \
  "http://${PRODUCER_HOST}:${PRODUCER_PORT}/bisect")

BUILD_HTTP_STATUS=$(echo "$BUILD_RESPONSE" | tail -n1 | cut -d: -f2)
BUILD_BODY=$(echo "$BUILD_RESPONSE" | sed '$d')

if [ "$BUILD_HTTP_STATUS" = "202" ]; then
    echo "OK"
    echo "   Build request accepted by producer"
    
    # Extract task ID
    TASK_ID=$(echo "$BUILD_BODY" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    echo "   Task ID: $TASK_ID"
    
    # Wait for build to complete (check for build package)
    echo -n "7. Waiting for build to complete... "
    for i in {1..30}; do
        if [ -f "/tmp/bisect_work/results/bisect_result_*_${TASK_ID}.json" ]; then
            echo "OK"
            break
        fi
        if [ $i -eq 30 ]; then
            echo "TIMEOUT - Build did not complete within 30 seconds"
            exit 1
        fi
        sleep 1
    done
    
    # Find the build package
    BUILD_PACKAGE=$(find /tmp/bisect_work -name "cubrid_${COMMIT_HASH:0:7}.tar.gz" 2>/dev/null | head -1)
    if [ -n "$BUILD_PACKAGE" ] && [ -f "$BUILD_PACKAGE" ]; then
        echo "   Build package found: $BUILD_PACKAGE"
    else
        echo "   Build package not found, creating dummy package for testing"
        # Create a dummy package for testing
        BUILD_PACKAGE="${WORK_DIR}/dummy-cubrid.tar.gz"
        tar czf "$BUILD_PACKAGE" -C /tmp --files-from /dev/null 2>/dev/null
    fi
else
    echo "FAILED - Build request failed: $BUILD_BODY"
    echo "   Creating dummy package for testing"
    BUILD_PACKAGE="${WORK_DIR}/dummy-cubrid.tar.gz"
    tar czf "$BUILD_PACKAGE" -C /tmp --files-from /dev/null 2>/dev/null
fi

# Find a real test case
echo -n "8. Finding a real test case... "
TEST_CASE=$(find "${SHELL_TC_DIR}/shell" -name "*.sh" | grep -E "(_01_|_02_)" | head -1)
if [ -n "$TEST_CASE" ] && [ -f "$TEST_CASE" ]; then
    echo "OK"
    echo "   Test case: $TEST_CASE"
    
    # Extract test path relative to shell directory
    TEST_PATH=$(echo "$TEST_CASE" | sed "s|${SHELL_TC_DIR}/||")
    TEST_DIR=$(dirname "$TEST_CASE")
    TEST_SCRIPT=$(basename "$TEST_CASE")
    TEST_NAME=$(basename "$TEST_CASE" .sh)
else
    echo "FAILED - No test case found"
    exit 1
fi

# Create a real test request
echo -n "9. Creating test request... "
cat > "${WORK_DIR}/test_request.json" << EOF
{
  "buildPackage": "${BUILD_PACKAGE}",
  "testPath": "${TEST_PATH}",
  "testDir": "${TEST_DIR}",
  "testScript": "${TEST_SCRIPT}",
  "testName": "${TEST_NAME}"
}
EOF

echo "OK"
echo "Request payload:"
cat "${WORK_DIR}/test_request.json" | jq . 2>/dev/null || cat "${WORK_DIR}/test_request.json"
echo

# Send the request to consumer
echo "10. Sending test request to consumer..."
echo "   This will trigger Docker execution and real test case execution"
echo

RESPONSE=$(curl -v -w "\nHTTP_STATUS:%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d @"${WORK_DIR}/test_request.json" \
  "http://${CONSUMER_HOST}:${CONSUMER_PORT}/test" 2>&1)

echo
echo "Full response:"
echo "$RESPONSE"
echo

# Extract HTTP status
HTTP_STATUS=$(echo "$RESPONSE" | grep "HTTP_STATUS:" | cut -d: -f2)
echo "HTTP Status: $HTTP_STATUS"

# Parse response
if echo "$RESPONSE" | grep -q '"status":"pass"'; then
    echo "✅ Test PASSED - Consumer executed test successfully"
elif echo "$RESPONSE" | grep -q '"status":"fail"'; then
    echo "❌ Test FAILED - Consumer executed test but it failed"
elif echo "$RESPONSE" | grep -q '"status":"error"'; then
    echo "⚠️  Test ERROR - Consumer encountered an error"
    echo "   This might indicate Docker or environment issues"
else
    echo "❓ Unknown response - Could not determine test status"
fi

# Clean up
echo
echo "11. Cleaning up..."
rm -rf "${WORK_DIR}"
echo "   Temporary files removed"

echo
echo "Diagnostic complete."
echo "Check consumer logs for detailed execution information." 