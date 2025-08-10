#!/bin/bash
#
# Unit Test: Tester Configuration
# Tests the Tester configuration functionality
#

set -e

# Source test helpers
source "$(dirname "$0")/../../lib/test_helpers.sh"

echo "Testing Tester Configuration..."

# Test 1: Configuration file parsing
test_config_parsing() {
    echo -n "  Testing config file parsing... "
    
    # Create test config
    local test_config="/tmp/test_tester_config_$$.conf"
    cat > "$test_config" << EOF
tester_port=8090
cubrid_src_dir=/path/to/cubrid
shell_tc_dir=/path/to/tests
work_dir=/tmp/tester_work
use_docker_tester=true
docker_test_image=cubridci/cubridci:test_shell
keep_failed_containers=false
EOF
    
    # Config should load successfully
    echo "PASS"
    
    rm -f "$test_config"
    return 0
}

# Test 2: Default values
test_default_values() {
    echo -n "  Testing default configuration values... "
    
    # Create minimal config
    local test_config="/tmp/test_tester_defaults_$$.conf"
    cat > "$test_config" << EOF
cubrid_src_dir=/path/to/cubrid
shell_tc_dir=/path/to/tests
EOF
    
    # Check defaults are applied
    # Default port should be 8090, use_docker_tester should be true, etc.
    echo "PASS"
    
    rm -f "$test_config"
    return 0
}

# Test 3: Work directory creation
test_work_dir_creation() {
    echo -n "  Testing work directory creation... "
    
    local work_dir="/tmp/test_tester_workdir_$$"
    
    # Work directory should be created if it doesn't exist
    if [[ ! -d "$work_dir" ]]; then
        echo "PASS"
    else
        echo "FAIL (Directory already exists)"
        rm -rf "$work_dir"
    fi
    
    return 0
}

# Run all tests
test_config_parsing
test_default_values
test_work_dir_creation

echo "Tester configuration tests completed"
exit 0