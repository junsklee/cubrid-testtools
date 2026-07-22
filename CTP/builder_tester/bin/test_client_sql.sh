#!/bin/bash

# Test Client for Builder-Tester System — SQL testcase mode
#
# Submits a build request with testType=sql. Test paths come from the
# https://github.com/CUBRID/cubrid-testcases repository and must match
# sql/**/cases/<name>.sql (the expected output lives at the sibling
# answers/<name>.answer). Cases are executed with plain CTP (latest develop)
# via builder-tester's own single-case runner inside Docker on the tester nodes.

# Configuration
BUILDER_URL="http://localhost:8089"
CALLBACK_URL="http://localhost:8091/callback"   # Report server callback endpoint
WORKER_IP="localhost"                           # Where tester is running

# Test data
COMMITS='["0d7296a"]'  # Replace with real CUBRID commit hashes
TESTS='["sql/_01_object/_01_type/_004_integer/cases/1014.sql", "sql/_04_operator_function/_05_arighmetic_op/_002_rand/cases/1002.sql"]'

# Create request JSON
REQUEST_JSON=$(cat <<EOF
{
    "commits": $COMMITS,
    "tests": $TESTS,
    "testType": "sql",
    "callbackUrl": "$CALLBACK_URL",
    "workerIp": "$WORKER_IP",
    "buildType": "debug",
    "runMode": "fixed-runs",
    "minRuns": 1,
    "maxRuns": 1
}
EOF
)

echo "Sending SQL build request to Builder service..."
echo "Request:"
echo "$REQUEST_JSON"

# Send request
RESPONSE=$(curl -s -X POST \
    -H "Content-Type: application/json" \
    -d "$REQUEST_JSON" \
    "$BUILDER_URL/build")

echo ""
echo "Response:"
echo "$RESPONSE"

# Extract task ID if successful
TASK_ID=$(echo "$RESPONSE" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)

if [ ! -z "$TASK_ID" ]; then
    echo ""
    echo "Task ID: $TASK_ID"
    echo ""
    echo "You can check status at: $BUILDER_URL/status?taskId=$TASK_ID"
fi
