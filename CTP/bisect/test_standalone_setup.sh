#!/bin/bash

# Test script for Standalone Bisect Mode
# This script tests the basic setup and Docker image building

set -e

echo "========================================="
echo "Testing Standalone Bisect Mode Setup"
echo "========================================="

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0

# Function to test a condition
test_condition() {
    local test_name="$1"
    local condition="$2"
    
    echo -n "Testing: $test_name... "
    
    if eval "$condition"; then
        echo -e "${GREEN}PASSED${NC}"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}FAILED${NC}"
        ((TESTS_FAILED++))
    fi
}

# Test 1: Check if Docker is available
test_condition "Docker availability" "command -v docker &>/dev/null"

# Test 2: Check if Docker daemon is running
test_condition "Docker daemon running" "docker ps &>/dev/null"

# Test 3: Check if Java is available
test_condition "Java availability" "command -v java &>/dev/null"

# Test 4: Check if cubridci directory exists or can be created
test_condition "CubridCI repository" "[ -d ~/cubridci ] || (git ls-remote https://github.com/CUBRID/cubridci.git &>/dev/null)"

# Test 5: Check if build was successful
test_condition "Build artifacts exist" "[ -f lib/bisect-tool.jar ]"

# Test 6: Check if standalone classes were compiled
test_condition "Standalone classes compiled" "jar tf lib/bisect-tool.jar | grep -q StandaloneBisectExecutor"

# Test 7: Check if configuration file exists or can be created
test_condition "Configuration file" "[ -f conf/bisect_standalone.conf ] || [ -f conf/bisect_standalone.conf.example ]"

# Test 8: Check if test cases directory is configured
if [ -f conf/bisect_standalone.conf ]; then
    TC_DIR=$(grep "^shell_tc_dir=" conf/bisect_standalone.conf | cut -d= -f2 | sed 's/~/'$HOME'/g')
    test_condition "Test cases directory" "[ -n '$TC_DIR' ]"
else
    echo -e "${YELLOW}Skipping test cases directory check (no config file)${NC}"
fi

# Test 9: Check if CUBRID source directory is configured
if [ -f conf/bisect_standalone.conf ]; then
    SRC_DIR=$(grep "^cubrid_src_dir=" conf/bisect_standalone.conf | cut -d= -f2 | sed 's/~/'$HOME'/g')
    test_condition "CUBRID source directory configured" "[ -n '$SRC_DIR' ]"
else
    echo -e "${YELLOW}Skipping CUBRID source directory check (no config file)${NC}"
fi

# Test 10: Check if the standalone script is executable
test_condition "Standalone script executable" "[ -x script/run_standalone.sh ]"

# Summary
echo ""
echo "========================================="
echo "Test Summary"
echo "========================================="
echo -e "Tests Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Tests Failed: ${RED}$TESTS_FAILED${NC}"

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed! Standalone mode is ready to use.${NC}"
    echo ""
    echo "To start the standalone service, run:"
    echo "  ./script/run_standalone.sh start"
    exit 0
else
    echo -e "${RED}Some tests failed. Please fix the issues before running standalone mode.${NC}"
    echo ""
    echo "Common fixes:"
    echo "  - Install Docker: https://docs.docker.com/get-docker/"
    echo "  - Start Docker daemon: sudo systemctl start docker"
    echo "  - Clone cubridci: git clone https://github.com/CUBRID/cubridci.git ~/cubridci"
    echo "  - Create config: cp conf/bisect_standalone.conf.example conf/bisect_standalone.conf"
    exit 1
fi
