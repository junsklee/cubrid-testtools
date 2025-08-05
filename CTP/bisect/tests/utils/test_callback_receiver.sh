#!/bin/bash

# Test script for the callback receiver
# Usage: ./test_callback_receiver.sh [port]

PORT=${1:-8080}

echo "Testing Callback Receiver on port $PORT"
echo "======================================="

# Check if receiver is running
if ! curl -s http://localhost:$PORT/ >/dev/null 2>&1; then
    echo "❌ Callback receiver is not running on port $PORT"
    echo "Start it with: ./start_callback_receiver.sh $PORT"
    exit 1
fi

echo "✅ Callback receiver is running"

# Send a test request
echo "Sending test bisect result..."

RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}\n" -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "suspectedStartCommit": "abc123",
    "suspectedEndCommit": "def456", 
    "workerIp": "192.168.1.5",
    "generatedAt": "2025-08-06T00:15:00.000Z",
    "tests": [
      {
        "name": "shell/_06_issues/_12_2h/bug_bts_7583/cases/bug_bts_7583.sh",
        "status": "found",
        "firstBadCommit": "abc123def456",
        "author": "test-user <test@example.com>",
        "runtimeMs": 123456
      }
    ]
  }' \
  http://localhost:$PORT/bisect/result)

HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
BODY=$(echo "$RESPONSE" | grep -v "HTTP_CODE:")

echo "Response code: $HTTP_CODE"
echo "Response body: $BODY"

if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ Test successful!"
    echo "Check the callback receiver output to see the formatted result."
else
    echo "❌ Test failed with HTTP code: $HTTP_CODE"
    exit 1
fi