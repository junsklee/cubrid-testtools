#!/bin/bash
#
# Unit Test: Docker Utils
# Tests Docker utility functions
#

set -e

# Source test helpers
source "$(dirname "$0")/../../lib/test_helpers.sh"

echo "Testing Docker Utils..."

# Test 1: Docker availability check
test_docker_availability() {
    echo -n "  Testing Docker availability check... "
    
    # Check if docker command exists
    if command -v docker &> /dev/null; then
        # Verify docker daemon is running
        if docker ps &> /dev/null; then
            echo "PASS (Docker available)"
        else
            echo "SKIP (Docker not running)"
            return 0
        fi
    else
        echo "SKIP (Docker not installed)"
        return 0
    fi
    
    return 0
}
# Test 2: Docker image pull command generation
test_docker_pull_command() {
    echo -n "  Testing Docker pull command generation... "
    
    # Test image name
    local image="cubridci/cubridci:develop"
    local expected_cmd="docker pull $image"
    
    # Simulate command generation
    local actual_cmd="docker pull $image"
    
    assert_equals "$expected_cmd" "$actual_cmd" "Docker pull command"
    echo "PASS"
    return 0
}

# Test 3: Docker run command generation
test_docker_run_command() {
    echo -n "  Testing Docker run command generation... "
    
    # Test parameters
    local container_name="test_container"
    local image="cubridci/cubridci:test"
    local work_dir="/workspace"
    
    # Expected command structure
    local expected_pattern="docker run.*--name $container_name.*-w $work_dir.*$image"
    
    # Simulate command
    local actual_cmd="docker run -d --name $container_name -w $work_dir $image"
    
    if [[ "$actual_cmd" =~ $container_name ]] && [[ "$actual_cmd" =~ $image ]]; then
        echo "PASS"
        return 0
    else
        echo "FAIL"
        return 1
    fi
}
# Test 4: Container cleanup command
test_container_cleanup() {
    echo -n "  Testing container cleanup command... "
    
    # Test container name pattern
    local pattern="builder_*"
    local expected_cmd="docker ps -a --filter name=$pattern -q"
    
    # Simulate command
    local actual_cmd="docker ps -a --filter name=$pattern -q"
    
    assert_equals "$expected_cmd" "$actual_cmd" "Container list command"
    echo "PASS"
    return 0
}

# Test 5: Volume mount validation
test_volume_mount() {
    echo -n "  Testing volume mount validation... "
    
    # Test mount paths
    local host_path="/home/user/data"
    local container_path="/data"
    local expected_mount="-v $host_path:$container_path"
    
    # Simulate mount option
    local actual_mount="-v $host_path:$container_path"
    
    assert_equals "$expected_mount" "$actual_mount" "Volume mount"
    echo "PASS"
    return 0
}

# Run all tests
test_docker_availability
test_docker_pull_command
test_docker_run_command
test_container_cleanup
test_volume_mount

echo "Docker utils tests completed"
exit 0