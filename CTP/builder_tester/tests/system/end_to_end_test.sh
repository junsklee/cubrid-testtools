#!/bin/bash
#
# System Test: End-to-End Test
# Tests complete build-test-callback flow
#

set -e

# Source test helpers
source "$(dirname "$0")/../lib/test_helpers.sh"

echo "Testing End-to-End Flow..."

# Setup
setup() {
    setup_mock_environment
    
    # Create more realistic mock build script
    cat > "$FIXTURES_DIR/mock_cubrid_src/build.sh" << 'EOF'
#!/bin/bash
echo "Starting CUBRID build..."
echo "Checking out commit: $1"
sleep 2
echo "Building debug version..."
mkdir -p build_x86_64_debug
tar czf build_x86_64_debug/cubrid_debug.tar.gz --files-from /dev/null
echo "Build completed successfully"
exit 0
EOF
    chmod +x "$FIXTURES_DIR/mock_cubrid_src/build.sh"
    
    start_test_builder
    start_test_tester
    sleep 3
}
# Teardown
teardown() {
    stop_test_builder
    stop_test_tester
    cleanup_test_environment
    rm -f /tmp/e2e_callback_$$.txt
    kill $(cat /tmp/callback_server_$$.pid 2>/dev/null) 2>/dev/null || true
    rm -f /tmp/callback_server_$$.pid
}

# Test 1: Complete flow with single commit and test
test_single_commit_flow() {
    echo -n "  Testing single commit and test flow... "
    
    # Start simple callback server
    (while true; do 
        nc -l 8888 >> /tmp/e2e_callback_$$.txt 2>/dev/null && break
    done) &
    echo $! > /tmp/callback_server_$$.pid
    
    # Send build request
    local response=$(send_build_request \
        '["abc123"]' \
        '["shell/test1/cases/test1.sh"]' \
        "http://localhost:8888/callback" \
        "localhost" \
        "debug")
    
    # Extract task ID
    local task_id=$(echo "$response" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)
    
    if [[ -n "$task_id" ]]; then
        echo "PASS (Task ID: $task_id)"
        return 0
    else
        echo "FAIL (No task ID returned)"
        return 1
    fi
}
# Test 2: Multiple commits and tests
test_multiple_commits_flow() {
    echo -n "  Testing multiple commits and tests flow... "
    
    # Send build request with multiple commits and tests
    local response=$(send_build_request \
        '["commit1", "commit2"]' \
        '["shell/test1/cases/test1.sh", "shell/test2/cases/test2.sh"]' \
        "http://localhost:8888/callback" \
        "localhost" \
        "debug")
    
    # Wait for processing
    sleep 5
    
    # Check status shows processing
    local status=$(curl -s "http://localhost:$TEST_BUILDER_PORT/status")
    
    if [[ -n "$status" ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL (Empty status)"
        return 1
    fi
}

# Test 3: Callback verification
test_callback_reception() {
    echo -n "  Testing callback reception... "
    
    # Wait for previous tests' callbacks
    sleep 3
    
    if [[ -f /tmp/e2e_callback_$$.txt ]] && [[ -s /tmp/e2e_callback_$$.txt ]]; then
        local content=$(cat /tmp/e2e_callback_$$.txt)
        if [[ "$content" == *"results"* ]]; then
            echo "PASS"
            return 0
        else
            echo "FAIL (Invalid callback content)"
            return 1
        fi
    else
        echo "FAIL (No callback received)"
        return 1
    fi
}

# Run tests with setup/teardown
trap teardown EXIT

setup

test_single_commit_flow
test_multiple_commits_flow
test_callback_reception

teardown
trap - EXIT

echo "End-to-end tests completed"
exit 0