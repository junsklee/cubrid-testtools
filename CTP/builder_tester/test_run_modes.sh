#!/bin/bash

# Test script for verifying retry_count and run_mode configurations
# This script tests all three run modes with different retry counts

BUILDER_URL="http://localhost:8089"
TESTER_URL="http://localhost:8090"

echo "=== Testing Retry Count and Run Mode Configuration ==="
echo

# Function to send test request
send_test_request() {
    local mode=$1
    local count=$2
    local test_name=$3
    
    echo "Testing with run_mode=$mode, retry_count=$count"
    
    # Create a test request
    cat > /tmp/test_request.json <<EOF
{
    "commits": ["6ea587e"],
    "tests": ["shell/_01_utility/_38_csql/csql_hist/cases/${test_name}.sh"],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIp": "localhost",
    "buildType": "debug"
}
EOF
    
    # Update builder.conf temporarily
    echo "Updating builder.conf with run_mode=$mode and retry_count=$count..."
    sed -i.bak "s/^run_mode=.*/run_mode=$mode/" conf/builder.conf
    sed -i.bak "s/^retry_count=.*/retry_count=$count/" conf/builder.conf
    
    # Send the request
    echo "Sending test request..."
    RESPONSE=$(curl -s -X POST $BUILDER_URL/build \
        -H "Content-Type: application/json" \
        -d @/tmp/test_request.json)
    
    echo "Response: $RESPONSE"
    
    # Extract task ID
    TASK_ID=$(echo $RESPONSE | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    echo "Task ID: $TASK_ID"
    
    if [ -n "$TASK_ID" ]; then
        # Wait for task to complete
        echo "Waiting for task to complete..."
        sleep 5
        
        # Check status
        STATUS=$(curl -s "$BUILDER_URL/status?taskId=$TASK_ID")
        echo "Status: $STATUS"
        
        # Check logs
        if [ -d "log/requests/$TASK_ID" ]; then
            echo "Checking test logs..."
            grep -E "(run_mode|retry|attempt)" log/requests/$TASK_ID/tests/*.log | head -20
        fi
    fi
    
    echo
    echo "---"
    echo
}

# Test 1: until-pass mode with retry_count=2
echo "Test 1: until-pass mode (default behavior)"
send_test_request "until-pass" 2 "csql_hist"

# Test 2: until-fail mode with retry_count=5 (reproduce mode)
echo "Test 2: until-fail mode (reproduce failures)"
send_test_request "until-fail" 5 "csql_hist"

# Test 3: fixed-runs mode with retry_count=3
echo "Test 3: fixed-runs mode (run exactly N times)"
send_test_request "fixed-runs" 3 "csql_hist"

# Restore original config
echo "Restoring original builder.conf..."
if [ -f conf/builder.conf.bak ]; then
    mv conf/builder.conf.bak conf/builder.conf
fi

echo
echo "=== Test Complete ==="
echo "Check the logs in log/requests/ for detailed execution information"
