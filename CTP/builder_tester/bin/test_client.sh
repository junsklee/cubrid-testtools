#!/bin/bash

# Test Client for Builder-Tester System

# Configuration
BUILDER_URL="http://localhost:8089"
CALLBACK_URL="http://localhost:8888/callback"  # Your callback endpoint
WORKER_IP="localhost"  # Where tester is running

# Test data
COMMITS='["abc123", "def456", "ghi789"]'  # Replace with real commit hashes
TESTS='["sql/_01_object/_09_partition/update/cases/1001.sh"]'  # Replace with real test paths

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
