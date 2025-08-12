#!/bin/bash

# Test Client for Builder-Tester System

# Configuration
BUILDER_URL="http://192.168.1.5:8089"
CALLBACK_URL="http://192.168.1.5:8089/callback"  # Your callback endpoint
WORKER_IP="192.168.1.5"  # Where tester is running

# Test data
COMMITS='["0d7296a", "dd32812", "6ea587e", "228cd61"]'  # Replace with real commit hashes
TESTS='["shell/_01_utility/_38_csql/csql_hist/cases/csql_hist.sh", "shell/_01_utility/_38_csql/csql_hist_01/cases/csql_hist_01.sh", "shell/_06_issues/_12_2h/bug_bts_9521_1/cases/bug_bts_9521_1.sh"]'

# Create request JSON
REQUEST_JSON=$(cat <<EOF
{
    "commits": $COMMITS,
    "tests": $TESTS,
    "callbackUrl": "$CALLBACK_URL",
    "workerIp": "$WORKER_IP",
    "buildType": "debug"
}
EOF
)

echo "Sending build request to Builder service..."
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
