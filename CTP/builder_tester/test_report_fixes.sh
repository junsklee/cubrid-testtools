#!/bin/bash

# Test script to verify the report fixes are working correctly
# This tests the status colors and back button functionality

echo "=================================="
echo "Testing Report Display Fixes"
echo "=================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BUILDER_PORT=8089
TESTER_PORT=8090

echo -e "${YELLOW}1. Checking if services are running...${NC}"

# Check Builder
curl -s http://localhost:${BUILDER_PORT}/health > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Builder is running${NC}"
else
    echo -e "${RED}✗ Builder is not running. Start with: ./bin/start_builder.sh${NC}"
    exit 1
fi

# Check Tester
curl -s http://localhost:${TESTER_PORT}/health > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Tester is running${NC}"
else
    echo -e "${RED}✗ Tester is not running. Start with: ./bin/start_tester.sh${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}2. Creating test request with retries to generate multiple attempts...${NC}"

REQUEST_ID="test_report_$(date +%Y%m%d_%H%M%S)"

# Create a request that will likely fail and retry
REQUEST_JSON=$(cat <<EOF
{
  "commits": ["HEAD"],
  "tests": [
    "shell/_01_utility/_01_csql/answer/basic_01.sh",
    "shell/_01_utility/_01_csql/answer/csql_hist.sh"
  ],
  "callbackUrl": "http://localhost:${BUILDER_PORT}/callback",
  "workerIp": "localhost",
  "buildType": "debug",
  "requestId": "${REQUEST_ID}",
  "testRetryCount": 2
}
EOF
)

echo "Request ID: ${REQUEST_ID}"
echo "Sending test request with retry count of 2..."

RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d "${REQUEST_JSON}" \
  http://localhost:${BUILDER_PORT}/build)

TASK_ID=$(echo ${RESPONSE} | grep -o '"taskId":"[^"]*' | cut -d'"' -f4)

if [ -z "$TASK_ID" ]; then
    echo -e "${RED}✗ Failed to create task${NC}"
    echo "Response: ${RESPONSE}"
    exit 1
fi

echo -e "${GREEN}✓ Task created: ${TASK_ID}${NC}"

echo ""
echo -e "${YELLOW}3. Waiting for task completion...${NC}"

# Monitor task
MAX_WAIT=300
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT ]; do
    STATUS=$(curl -s "http://localhost:${BUILDER_PORT}/status?taskId=${TASK_ID}")
    
    if echo "${STATUS}" | grep -q '"status":"not_found"'; then
        echo -e "${GREEN}✓ Task completed${NC}"
        break
    fi
    
    echo -n "."
    sleep 5
    ELAPSED=$((ELAPSED + 5))
done

if [ $ELAPSED -ge $MAX_WAIT ]; then
    echo -e "${RED}✗ Task timed out${NC}"
    exit 1
fi

echo ""
echo -e "${YELLOW}4. Checking report generation...${NC}"

REPORT_FILE="${HOME}/cubrid-testtools/CTP/builder_tester/log/requests/${TASK_ID}/report.html"

if [ ! -f "$REPORT_FILE" ]; then
    echo -e "${RED}✗ Report not generated${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Report generated${NC}"

echo ""
echo -e "${YELLOW}5. Verifying report content...${NC}"

# Check for attemptLogMetadata in results
RESULTS_FILE="${HOME}/cubrid-testtools/CTP/builder_tester/log/requests/${TASK_ID}/results.json"
if [ -f "$RESULTS_FILE" ]; then
    if grep -q "attemptLogMetadata" "$RESULTS_FILE"; then
        echo -e "${GREEN}✓ Attempt metadata found in results${NC}"
    else
        echo -e "${YELLOW}⚠ No attempt metadata in results (may be using old format)${NC}"
    fi
fi

# Check for proper status handling in report
if grep -q "status === 'pass'" "$REPORT_FILE"; then
    echo -e "${GREEN}✓ Report has proper status detection logic${NC}"
else
    echo -e "${RED}✗ Report missing proper status detection${NC}"
fi

# Check for back button fix
if grep -q "Back to Log List" "$REPORT_FILE"; then
    echo -e "${GREEN}✓ Back button text found${NC}"
else
    echo -e "${YELLOW}⚠ Back button text not found${NC}"
fi

# Check for status badges
if grep -q "statusBadge" "$REPORT_FILE"; then
    echo -e "${GREEN}✓ Status badges implemented${NC}"
else
    echo -e "${YELLOW}⚠ Status badges not found${NC}"
fi

echo ""
echo -e "${YELLOW}6. Checking log files...${NC}"

LOG_DIR="${HOME}/cubrid-testtools/CTP/builder_tester/log/requests/${TASK_ID}/tests"
if [ -d "$LOG_DIR" ]; then
    LOG_COUNT=$(ls -1 ${LOG_DIR}/*.log 2>/dev/null | wc -l)
    echo -e "${GREEN}✓ Found ${LOG_COUNT} log file(s)${NC}"
    
    # Check for multiple attempt logs
    if ls ${LOG_DIR}/*.2.log 2>/dev/null | head -1 > /dev/null; then
        echo -e "${GREEN}✓ Multiple attempt logs found (retries working)${NC}"
    else
        echo -e "${YELLOW}⚠ No retry logs found (tests may have passed on first attempt)${NC}"
    fi
else
    echo -e "${RED}✗ Log directory not found${NC}"
fi

echo ""
echo "=================================="
echo -e "${BLUE}Report URL: http://localhost:${BUILDER_PORT}/report?id=${TASK_ID}${NC}"
echo "=================================="
echo ""
echo "Please open the report URL in a browser and verify:"
echo "1. Test status colors are correct (green=pass, red=fail, orange=error)"
echo "2. Click on a test with multiple attempts"
echo "3. Verify each attempt shows the correct status"
echo "4. Click on an attempt to view the log"
echo "5. Verify the back button returns to the log list"
echo ""
echo -e "${GREEN}Test script completed!${NC}"
