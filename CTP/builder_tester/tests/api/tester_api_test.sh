#!/bin/bash
#
# API Test: Tester API Endpoints
# Tests all Tester service HTTP endpoints
#

set -e

# Source test helpers
source "$(dirname "$0")/../lib/test_helpers.sh"

echo "Testing Tester API..."

# Setup
setup() {
    setup_mock_environment
    start_test_tester
    sleep 2
}

# Teardown
teardown() {
    stop_test_tester
    cleanup_test_environment
}

# Test 1: Health endpoint
test_health_endpoint() {
    echo -n "  Testing /health endpoint... "
    
    local response=$(curl -s "http://localhost:$TEST_TESTER_PORT/health")
    
    if [[ "$response" == *"OK"* ]] || [[ "$response" == *"healthy"* ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Response: $response)"
        return 1
    fi
}
# Test 2: Test endpoint with synchronous mode
test_sync_test_endpoint() {
    echo -n "  Testing /test endpoint (synchronous)... "
    
    # Create a mock build package
    local mock_package="/tmp/mock_build_$$.tar.gz"
    touch "$mock_package"
    
    local response=$(send_test_request \
        "$mock_package" \
        "shell/test1/cases/test1.sh" \
        "$FIXTURES_DIR/mock_testcases/shell/test1/cases" \
        "test1.sh" \
        "test1" \
        "false")
    
    rm -f "$mock_package"
    
    if [[ "$response" == *"status"* ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Response: $response)"
        return 1
    fi
}

# Test 3: Test endpoint with keep-alive mode
test_keepalive_test_endpoint() {
    echo -n "  Testing /test endpoint (keep-alive)... "
    
    # Create a mock build package
    local mock_package="/tmp/mock_build_keepalive_$$.tar.gz"
    touch "$mock_package"
    
    local response=$(send_test_request \
        "$mock_package" \
        "shell/test1/cases/test1.sh" \
        "$FIXTURES_DIR/mock_testcases/shell/test1/cases" \
        "test1.sh" \
        "test1" \
        "true")
    
    rm -f "$mock_package"
    
    if [[ "$response" == *"containerName"* ]] && [[ "$response" == *"execCommand"* ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Response: $response)"
        return 1
    fi
}
# Test 4: Invalid test request
test_invalid_test_request() {
    echo -n "  Testing /test endpoint with invalid request... "
    
    local response=$(curl -s -X POST "http://localhost:$TEST_TESTER_PORT/test" \
        -H "Content-Type: application/json" \
        -d '{"missing": "required_fields"}' \
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

# Test 5: Non-existent build package
test_missing_build_package() {
    echo -n "  Testing /test with missing build package... "
    
    local response=$(send_test_request \
        "/non/existent/package.tar.gz" \
        "shell/test1/cases/test1.sh" \
        "$FIXTURES_DIR/mock_testcases/shell/test1/cases" \
        "test1.sh" \
        "test1" \
        "false")
    
    if [[ "$response" == *"error"* ]] || [[ "$response" == *"not found"* ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Should report missing package)"
        return 1
    fi
}

# Run tests with setup/teardown
trap teardown EXIT

setup

test_health_endpoint
test_sync_test_endpoint
test_keepalive_test_endpoint
test_invalid_test_request
test_missing_build_package

teardown
trap - EXIT

echo "Tester API tests completed"
exit 0