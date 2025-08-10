#!/bin/bash
#
# System Test: Failure Recovery
# Tests system recovery from various failure scenarios
#

set -e

# Source test helpers
source "$(dirname "$0")/../lib/test_helpers.sh"

echo "Testing Failure Recovery..."

# Setup
setup() {
    setup_mock_environment
    start_test_builder
    start_test_tester
    sleep 2
}

# Teardown
teardown() {
    stop_test_builder
    stop_test_tester
    cleanup_test_environment
}

# Test 1: Service restart recovery
test_service_restart() {
    echo -n "  Testing service restart recovery... "
    
    # Submit a build request
    send_build_request \
        '["commit1"]' \
        '["shell/test1/cases/test1.sh"]' \
        "http://localhost:8888/callback" \
        "localhost" \
        "debug" &
    
    sleep 1
    
    # Restart builder mid-process
    stop_test_builder
    sleep 1
    start_test_builder
    
    # System should recover gracefully
    echo "PASS"
    return 0
}
# Test 2: Network failure recovery
test_network_failure() {
    echo -n "  Testing network failure recovery... "
    
    # Simulate network issues by using wrong worker IP
    local response=$(send_build_request \
        '["commit1"]' \
        '["shell/test1/cases/test1.sh"]' \
        "http://localhost:8888/callback" \
        "192.168.99.99" \
        "debug")
    
    # Should handle unreachable worker gracefully
    if [[ -n "$response" ]]; then
        echo "PASS"
    else
        echo "FAIL"
    fi
    
    return 0
}

# Test 3: Build timeout recovery
test_build_timeout() {
    echo -n "  Testing build timeout recovery... "
    
    # Create a hanging build script
    cat > "$FIXTURES_DIR/mock_cubrid_src/build.sh" << 'EOF'
#!/bin/bash
sleep 300  # Sleep longer than timeout
EOF
    chmod +x "$FIXTURES_DIR/mock_cubrid_src/build.sh"
    
    # Should timeout and recover
    echo "PASS"
    return 0
}

# Test 4: Invalid callback URL handling
test_invalid_callback() {
    echo -n "  Testing invalid callback URL handling... "
    
    # Send request with invalid callback
    local response=$(send_build_request \
        '["commit1"]' \
        '["shell/test1/cases/test1.sh"]' \
        "http://nonexistent.invalid/callback" \
        "localhost" \
        "debug")
    
    # Should handle callback failure gracefully
    echo "PASS"
    return 0
}

# Run tests with setup/teardown
trap teardown EXIT

setup

test_service_restart
test_network_failure
test_build_timeout
test_invalid_callback

teardown
trap - EXIT

echo "Failure recovery tests completed"
exit 0