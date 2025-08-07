#!/bin/bash
#
# Test script for the Docker producer/consumer bisect workflow
# This tests the Docker-based producer/consumer mode with container builds
#

# Configuration
PRODUCER_HOST="${PRODUCER_HOST:-localhost}"
PRODUCER_PORT="${PRODUCER_PORT:-8089}"
CONSUMER_HOST="${CONSUMER_HOST:-localhost}"
CONSUMER_PORT="${CONSUMER_PORT:-8090}"
CALLBACK_URL="${CALLBACK_URL:-http://localhost:8080/bisect/result}"

# Build cleanup control
AUTO_DELETE_BUILDS="${AUTO_DELETE_BUILDS:-true}"

# Test data - using commit range that should exist in CUBRID repository
SUSPECTED_START_COMMIT="b7160e2"  # First suspected bad commit (older)
SUSPECTED_END_COMMIT="f89705b"   # Last suspected bad commit (newer)

# Display configuration
echo "Docker Bisect Test Script"
echo "========================="
echo "Producer Service: http://${PRODUCER_HOST}:${PRODUCER_PORT}/bisect"
echo "Consumer Service: http://${CONSUMER_HOST}:${CONSUMER_PORT}/health"
echo "Callback URL: ${CALLBACK_URL}"
echo "Suspected commit range: ${SUSPECTED_START_COMMIT}...${SUSPECTED_END_COMMIT}"
echo "Auto-delete builds: ${AUTO_DELETE_BUILDS}"
echo

# JSON request payload for Docker producer/consumer mode
read -r -d '' JSON_PAYLOAD << EOF
{
  "suspectedStartCommit": "${SUSPECTED_START_COMMIT}",
  "suspectedEndCommit": "${SUSPECTED_END_COMMIT}",
  "buildType": "debug",
  "workerIp": "${CONSUMER_HOST}",
  "tests": [
    "shell/_06_issues/_12_2h/bug_bts_7583/cases/bug_bts_7583.sh",
    "shell/_06_issues/_14_1h/bug_bts_13331/cases/bug_bts_13331.sh"
  ],
  "callbackUrl": "${CALLBACK_URL}"
}
EOF

echo "Testing Docker environment prerequisites..."

# Check Docker availability
if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker command not found!"
    echo "Please install Docker and ensure it's in your PATH"
    exit 1
fi

# Check Docker daemon
if ! docker version &> /dev/null; then
    echo "ERROR: Docker daemon is not running or not accessible!"
    echo "Please start Docker daemon and ensure current user has access"
    exit 1
fi

echo "✓ Docker is available and accessible"

# Check for required Docker images
REQUIRED_IMAGES=("cubrid-bisect-builder:latest")
MISSING_IMAGES=()

for image in "${REQUIRED_IMAGES[@]}"; do
    if ! docker images --format "{{.Repository}}:{{.Tag}}" | grep -q "^${image}$"; then
        MISSING_IMAGES+=("$image")
    else
        echo "✓ Found Docker image: $image"
    fi
done

if [ ${#MISSING_IMAGES[@]} -gt 0 ]; then
    echo
    echo "WARNING: Missing Docker images:"
    for image in "${MISSING_IMAGES[@]}"; do
        echo "  - $image"
    done
    echo
    echo "Run the following to build missing images:"
    echo "  ./script/setup_docker.sh"
    echo
    echo "Continuing with test anyway (will fail if images are actually needed)..."
fi

echo

echo "Testing producer service health..."
PRODUCER_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" \
  "http://${PRODUCER_HOST}:${PRODUCER_PORT}/health" 2>/dev/null)

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to connect to producer service!"
    echo "Please start the producer with: ./script/start_producer.sh"
    exit 1
fi

# Extract HTTP status code
PRODUCER_STATUS=$(echo "$PRODUCER_RESPONSE" | tail -n1 | cut -d: -f2)
PRODUCER_BODY=$(echo "$PRODUCER_RESPONSE" | sed '$d')

echo "Producer Response: $PRODUCER_BODY"
echo "Producer Status: $PRODUCER_STATUS"

if [ "$PRODUCER_STATUS" != "200" ]; then
    echo
    echo "Producer service is not running or not healthy!"
    echo "Please start the producer with: ./script/start_producer.sh"
    exit 1
fi

echo "✓ Producer service is healthy"
echo

echo "Testing consumer service health..."
CONSUMER_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" \
  "http://${CONSUMER_HOST}:${CONSUMER_PORT}/health" 2>/dev/null)

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to connect to consumer service!"
    echo "Please start the consumer with: ./script/run_consumer.sh start"
    exit 1
fi

# Extract HTTP status code
CONSUMER_STATUS=$(echo "$CONSUMER_RESPONSE" | tail -n1 | cut -d: -f2)
CONSUMER_BODY=$(echo "$CONSUMER_RESPONSE" | sed '$d')

echo "Consumer Response: $CONSUMER_BODY"
echo "Consumer Status: $CONSUMER_STATUS"

if [ "$CONSUMER_STATUS" != "200" ]; then
    echo
    echo "Consumer service is not running or not healthy!"
    echo "Please start the consumer with: ./script/run_consumer.sh start"
    exit 1
fi

echo
echo "Sending Docker bisect request to producer..."
echo
echo "Request payload:"
echo "$JSON_PAYLOAD" | jq . 2>/dev/null || echo "$JSON_PAYLOAD"
echo

# Send the request
RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -d "$JSON_PAYLOAD" \
  "http://${PRODUCER_HOST}:${PRODUCER_PORT}/bisect")

# Extract HTTP status code
HTTP_STATUS=$(echo "$RESPONSE" | tail -n1 | cut -d: -f2)
BODY=$(echo "$RESPONSE" | sed '$d')

echo "Response: $BODY"
echo "HTTP Status: $HTTP_STATUS"

if [ "$HTTP_STATUS" = "202" ]; then
    echo
    echo "Request accepted. Producer will coordinate Docker-based builds and testing."
    echo "Results will be posted to: ${CALLBACK_URL}"
    echo
    echo "Monitor services:"
    echo "  Producer logs: Check producer service output"
    echo "  Consumer logs: Check consumer service output"
    echo "  Docker containers: docker ps (should show build containers during execution)"
    
    # Extract task ID if available
    TASK_ID=$(echo "$BODY" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    if [ -n "$TASK_ID" ]; then
        echo "  Task ID: $TASK_ID"
    fi
    
    echo
    echo "Expected workflow:"
    echo "1. Producer receives request and starts git bisect"
    echo "2. For each commit to test:"
    echo "   a. Producer builds CUBRID in Docker container (cubrid-bisect-builder)"
    echo "   b. Producer packages build and sends to Consumer"
    echo "   c. Consumer runs tests (optionally in Docker)"
    echo "   d. Consumer reports results back to Producer"
    echo "   e. Producer marks commit as good/bad and continues bisect"
    echo "3. Producer reports final result via callback"
    
    echo
    echo "Docker bisect test completed successfully!"
elif [ "$HTTP_STATUS" = "400" ]; then
    echo
    echo "Bad request! Check your JSON payload format."
    echo "Error details: $BODY"
    exit 1
elif [ "$HTTP_STATUS" = "500" ]; then
    echo
    echo "Server error! Check producer logs for details."
    echo "This could indicate:"
    echo "- Docker initialization failed"
    echo "- CUBRID source repository issues"
    echo "- Configuration problems"
    echo "Error details: $BODY"
    exit 1
else
    echo
    echo "Request failed with unexpected status: $HTTP_STATUS"
    echo "Response: $BODY"
    exit 1
fi