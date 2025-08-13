#!/bin/bash
#
# Test Helper Library
# Common functions for all test suites
#

# Test directory paths
TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(dirname "$TEST_ROOT")"
FIXTURES_DIR="$TEST_ROOT/fixtures"
RESULTS_DIR="$TEST_ROOT/results"

# Test ports (to avoid conflicts with running services)
TEST_BUILDER_PORT=18089
TEST_TESTER_PORT=18090

# PID files for test services
TEST_BUILDER_PID="/tmp/test_builder_$$.pid"
TEST_TESTER_PID="/tmp/test_tester_$$.pid"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Test assertion functions
assert_equals() {
    local expected="$1"
    local actual="$2"
    local message="${3:-Assertion failed}"
    
    if [[ "$expected" == "$actual" ]]; then
        return 0
    else
        echo -e "${RED}✗ $message${NC}"
        echo "  Expected: $expected"
        echo "  Actual: $actual"
        return 1
    fi
}
assert_not_equals() {
    local not_expected="$1"
    local actual="$2"
    local message="${3:-Assertion failed}"
    
    if [[ "$not_expected" != "$actual" ]]; then
        return 0
    else
        echo -e "${RED}✗ $message${NC}"
        echo "  Should not be: $not_expected"
        echo "  Actual: $actual"
        return 1
    fi
}

assert_contains() {
    local haystack="$1"
    local needle="$2"
    local message="${3:-Assertion failed}"
    
    if [[ "$haystack" == *"$needle"* ]]; then
        return 0
    else
        echo -e "${RED}✗ $message${NC}"
        echo "  Text should contain: $needle"
        echo "  Actual text: $haystack"
        return 1
    fi
}

assert_not_contains() {
    local haystack="$1"
    local needle="$2"
    local message="${3:-Assertion failed}"
    
    if [[ "$haystack" != *"$needle"* ]]; then
        return 0
    else
        echo -e "${RED}✗ $message${NC}"
        echo "  Text should not contain: $needle"
        echo "  Actual text: $haystack"
        return 1
    fi
}
assert_file_exists() {
    local file="$1"
    local message="${2:-File should exist}"
    
    if [[ -f "$file" ]]; then
        return 0
    else
        echo -e "${RED}✗ $message${NC}"
        echo "  File not found: $file"
        return 1
    fi
}

assert_dir_exists() {
    local dir="$1"
    local message="${2:-Directory should exist}"
    
    if [[ -d "$dir" ]]; then
        return 0
    else
        echo -e "${RED}✗ $message${NC}"
        echo "  Directory not found: $dir"
        return 1
    fi
}

assert_http_status() {
    local url="$1"
    local expected_status="$2"
    local message="${3:-HTTP status assertion failed}"
    
    local actual_status=$(curl -s -o /dev/null -w "%{http_code}" "$url")
    
    if [[ "$actual_status" == "$expected_status" ]]; then
        return 0
    else
        echo -e "${RED}✗ $message${NC}"
        echo "  URL: $url"
        echo "  Expected status: $expected_status"
        echo "  Actual status: $actual_status"
        return 1
    fi
}
# Service management functions
start_test_builder() {
    local config="${1:-$FIXTURES_DIR/test_configs/builder_test.conf}"
    
    # Create test config if not provided
    if [[ ! -f "$config" ]]; then
        create_test_builder_config "$config"
    fi
    
    # Start builder with test config
    java -cp "$PROJECT_ROOT/build/*:$PROJECT_ROOT/lib/*" \
        com.navercorp.cubridqa.builder.Builder "$config" &> /tmp/test_builder_$$.log &
    
    echo $! > "$TEST_BUILDER_PID"
    
    # Wait for service to start
    local retries=10
    while [[ $retries -gt 0 ]]; do
        if curl -s "http://localhost:$TEST_BUILDER_PORT/health" &> /dev/null; then
            return 0
        fi
        sleep 1
        ((retries--))
    done
    
    echo "Failed to start test builder service"
    return 1
}

start_test_tester() {
    local config="${1:-$FIXTURES_DIR/test_configs/tester_test.conf}"
    
    # Create test config if not provided
    if [[ ! -f "$config" ]]; then
        create_test_tester_config "$config"
    fi
    
    # Start tester with test config
    java -cp "$PROJECT_ROOT/build/*:$PROJECT_ROOT/lib/*" \
        com.navercorp.cubridqa.builder.Tester "$config" &> /tmp/test_tester_$$.log &
    
    echo $! > "$TEST_TESTER_PID"    
    # Wait for service to start
    local retries=10
    while [[ $retries -gt 0 ]]; do
        if curl -s "http://localhost:$TEST_TESTER_PORT/health" &> /dev/null; then
            return 0
        fi
        sleep 1
        ((retries--))
    done
    
    echo "Failed to start test tester service"
    return 1
}

stop_test_builder() {
    if [[ -f "$TEST_BUILDER_PID" ]]; then
        kill $(cat "$TEST_BUILDER_PID") 2>/dev/null || true
        rm -f "$TEST_BUILDER_PID"
    fi
}

