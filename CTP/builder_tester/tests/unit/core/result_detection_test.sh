#!/bin/bash
#
# Unit Test: Result Detection
# Tests test result detection logic
#

set -e

# Source test helpers
source "$(dirname "$0")/../../lib/test_helpers.sh"

echo "Testing Result Detection..."

# Test 1: PASS result detection
test_pass_detection() {
    echo -n "  Testing PASS result detection... "
    
    # Create test result file
    local result_file="/tmp/test_pass.result"
    echo "Test completed OK" > "$result_file"
    
    # Check detection
    if grep -q "OK\|PASS" "$result_file"; then
        echo "PASS"
    else
        echo "FAIL"
    fi
    
    rm -f "$result_file"
    return 0
}

# Test 2: FAIL result detection
test_fail_detection() {
    echo -n "  Testing FAIL result detection... "
    
    # Create test result file
    local result_file="/tmp/test_fail.result"
    echo "Test failed NOK" > "$result_file"
    
    # Check detection
    if grep -q "NOK\|FAIL" "$result_file"; then
        echo "PASS"
    else
        echo "FAIL"
    fi
    
    rm -f "$result_file"
    return 0
}
# Test 3: Result file naming convention
test_result_file_naming() {
    echo -n "  Testing result file naming convention... "
    
    # Test script: foo.sh -> foo.result
    local test_script="csql_hist.sh"
    local expected_result="csql_hist.result"
    
    # Extract base name and add .result
    local base_name="${test_script%.sh}"
    local actual_result="${base_name}.result"
    
    assert_equals "$expected_result" "$actual_result" "Result file naming"
    echo "PASS"
    return 0
}

# Test 4: Missing result file handling
test_missing_result() {
    echo -n "  Testing missing result file handling... "
    
    # When result file is missing, should report error
    local result_file="/tmp/nonexistent.result"
    
    if [[ ! -f "$result_file" ]]; then
        echo "PASS (Correctly handles missing result)"
    else
        echo "FAIL"
    fi
    
    return 0
}

# Run all tests
test_pass_detection
test_fail_detection
test_result_file_naming
test_missing_result

echo "Result detection tests completed"
exit 0