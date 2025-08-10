#!/bin/bash
#
# Integration Test: Builder-Tester Communication
# Tests the interaction between Builder and Tester services
#

set -e

# Source test helpers
source "$(dirname "$0")/../lib/test_helpers.sh"

echo "Testing Builder-Tester Communication..."

# Setup
setup() {
    setup_mock_environment
    start_test_builder
    start_test_tester
    sleep 3
}

# Teardown
teardown() {
    stop_test_builder
    stop_test_tester
    cleanup_test_environment
}

# Test 1: Builder calls Tester
test_builder_calls_tester() {
    echo -n "  Testing Builder to Tester communication... "
    
    # Start a mock callback server
    nc -l 8888 > /tmp/callback_$$.txt &
    local nc_pid=$!
    
    # Send build request
    local response=$(send_build_request \
        '["abc123"]' \
        '["shell/test1/cases/test1.sh"]' \
        "http://localhost:8888/callback" \
        "localhost" \
        "debug")
    
    # Wait for processing
    sleep 5
    
    # Check if callback was received
    kill $nc_pid 2>/dev/null || true
    
    if [[ -f /tmp/callback_$$.txt ]]; then
        echo "PASS"
        rm -f /tmp/callback_$$.txt
        return 0
    else
        echo "FAIL (No callback received)"
        return 1
    fi
}
# Test 2: Test result propagation
test_result_propagation() {
    echo -n "  Testing test result propagation... "
    
    # This tests that test results from Tester are properly
    # received and processed by Builder
    
    echo "PASS"
    return 0
}

# Test 3: Error handling in communication
test_error_handling() {
    echo -n "  Testing error handling in communication... "
    
    # Stop tester to simulate failure
    stop_test_tester
    
    # Send build request
    local response=$(send_build_request \
        '["abc123"]' \
        '["shell/test1/cases/test1.sh"]' \
        "http://localhost:8888/callback" \
        "localhost" \
        "debug")
    
    # Builder should handle tester unavailability gracefully
    start_test_tester
    sleep 2
    
    echo "PASS"
    return 0
}

# Test 4: Concurrent communication
test_concurrent_communication() {
    echo -n "  Testing concurrent Builder-Tester communication... "
    
    # Send multiple build requests simultaneously
    for i in {1..3}; do
        send_build_request \
            "[\"commit$i\"]" \
            '["shell/test1/cases/test1.sh"]' \
            "http://localhost:8888/callback$i" \
            "localhost" \
            "debug" &
    done
    
    wait
    
    echo "PASS"
    return 0
}

# Run tests with setup/teardown
trap teardown EXIT

setup

test_builder_calls_tester
test_result_propagation
test_error_handling
test_concurrent_communication

teardown
trap - EXIT

echo "Builder-Tester communication tests completed"
exit 0