stop_test_tester() {
    if [[ -f "$TEST_TESTER_PID" ]]; then
        kill $(cat "$TEST_TESTER_PID") 2>/dev/null || true
        rm -f "$TEST_TESTER_PID"
    fi
}

# Configuration creation functions
create_test_builder_config() {
    local config_file="$1"
    mkdir -p "$(dirname "$config_file")"
    
    cat > "$config_file" << EOF
# Test Builder Configuration
listen_port=$TEST_BUILDER_PORT
tester_port=$TEST_TESTER_PORT
cubrid_src_dir=$FIXTURES_DIR/mock_cubrid_src
shell_tc_dir=$FIXTURES_DIR/mock_testcases
work_dir=/tmp/test_builder_work_$$
max_concurrent_builds=2
max_concurrent_tests=2
use_docker=false
build_timeout_minutes=5
build_cache_size=5
EOF
}
create_test_tester_config() {
    local config_file="$1"
    mkdir -p "$(dirname "$config_file")"
    
    cat > "$config_file" << EOF
# Test Tester Configuration
tester_port=$TEST_TESTER_PORT
cubrid_src_dir=$FIXTURES_DIR/mock_cubrid_src
shell_tc_dir=$FIXTURES_DIR/mock_testcases
work_dir=/tmp/test_tester_work_$$
use_docker_tester=false
keep_failed_containers=false
EOF
}

# Mock setup functions
setup_mock_environment() {
    # Create mock CUBRID source
    mkdir -p "$FIXTURES_DIR/mock_cubrid_src"
    cat > "$FIXTURES_DIR/mock_cubrid_src/build.sh" << 'EOF'
#!/bin/bash
echo "Mock build started"
sleep 1
echo "Mock build completed"
mkdir -p build_x86_64_debug
touch build_x86_64_debug/cubrid_debug.tar.gz
exit 0
EOF
    chmod +x "$FIXTURES_DIR/mock_cubrid_src/build.sh"
    
    # Create mock test cases
    mkdir -p "$FIXTURES_DIR/mock_testcases/shell/test1/cases"
    cat > "$FIXTURES_DIR/mock_testcases/shell/test1/cases/test1.sh" << 'EOF'
#!/bin/bash
echo "Running test1"
echo "OK" > test1.result
exit 0
EOF
    chmod +x "$FIXTURES_DIR/mock_testcases/shell/test1/cases/test1.sh"
    
    mkdir -p "$FIXTURES_DIR/mock_testcases/shell/test2/cases"
    cat > "$FIXTURES_DIR/mock_testcases/shell/test2/cases/test2.sh" << 'EOF'
#!/bin/bash
echo "Running test2"
echo "FAIL" > test2.result
exit 1
EOF
    chmod +x "$FIXTURES_DIR/mock_testcases/shell/test2/cases/test2.sh"
}
# HTTP request helper functions
send_build_request() {
    local commits="$1"
    local tests="$2"
    local callback_url="${3:-http://localhost:8888/callback}"
    local worker_ip="${4:-localhost}"
    local build_type="${5:-debug}"
    
    curl -s -X POST "http://localhost:$TEST_BUILDER_PORT/build" \
        -H "Content-Type: application/json" \
        -d "{
            \"commits\": $commits,
            \"tests\": $tests,
            \"callbackUrl\": \"$callback_url\",
            \"workerIp\": \"$worker_ip\",
            \"buildType\": \"$build_type\"
        }"
}

send_test_request() {
    local build_package="$1"
    local test_path="$2"
    local test_dir="$3"
    local test_script="$4"
    local test_name="$5"
    local keep_alive="${6:-false}"
    
    curl -s -X POST "http://localhost:$TEST_TESTER_PORT/test" \
        -H "Content-Type: application/json" \
        -d "{
            \"buildPackage\": \"$build_package\",
            \"testPath\": \"$test_path\",
            \"testDir\": \"$test_dir\",
            \"testScript\": \"$test_script\",
            \"testName\": \"$test_name\",
            \"expectedBuildVersion\": \"test\",
            \"keepAlive\": $keep_alive
        }"
}

# Cleanup function
cleanup_test_environment() {
    stop_test_builder
    stop_test_tester
    rm -rf /tmp/test_builder_work_$$
    rm -rf /tmp/test_tester_work_$$
    rm -f /tmp/test_builder_$$.log
    rm -f /tmp/test_tester_$$.log
}

# Skip test if condition not met
skip_if() {
    local condition="$1"
    local message="$2"
    
    if eval "$condition"; then
        echo -e "${YELLOW}⊘ SKIPPING: $message${NC}"
        exit 77  # Special exit code for skipped tests
    fi
}

# Export functions for use in tests
export -f assert_equals assert_not_equals assert_contains assert_not_contains
export -f assert_file_exists assert_dir_exists assert_http_status
export -f start_test_builder start_test_tester stop_test_builder stop_test_tester
export -f create_test_builder_config create_test_tester_config
export -f setup_mock_environment send_build_request send_test_request
export -f cleanup_test_environment skip_if