#!/bin/bash
#
# Test script to validate the enhanced BisectConsumer functionality
# This script tests all the new features added in v2.0
#

set -e

echo "================================================================"
echo "CUBRID Bisect Consumer v2.0 Test Suite"
echo "================================================================"
echo ""

# Configuration
CONSUMER_HOST="localhost"
CONSUMER_PORT="8090"
TEST_DIR="/tmp/bisect_consumer_test"
BUILD_PACKAGE="/tmp/test_build.tar.gz"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counter
TEST_COUNT=0
PASS_COUNT=0
FAIL_COUNT=0

# Function to print test result
print_result() {
    local test_name=$1
    local status=$2
    local message=$3
    
    TEST_COUNT=$((TEST_COUNT + 1))
    
    if [ "$status" = "PASS" ]; then
        echo -e "${GREEN}✓${NC} Test $TEST_COUNT: $test_name - ${GREEN}PASSED${NC}"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo -e "${RED}✗${NC} Test $TEST_COUNT: $test_name - ${RED}FAILED${NC}"
        echo "  Reason: $message"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
}

# Function to send test request
send_test_request() {
    local test_name=$1
    local test_script=$2
    local expected_status=$3
    
    # Create request JSON
    cat > /tmp/test_request.json << EOF
{
  "buildPackage": "$BUILD_PACKAGE",
  "testPath": "test/$test_script",
  "testDir": "$TEST_DIR",
  "testScript": "$test_script",
  "testName": "$test_name"
}
EOF
    
    # Send request and capture response
    RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" \
        -d @/tmp/test_request.json \
        http://$CONSUMER_HOST:$CONSUMER_PORT/test)
    
    # Check if response contains expected status
    if echo "$RESPONSE" | grep -q "\"status\":\"$expected_status\""; then
        print_result "$test_name" "PASS" ""
        return 0
    else
        print_result "$test_name" "FAIL" "Expected status '$expected_status', got: $RESPONSE"
        return 1
    fi
}

# Setup test environment
echo "Setting up test environment..."
mkdir -p $TEST_DIR

# Create a mock build package (if CUBRID is not available)
if [ ! -f "$BUILD_PACKAGE" ]; then
    echo "Creating mock build package..."
    mkdir -p /tmp/mock_build/bin
    mkdir -p /tmp/mock_build/lib
    mkdir -p /tmp/mock_build/databases
    
    # Create mock cubrid_rel command
    cat > /tmp/mock_build/bin/cubrid_rel << 'EOF'
#!/bin/bash
echo "CUBRID 11.2.0 (11.2.0.0001) (64bit release build for linux_gnu) (Aug 6 2025)"
EOF
    chmod +x /tmp/mock_build/bin/cubrid_rel
    
    # Create tarball
    cd /tmp/mock_build
    tar czf $BUILD_PACKAGE .
    cd -
fi

echo ""
echo "Running tests..."
echo "----------------"

# Test 1: Health Check
echo -e "${YELLOW}Test: Health Check${NC}"
HEALTH_RESPONSE=$(curl -s http://$CONSUMER_HOST:$CONSUMER_PORT/health)
if echo "$HEALTH_RESPONSE" | grep -q "\"status\":\"healthy\""; then
    print_result "Health Check" "PASS" ""
else
    print_result "Health Check" "FAIL" "Consumer not healthy: $HEALTH_RESPONSE"
fi

# Test 2: Test that passes
echo -e "${YELLOW}Test: Passing Test${NC}"
cat > $TEST_DIR/test_pass.sh << 'EOF'
#!/bin/bash
echo "This test passes"
echo "OK" > test_pass.result
exit 0
EOF
chmod +x $TEST_DIR/test_pass.sh
send_test_request "test_pass" "test_pass.sh" "pass"

# Test 3: Test that fails
echo -e "${YELLOW}Test: Failing Test${NC}"
cat > $TEST_DIR/test_fail.sh << 'EOF'
#!/bin/bash
echo "This test fails"
echo "NOK" > test_fail.result
exit 1
EOF
chmod +x $TEST_DIR/test_fail.sh
send_test_request "test_fail" "test_fail.sh" "fail"

# Test 4: Test with execution error (syntax error)
echo -e "${YELLOW}Test: Execution Error (Syntax)${NC}"
cat > $TEST_DIR/test_syntax_error.sh << 'EOF'
#!/bin/bash
echo "This has a syntax error"
if [ true  # Missing closing bracket
echo "Should not reach here"
EOF
chmod +x $TEST_DIR/test_syntax_error.sh
send_test_request "test_syntax_error" "test_syntax_error.sh" "execution_error"

# Test 5: Test with no result file
echo -e "${YELLOW}Test: No Result File${NC}"
cat > $TEST_DIR/test_no_result.sh << 'EOF'
#!/bin/bash
echo "This test doesn't create a result file"
# No result file created
exit 0
EOF
chmod +x $TEST_DIR/test_no_result.sh
# Should create a default OK result based on exit code 0
send_test_request "test_no_result" "test_no_result.sh" "pass"

# Test 6: Test with shell test framework functions
echo -e "${YELLOW}Test: Shell Framework Functions${NC}"
cat > $TEST_DIR/test_framework.sh << 'EOF'
#!/bin/bash
# Test that uses write_ok function (will use fallback if not available)
if type -t write_ok > /dev/null; then
    write_ok "Test passed with framework"
else
    echo "OK" > test_framework.result
fi
exit 0
EOF
chmod +x $TEST_DIR/test_framework.sh
send_test_request "test_framework" "test_framework.sh" "pass"

# Test 7: Test with command not found
echo -e "${YELLOW}Test: Command Not Found${NC}"
cat > $TEST_DIR/test_cmd_not_found.sh << 'EOF'
#!/bin/bash
echo "Testing non-existent command"
nonexistent_command_xyz
echo "Should not reach here"
EOF
chmod +x $TEST_DIR/test_cmd_not_found.sh
send_test_request "test_cmd_not_found" "test_cmd_not_found.sh" "execution_error"

# Test 8: Test timeout simulation (commented out as it takes 30 minutes)
# echo -e "${YELLOW}Test: Timeout${NC}"
# cat > $TEST_DIR/test_timeout.sh << 'EOF'
# #!/bin/bash
# echo "This test will timeout"
# sleep 3600  # Sleep for 1 hour (will timeout after 30 minutes)
# EOF
# chmod +x $TEST_DIR/test_timeout.sh
# send_test_request "test_timeout" "test_timeout.sh" "execution_error"

echo ""
echo "================================================================"
echo "Test Results Summary"
echo "================================================================"
echo -e "Total Tests: $TEST_COUNT"
echo -e "${GREEN}Passed: $PASS_COUNT${NC}"
echo -e "${RED}Failed: $FAIL_COUNT${NC}"

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${GREEN}All tests passed successfully!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed. Please check the consumer logs for details.${NC}"
    echo "Log file: ~/cubrid-testtools/CTP/bisect/log/bisect_consumer.log"
    exit 1
fi
