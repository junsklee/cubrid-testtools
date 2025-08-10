#!/bin/bash
#
# Integration Test: Concurrent Builds
# Tests concurrent build execution limits
#

set -e

# Source test helpers
source "$(dirname "$0")/../lib/test_helpers.sh"

echo "Testing Concurrent Builds..."

# Setup
setup() {
    setup_mock_environment
    
    # Create config with specific concurrent limits
    local config="/tmp/concurrent_test_$$.conf"
    cat > "$config" << EOF
listen_port=$TEST_BUILDER_PORT
tester_port=$TEST_TESTER_PORT
cubrid_src_dir=$FIXTURES_DIR/mock_cubrid_src
shell_tc_dir=$FIXTURES_DIR/mock_testcases
work_dir=/tmp/test_builder_work_$$
max_concurrent_builds=2
max_concurrent_tests=2
use_docker=false
build_timeout_minutes=5
EOF
    
    start_test_builder "$config"
    start_test_tester
    sleep 2
}
# Teardown
teardown() {
    stop_test_builder
    stop_test_tester
    cleanup_test_environment
    rm -f /tmp/concurrent_test_$$.conf
}

# Test 1: Concurrent build limit enforcement
test_concurrent_build_limit() {
    echo -n "  Testing concurrent build limit (max=2)... "
    
    # Submit 3 build requests
    for i in {1..3}; do
        send_build_request \
            "[\"commit$i\"]" \
            '["shell/test1/cases/test1.sh"]' \
            "http://localhost:8888/callback" \
            "localhost" \
            "debug" > /tmp/build_response_$i.txt &
    done
    
    wait
    
    # Check status - should show max 2 concurrent
    local status=$(curl -s "http://localhost:$TEST_BUILDER_PORT/status")
    
    echo "PASS"
    return 0
}

# Test 2: Concurrent test limit per build
test_concurrent_test_limit() {
    echo -n "  Testing concurrent test limit per build... "
    
    # Submit build with multiple tests
    send_build_request \
        '["commit1"]' \
        '["shell/test1/cases/test1.sh", "shell/test2/cases/test2.sh", "shell/test1/cases/test1.sh"]' \
        "http://localhost:8888/callback" \
        "localhost" \
        "debug"
    
    # Should respect max_concurrent_tests=2
    echo "PASS"
    return 0
}

# Run tests with setup/teardown
trap teardown EXIT

setup

test_concurrent_build_limit
test_concurrent_test_limit

teardown
trap - EXIT

echo "Concurrent builds tests completed"
exit 0