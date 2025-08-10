#!/bin/bash
#
# API Test: Builder API Endpoints
# Tests all Builder service HTTP endpoints
#

set -e

# Source test helpers
source "$(dirname "$0")/../lib/test_helpers.sh"

echo "Testing Builder API..."

# Setup
setup() {
    setup_mock_environment
    start_test_builder
    sleep 2
}

# Teardown
teardown() {
    stop_test_builder
    cleanup_test_environment
}

# Test 1: Health endpoint
test_health_endpoint() {
    echo -n "  Testing /health endpoint... "
    
    local response=$(curl -s "http://localhost:$TEST_BUILDER_PORT/health")
    
    if [[ "$response" == *"OK"* ]] || [[ "$response" == *"healthy"* ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Response: $response)"
        return 1
    fi
}
# Test 2: Build endpoint with valid request
test_build_endpoint_valid() {
    echo -n "  Testing /build endpoint with valid request... "
    
    local response=$(send_build_request \
        '["abc123"]' \
        '["shell/test1/cases/test1.sh"]' \
        "http://localhost:8888/callback" \
        "localhost" \
        "debug")
    
    if [[ "$response" == *"taskId"* ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Response: $response)"
        return 1
    fi
}

# Test 3: Build endpoint with invalid request
test_build_endpoint_invalid() {
    echo -n "  Testing /build endpoint with invalid request... "
    
    local response=$(curl -s -X POST "http://localhost:$TEST_BUILDER_PORT/build" \
        -H "Content-Type: application/json" \
        -d '{"invalid": "data"}' \
        -w "\n%{http_code}")
    
    local http_code=$(echo "$response" | tail -n 1)
    
    if [[ "$http_code" == "400" ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (HTTP Code: $http_code)"
        return 1
    fi
}
# Test 4: Status endpoint
test_status_endpoint() {
    echo -n "  Testing /status endpoint... "
    
    # First submit a build request
    send_build_request '["abc123"]' '["shell/test1/cases/test1.sh"]' > /dev/null
    
    sleep 1
    
    local response=$(curl -s "http://localhost:$TEST_BUILDER_PORT/status")
    
    if [[ -n "$response" ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Empty response)"
        return 1
    fi
}

# Test 5: Method not allowed
test_method_not_allowed() {
    echo -n "  Testing invalid HTTP method... "
    
    local response=$(curl -s -X GET "http://localhost:$TEST_BUILDER_PORT/build" \
        -w "\n%{http_code}")
    
    local http_code=$(echo "$response" | tail -n 1)
    
    if [[ "$http_code" == "405" ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (HTTP Code: $http_code)"
        return 1
    fi
}

# Run tests with setup/teardown
trap teardown EXIT

setup

test_health_endpoint
test_build_endpoint_valid
test_build_endpoint_invalid
test_status_endpoint
test_method_not_allowed

teardown
trap - EXIT

echo "Builder API tests completed"
exit 0