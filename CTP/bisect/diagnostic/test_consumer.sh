#!/bin/bash

# Test script to check if the consumer is working properly

CONSUMER_HOST="${CONSUMER_HOST:-localhost}"
CONSUMER_PORT="${CONSUMER_PORT:-8090}"

echo "Testing Consumer at http://${CONSUMER_HOST}:${CONSUMER_PORT}/test"
echo "=================================================="

# Check if consumer is reachable
echo -n "1. Checking if consumer is listening on port ${CONSUMER_PORT}... "
if nc -z ${CONSUMER_HOST} ${CONSUMER_PORT} 2>/dev/null; then
    echo "OK"
else
    echo "FAILED - Consumer is not reachable!"
    echo "   Make sure the consumer is running on port ${CONSUMER_PORT}"
    exit 1
fi

# Create a test request
cat > test_request.json << 'EOF'
{
  "buildPackage": "/tmp/dummy-cubrid.tar.gz",
  "testPath": "shell/test/dummy.sh",
  "testDir": "/tmp/test",
  "testScript": "dummy.sh",
  "testName": "dummy_test"
}
EOF

echo
echo "2. Sending test request to consumer..."
echo "Request payload:"
cat test_request.json | jq . 2>/dev/null || cat test_request.json
echo

# Send the request
RESPONSE=$(curl -v -w "\nHTTP_STATUS:%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d @test_request.json \
  "http://${CONSUMER_HOST}:${CONSUMER_PORT}/test" 2>&1)

echo "Full response:"
echo "$RESPONSE"
echo

# Extract HTTP status
HTTP_STATUS=$(echo "$RESPONSE" | grep "HTTP_STATUS:" | cut -d: -f2)
echo "HTTP Status: $HTTP_STATUS"

# Clean up
rm -f test_request.json

echo
echo "Diagnostic complete."
