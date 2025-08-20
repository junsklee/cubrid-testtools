#!/bin/bash

# Test script for multipart form-data functionality
# This script tests the new multipart response handling in the Builder-Tester system

echo "=================================="
echo "Testing Multipart Response Feature"
echo "=================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
BUILDER_PORT=8089
TESTER_PORT=8090
BUILDER_HOST="localhost"
TESTER_HOST="localhost"

echo -e "${YELLOW}1. Checking if services are running...${NC}"

# Check if Builder is running
curl -s -o /dev/null -w "%{http_code}" http://${BUILDER_HOST}:${BUILDER_PORT}/health > /tmp/builder_status 2>/dev/null
BUILDER_STATUS=$(cat /tmp/builder_status)

if [ "$BUILDER_STATUS" = "200" ]; then
    echo -e "${GREEN}✓ Builder is running on port ${BUILDER_PORT}${NC}"
else
    echo -e "${RED}✗ Builder is not running on port ${BUILDER_PORT}${NC}"
    echo "  Please start Builder with: ./bin/start_builder.sh"
fi

# Check if Tester is running
curl -s -o /dev/null -w "%{http_code}" http://${TESTER_HOST}:${TESTER_PORT}/health > /tmp/tester_status 2>/dev/null
TESTER_STATUS=$(cat /tmp/tester_status)

if [ "$TESTER_STATUS" = "200" ]; then
    echo -e "${GREEN}✓ Tester is running on port ${TESTER_PORT}${NC}"
else
    echo -e "${RED}✗ Tester is not running on port ${TESTER_PORT}${NC}"
    echo "  Please start Tester with: ./bin/start_tester.sh"
fi

if [ "$BUILDER_STATUS" != "200" ] || [ "$TESTER_STATUS" != "200" ]; then
    echo ""
    echo -e "${RED}Cannot proceed with tests. Please start both services.${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}2. Sending test request with retry enabled...${NC}"

# Create a test request with retry enabled to generate multiple log files
REQUEST_ID="test_multipart_$(date +%Y%m%d_%H%M%S)"
TEST_REQUEST='{
  "commits": ["HEAD"],
  "tests": ["shell/_01_utility/_01_csql/answer/basic_01.sh"],
  "callbackUrl": "http://localhost:8089/callback",
  "workerIp": "localhost",
  "buildType": "debug",
  "requestId": "'${REQUEST_ID}'"
}'

echo "Request ID: ${REQUEST_ID}"
echo "Sending test request..."

# Send the request
RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d "${TEST_REQUEST}" \
  http://${BUILDER_HOST}:${BUILDER_PORT}/build)

echo "Response: ${RESPONSE}"

# Extract task ID
TASK_ID=$(echo ${RESPONSE} | grep -o '"taskId":"[^"]*' | cut -d'"' -f4)

if [ -z "$TASK_ID" ]; then
    echo -e "${RED}✗ Failed to get task ID from response${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Task created: ${TASK_ID}${NC}"

echo ""
echo -e "${YELLOW}3. Monitoring task progress...${NC}"

# Monitor the task for up to 5 minutes
MAX_WAIT=300
ELAPSED=0
INTERVAL=5

while [ $ELAPSED -lt $MAX_WAIT ]; do
    STATUS_RESPONSE=$(curl -s "http://${BUILDER_HOST}:${BUILDER_PORT}/status?taskId=${TASK_ID}")
    
    if echo "${STATUS_RESPONSE}" | grep -q '"status":"not_found"'; then
        echo -e "${GREEN}✓ Task completed${NC}"
        break
    fi
    
    echo "Task still running... (${ELAPSED}s elapsed)"
    sleep $INTERVAL
    ELAPSED=$((ELAPSED + INTERVAL))
done

if [ $ELAPSED -ge $MAX_WAIT ]; then
    echo -e "${RED}✗ Task timed out after ${MAX_WAIT} seconds${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}4. Checking log files...${NC}"

# Check if log files were created
LOG_DIR="${HOME}/cubrid-testtools/CTP/builder_tester/log/requests/${TASK_ID}"

if [ -d "$LOG_DIR" ]; then
    echo -e "${GREEN}✓ Request log directory exists: ${LOG_DIR}${NC}"
    
    # Check for test logs
    TEST_LOG_DIR="${LOG_DIR}/tests"
    if [ -d "$TEST_LOG_DIR" ]; then
        echo -e "${GREEN}✓ Test log directory exists${NC}"
        
        # Count log files
        LOG_COUNT=$(ls -1 ${TEST_LOG_DIR}/*.log 2>/dev/null | wc -l)
        echo "  Found ${LOG_COUNT} log file(s):"
        
        for log in ${TEST_LOG_DIR}/*.log; do
            if [ -f "$log" ]; then
                SIZE=$(ls -lh "$log" | awk '{print $5}')
                echo "    - $(basename $log) (${SIZE})"
            fi
        done
    else
        echo -e "${RED}✗ Test log directory not found${NC}"
    fi
    
    # Check the builder.log for multipart mentions
    BUILDER_LOG="${LOG_DIR}/builder.log"
    if [ -f "$BUILDER_LOG" ]; then
        if grep -q "multipart" "$BUILDER_LOG"; then
            echo -e "${GREEN}✓ Multipart handling detected in builder.log${NC}"
            grep "multipart" "$BUILDER_LOG" | head -3
        else
            echo -e "${YELLOW}⚠ No multipart mentions in builder.log (might be using JSON fallback)${NC}"
        fi
    fi
else
    echo -e "${RED}✗ Request log directory not found: ${LOG_DIR}${NC}"
fi

echo ""
echo -e "${YELLOW}5. Checking report generation...${NC}"

# Check if report was generated
REPORT_FILE="${LOG_DIR}/report.html"
if [ -f "$REPORT_FILE" ]; then
    echo -e "${GREEN}✓ Report generated: ${REPORT_FILE}${NC}"
    
    # Check if report contains log references
    if grep -q "log_attempt" "$REPORT_FILE"; then
        echo -e "${GREEN}✓ Report contains references to multiple attempt logs${NC}"
    else
        echo -e "${YELLOW}⚠ Report may not contain multiple attempt log references${NC}"
    fi
else
    echo -e "${RED}✗ Report not found: ${REPORT_FILE}${NC}"
fi

echo ""
echo "=================================="
echo "Test Summary"
echo "=================================="

if [ -f "$REPORT_FILE" ] && [ $LOG_COUNT -gt 0 ]; then
    echo -e "${GREEN}✓ Multipart test completed successfully${NC}"
    echo ""
    echo "You can view the report at:"
    echo "  http://localhost:8089/report?id=${TASK_ID}"
else
    echo -e "${YELLOW}⚠ Test completed with warnings${NC}"
    echo "  Please check the logs for more details"
fi

# Cleanup
rm -f /tmp/builder_status /tmp/tester_status

echo ""
echo "Test complete!"
