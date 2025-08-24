#!/bin/bash

# Test script for verifying unified run configuration (min_runs/max_runs) and run_mode
# This script tests all three run modes with different run bounds

BUILDER_URL="http://localhost:8089"
TESTER_URL="http://localhost:8090"

echo "=== Testing Retry Count and Run Mode Configuration ==="
echo

# Function to send test request
send_test_request() {
    local mode=$1
    local min_runs=$2
    local max_runs=$3
    local test_name=$4
    
    echo "Testing with run_mode=$mode, min_runs=$min_runs, max_runs=$max_runs"
    
    # Create a test request
    cat > /tmp/test_request.json <<EOF
{
    "commits": ["6ea587e"],
    "tests": ["shell/_01_utility/_38_csql/csql_hist/cases/${test_name}.sh"],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIp": "localhost",
    "buildType": "debug",
    "run_mode": "${mode}",
    "min_runs": ${min_runs},
    "max_runs": ${max_runs}
}
EOF
    
    # Update builder.conf temporarily
    echo "Updating builder.conf with run_mode=$mode, min_runs=$min_runs, max_runs=$max_runs..."
    sed -i.bak "s/^run_mode=.*/run_mode=$mode/" conf/builder.conf
    if grep -q "^min_runs=" conf/builder.conf; then
        sed -i.bak "s/^min_runs=.*/min_runs=$min_runs/" conf/builder.conf
    else
        echo "min_runs=$min_runs" >> conf/builder.conf
    fi
    if grep -q "^max_runs=" conf/builder.conf; then
        sed -i.bak "s/^max_runs=.*/max_runs=$max_runs/" conf/builder.conf
    else
        echo "max_runs=$max_runs" >> conf/builder.conf
    fi
    
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
            grep -E "(run_mode|min_runs|max_runs|attempt)" log/requests/$TASK_ID/tests/*.log | head -20
        fi
    fi
    
    echo
    echo "---"
    echo
}

# Test 1: until-pass mode with min_runs=1, max_runs=3
echo "Test 1: until-pass mode (default behavior)"
send_test_request "until-pass" 1 3 "csql_hist"

# Test 2: until-fail mode with min_runs=1, max_runs=5 (reproduce mode)
echo "Test 2: until-fail mode (reproduce failures)"
send_test_request "until-fail" 1 5 "csql_hist"

# Test 3: fixed-runs mode with min_runs=max_runs=3
echo "Test 3: fixed-runs mode (run exactly N times)"
send_test_request "fixed-runs" 3 3 "csql_hist"

# Restore original config
echo "Restoring original builder.conf..."
if [ -f conf/builder.conf.bak ]; then
    mv conf/builder.conf.bak conf/builder.conf
fi

echo
echo "=== Test Complete ==="
echo "Check the logs in log/requests/ for detailed execution information"
