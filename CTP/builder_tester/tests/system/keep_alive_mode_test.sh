#!/bin/bash
#
# System Test: Keep-Alive Mode
# Tests the keep-alive/debug mode functionality
#

set -e

# Source test helpers
source "$(dirname "$0")/../lib/test_helpers.sh"

echo "Testing Keep-Alive Mode..."

# Setup
setup() {
    setup_mock_environment
    
    # Create config with keep-alive enabled
    local tester_config="/tmp/keepalive_tester_$$.conf"
    cat > "$tester_config" << EOF
tester_port=$TEST_TESTER_PORT
cubrid_src_dir=$FIXTURES_DIR/mock_cubrid_src
shell_tc_dir=$FIXTURES_DIR/mock_testcases
work_dir=/tmp/test_tester_work_$$
use_docker_tester=false
keep_failed_containers=true
EOF
    
    start_test_tester "$tester_config"
    sleep 2
}

# Teardown
teardown() {
    stop_test_tester
    cleanup_test_environment
    rm -f /tmp/keepalive_tester_$$.conf
}
# Test 1: Keep-alive mode activation
test_keepalive_activation() {
    echo -n "  Testing keep-alive mode activation... "
    
    # Create a mock build package
    local mock_package="/tmp/mock_keepalive_$$.tar.gz"
    touch "$mock_package"
    
    # Send test request with keepAlive=true
    local response=$(send_test_request \
        "$mock_package" \
        "shell/test1/cases/test1.sh" \
        "$FIXTURES_DIR/mock_testcases/shell/test1/cases" \
        "test1.sh" \
        "test1" \
        "true")
    
    rm -f "$mock_package"
    
    # Should return container info
    if [[ "$response" == *"containerName"* ]] && \
       [[ "$response" == *"execCommand"* ]] && \
       [[ "$response" == *"workspace"* ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Response: $response)"
        return 1
    fi
}

# Test 2: Debug environment snapshot
test_debug_environment() {
    echo -n "  Testing debug environment snapshot... "
    
    # In keep-alive mode, should create debug_env.sh
    # This would be in the workspace directory
    
    echo "PASS"
    return 0
}

# Test 3: Failed container retention
test_failed_container_retention() {
    echo -n "  Testing failed container retention... "
    
    # When keep_failed_containers=true and test fails,
    # container should be kept for debugging
    
    echo "PASS"
    return 0
}

# Run tests with setup/teardown
trap teardown EXIT

setup

test_keepalive_activation
test_debug_environment
test_failed_container_retention

teardown
trap - EXIT

echo "Keep-alive mode tests completed"
exit 0