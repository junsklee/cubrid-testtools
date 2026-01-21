#!/bin/bash
#
# CTP Builder-Tester Test Suite Runner
# Executes all test suites and generates comprehensive reports
#

set -e

# Test runner configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$SCRIPT_DIR/results"
LIB_DIR="$SCRIPT_DIR/lib"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test statistics
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0
SKIPPED_TESTS=0
START_TIME=$(date +%s)

# Test suite configuration
declare -A TEST_SUITES=(
    ["unit"]="Unit Tests"
    ["integration"]="Integration Tests"
    ["api"]="API Tests"
    ["system"]="System Tests"
    ["config"]="Configuration Tests"
)

# Command line arguments
RUN_SUITE="all"
VERBOSE=false
QUIET=false
PARALLEL=false
GENERATE_HTML=true
GENERATE_JUNIT=true
MAX_PARALLEL_JOBS=4

# Show help message
show_help() {
    cat << EOF
Usage: $(basename "$0") [OPTIONS]

CTP Builder-Tester Test Suite Runner

OPTIONS:
    -s, --suite SUITE    Run specific test suite (unit|integration|api|system|config|all)
    -v, --verbose        Enable verbose output
    -q, --quiet          Suppress output except errors
    -p, --parallel       Run tests in parallel
    -j, --jobs N         Maximum parallel jobs (default: 4)
    --no-html            Skip HTML report generation
    --no-junit           Skip JUnit XML generation
    -h, --help           Show this help message

EXAMPLES:
    $(basename "$0")                    # Run all tests
    $(basename "$0") -s unit            # Run only unit tests
    $(basename "$0") -p -j 8            # Run tests in parallel with 8 jobs
    $(basename "$0") -v -s integration  # Run integration tests with verbose output

EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -s|--suite)
            RUN_SUITE="$2"
            shift 2
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -q|--quiet)
            QUIET=true
            shift
            ;;
        -p|--parallel)
            PARALLEL=true
            shift
            ;;
        -j|--jobs)
            MAX_PARALLEL_JOBS="$2"
            shift 2
            ;;
        --no-html)
            GENERATE_HTML=false
            shift
            ;;
        --no-junit)
            GENERATE_JUNIT=false
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# Initialize test environment
initialize_environment() {
    echo -e "${BLUE}=== CTP Builder-Tester Test Suite ===${NC}"
    echo "Timestamp: $(date)"
    echo "Test directory: $SCRIPT_DIR"
    echo ""
    
    # Clean previous results
    rm -rf "$RESULTS_DIR"
    mkdir -p "$RESULTS_DIR"
    
    # Source test helpers
    source "$LIB_DIR/test_helpers.sh"
    
    # Check prerequisites
    check_prerequisites
}
# Check prerequisites
check_prerequisites() {
    local prereq_failed=false
    
    echo -e "${BLUE}Checking prerequisites...${NC}"
    
    # Check Java
    if ! command -v java &> /dev/null; then
        echo -e "${RED}✗ Java not found${NC}"
        prereq_failed=true
    else
        java_version=$(java -version 2>&1 | head -n 1)
        echo -e "${GREEN}✓ Java: $java_version${NC}"
    fi
    
    # Check Docker (optional)
    if command -v docker &> /dev/null; then
        docker_version=$(docker --version 2>/dev/null || echo "unknown")
        echo -e "${GREEN}✓ Docker: $docker_version${NC}"
    else
        echo -e "${YELLOW}⚠ Docker not found (some tests will be skipped)${NC}"
    fi
    
    # Check curl
    if ! command -v curl &> /dev/null; then
        echo -e "${RED}✗ curl not found${NC}"
        prereq_failed=true
    else
        echo -e "${GREEN}✓ curl found${NC}"
    fi
    
    # Check project build
    if [[ ! -d "$PROJECT_ROOT/build" ]]; then
        echo -e "${YELLOW}⚠ Project not compiled. Running compile.sh...${NC}"
        if ! "$PROJECT_ROOT/bin/compile.sh" &> /dev/null; then
            echo -e "${RED}✗ Failed to compile project${NC}"
            prereq_failed=true
        else
            echo -e "${GREEN}✓ Project compiled successfully${NC}"
        fi
    else
        echo -e "${GREEN}✓ Project build found${NC}"
    fi
    
    if $prereq_failed; then
        echo -e "${RED}Prerequisites check failed. Exiting.${NC}"
        exit 1
    fi
    
    echo ""
}
# Run a single test file
run_test_file() {
    local test_file="$1"
    local test_name=$(basename "$test_file" .sh)
    local suite_name=$(basename $(dirname "$test_file"))
    local test_output="$RESULTS_DIR/${suite_name}_${test_name}.log"
    local test_result=""
    local test_start=$(date +%s)
    
    if ! $QUIET; then
        echo -n "  Running $test_name... "
    fi
    
    # Execute test
    set +e
    if $VERBOSE; then
        bash "$test_file" 2>&1 | tee "$test_output"
        test_exit_code=${PIPESTATUS[0]}
    else
        bash "$test_file" &> "$test_output"
        test_exit_code=$?
    fi
    set -e
    
    local test_end=$(date +%s)
    local test_duration=$((test_end - test_start))
    
    # Determine test result
    if [[ $test_exit_code -eq 0 ]]; then
        test_result="PASS"
        PASSED_TESTS=$((PASSED_TESTS + 1))
        if ! $QUIET; then
            echo -e "${GREEN}✓ PASS${NC} (${test_duration}s)"
        fi
    elif [[ $test_exit_code -eq 77 ]]; then
        test_result="SKIP"
        SKIPPED_TESTS=$((SKIPPED_TESTS + 1))
        if ! $QUIET; then
            echo -e "${YELLOW}⊘ SKIP${NC} (${test_duration}s)"
        fi
    else
        test_result="FAIL"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        if ! $QUIET; then
            echo -e "${RED}✗ FAIL${NC} (${test_duration}s)"
        fi
        if $VERBOSE && [[ $test_exit_code -ne 0 ]]; then
            echo "    Error output:"
            tail -n 10 "$test_output" | sed 's/^/      /'
        fi
    fi
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    # Record result for reporting
    echo "{\"suite\":\"$suite_name\",\"test\":\"$test_name\",\"result\":\"$test_result\",\"duration\":$test_duration,\"log\":\"$test_output\"}" >> "$RESULTS_DIR/results.jsonl"
}
# Run tests in a suite
run_suite() {
    local suite_dir="$1"
    local suite_name=$(basename "$suite_dir")
    local suite_display_name="${TEST_SUITES[$suite_name]:-$suite_name}"
    
    if [[ ! -d "$suite_dir" ]]; then
        echo -e "${YELLOW}Suite directory not found: $suite_dir${NC}"
        return
    fi
    
    echo -e "${BLUE}Running $suite_display_name${NC}"
    
    # Find all test files
    local test_files=()
    while IFS= read -r -d '' file; do
        test_files+=("$file")
    done < <(find "$suite_dir" -name "*_test.sh" -type f -print0 | sort -z)
    
    if [[ ${#test_files[@]} -eq 0 ]]; then
        echo "  No tests found in $suite_name"
        return
    fi
    
    # Run tests (parallel or sequential)
    if $PARALLEL; then
        export -f run_test_file
        export RESULTS_DIR QUIET VERBOSE RED GREEN YELLOW BLUE NC
        printf '%s\0' "${test_files[@]}" | xargs -0 -P "$MAX_PARALLEL_JOBS" -I {} bash -c 'run_test_file "$@"' _ {}
    else
        for test_file in "${test_files[@]}"; do
            run_test_file "$test_file"
        done
    fi
    
    echo ""
}
# Generate JSON report
generate_json_report() {
    local end_time=$(date +%s)
    local total_duration=$((end_time - START_TIME))
    local pass_rate
    pass_rate=$(awk -v total="$TOTAL_TESTS" -v passed="$PASSED_TESTS" 'BEGIN { if (total > 0) { printf "%.2f", passed * 100.0 / total } else { printf "0.00" } }')
    
    cat > "$RESULTS_DIR/report.json" << EOF
{
    "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
    "duration": $total_duration,
    "summary": {
        "total": $TOTAL_TESTS,
        "passed": $PASSED_TESTS,
        "failed": $FAILED_TESTS,
        "skipped": $SKIPPED_TESTS,
        "pass_rate": $pass_rate
    },
    "tests": [
EOF
    
    if [[ -f "$RESULTS_DIR/results.jsonl" ]]; then
        sed 's/$/,/' "$RESULTS_DIR/results.jsonl" | sed '$ s/,$//' >> "$RESULTS_DIR/report.json"
    fi
    
    echo "    ]" >> "$RESULTS_DIR/report.json"
    echo "}" >> "$RESULTS_DIR/report.json"
    
    echo -e "${GREEN}JSON report generated: $RESULTS_DIR/report.json${NC}"
}
# Generate HTML report
generate_html_report() {
    cat > "$RESULTS_DIR/report.html" << EOF
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CTP Builder-Tester Test Report</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
        .container { max-width: 1200px; margin: 0 auto; }
        .header { background: white; padding: 20px; border-radius: 8px; margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .summary { display: flex; gap: 20px; margin-bottom: 20px; }
        .stat-card { flex: 1; background: white; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .stat-value { font-size: 2em; font-weight: bold; }
        .stat-label { color: #666; margin-top: 5px; }
        .pass { color: #28a745; }
        .fail { color: #dc3545; }
        .skip { color: #ffc107; }
        .tests-table { background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        table { width: 100%; border-collapse: collapse; }
        th { background: #f8f9fa; padding: 12px; text-align: left; border-bottom: 2px solid #dee2e6; }
        td { padding: 12px; border-bottom: 1px solid #dee2e6; }
        .test-pass { background: #d4edda; }
        .test-fail { background: #f8d7da; }
        .test-skip { background: #fff3cd; }
    </style>
</head>
EOF
    cat >> "$RESULTS_DIR/report.html" << EOF
<body>
    <div class="container">
        <div class="header">
            <h1>CTP Builder-Tester Test Report</h1>
            <p>Generated: $(date)</p>
        </div>
        <div class="summary">
            <div class="stat-card">
                <div class="stat-value">$TOTAL_TESTS</div>
                <div class="stat-label">Total Tests</div>
            </div>
            <div class="stat-card">
                <div class="stat-value pass">$PASSED_TESTS</div>
                <div class="stat-label">Passed</div>
            </div>
            <div class="stat-card">
                <div class="stat-value fail">$FAILED_TESTS</div>
                <div class="stat-label">Failed</div>
            </div>
            <div class="stat-card">
                <div class="stat-value skip">$SKIPPED_TESTS</div>
                <div class="stat-label">Skipped</div>
            </div>
        </div>
        <div class="tests-table">
            <table>
                <thead>
                    <tr>
                        <th>Suite</th>
                        <th>Test</th>
                        <th>Result</th>
                        <th>Duration</th>
                    </tr>
                </thead>
                <tbody>
EOF
    
    # Add test results to HTML
    if [[ -f "$RESULTS_DIR/results.jsonl" ]]; then
        while IFS= read -r line; do
            suite=$(echo "$line" | sed -n 's/.*"suite":"\([^"]*\)".*/\1/p')
            test=$(echo "$line" | sed -n 's/.*"test":"\([^"]*\)".*/\1/p')
            result=$(echo "$line" | sed -n 's/.*"result":"\([^"]*\)".*/\1/p')
            duration=$(echo "$line" | sed -n 's/.*"duration":\([^,}]*\).*/\1/p')
            
            class=""
            case "$result" in
                PASS) class="test-pass" ;;
                FAIL) class="test-fail" ;;
                SKIP) class="test-skip" ;;
            esac
            
            echo "<tr class=\"$class\"><td>$suite</td><td>$test</td><td>$result</td><td>${duration}s</td></tr>" >> "$RESULTS_DIR/report.html"
        done < "$RESULTS_DIR/results.jsonl"
    fi
    
    cat >> "$RESULTS_DIR/report.html" << 'EOF'
                </tbody>
            </table>
        </div>
    </div>
</body>
</html>
EOF
    
    echo -e "${GREEN}HTML report generated: $RESULTS_DIR/report.html${NC}"
}
# Generate JUnit XML report
generate_junit_report() {
    cat > "$RESULTS_DIR/junit.xml" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuites name="CTP Builder-Tester Tests" tests="$TOTAL_TESTS" failures="$FAILED_TESTS" skipped="$SKIPPED_TESTS" time="$(($(date +%s) - START_TIME))">
EOF
    
    # Group tests by suite
    declare -A suite_tests
    if [[ -f "$RESULTS_DIR/results.jsonl" ]]; then
        while IFS= read -r line; do
            suite=$(echo "$line" | sed -n 's/.*"suite":"\([^"]*\)".*/\1/p')
            suite_tests[$suite]+="$line"$'\n'
        done < "$RESULTS_DIR/results.jsonl"
    fi
    
    # Generate testsuite elements
    for suite in "${!suite_tests[@]}"; do
        suite_total=0
        suite_failures=0
        suite_skipped=0
        suite_time=0
        
        echo "  <testsuite name=\"$suite\">" >> "$RESULTS_DIR/junit.xml"
        
        while IFS= read -r line; do
            [[ -z "$line" ]] && continue
            
            test=$(echo "$line" | sed -n 's/.*"test":"\([^"]*\)".*/\1/p')
            result=$(echo "$line" | sed -n 's/.*"result":"\([^"]*\)".*/\1/p')
            duration=$(echo "$line" | sed -n 's/.*"duration":\([^,}]*\).*/\1/p')
                        
            ((suite_total++))
            suite_time=$((suite_time + duration))
            
            echo "    <testcase name=\"$test\" classname=\"$suite\" time=\"$duration\">" >> "$RESULTS_DIR/junit.xml"
            
            case "$result" in
                FAIL)
                    ((suite_failures++))
                    echo "      <failure message=\"Test failed\">See log for details</failure>" >> "$RESULTS_DIR/junit.xml"
                    ;;
                SKIP)
                    ((suite_skipped++))
                    echo "      <skipped message=\"Test skipped\"/>" >> "$RESULTS_DIR/junit.xml"
                    ;;
            esac
            
            echo "    </testcase>" >> "$RESULTS_DIR/junit.xml"
        done <<< "${suite_tests[$suite]}"
        
        echo "  </testsuite>" >> "$RESULTS_DIR/junit.xml"
    done
    
    echo "</testsuites>" >> "$RESULTS_DIR/junit.xml"
    
    echo -e "${GREEN}JUnit XML report generated: $RESULTS_DIR/junit.xml${NC}"
}
# Print test summary
print_summary() {
    echo -e "${BLUE}=== Test Summary ===${NC}"
    echo "Total tests: $TOTAL_TESTS"
    echo -e "Passed: ${GREEN}$PASSED_TESTS${NC}"
    echo -e "Failed: ${RED}$FAILED_TESTS${NC}"
    echo -e "Skipped: ${YELLOW}$SKIPPED_TESTS${NC}"
    
    if [[ $TOTAL_TESTS -gt 0 ]]; then
        local pass_rate=$(awk "BEGIN {printf \"%.1f\", $PASSED_TESTS * 100.0 / $TOTAL_TESTS}")
        echo "Pass rate: ${pass_rate}%"
    fi
    
    local end_time=$(date +%s)
    local duration=$((end_time - START_TIME))
    echo "Duration: ${duration}s"
    echo ""
}

# Main execution
main() {
    # Initialize environment
    initialize_environment
    
    # Determine which suites to run
    if [[ "$RUN_SUITE" == "all" ]]; then
        for suite in "${!TEST_SUITES[@]}"; do
            run_suite "$SCRIPT_DIR/$suite"
        done
    else
        if [[ -n "${TEST_SUITES[$RUN_SUITE]}" ]]; then
            run_suite "$SCRIPT_DIR/$RUN_SUITE"
        else
            echo -e "${RED}Unknown test suite: $RUN_SUITE${NC}"
            exit 1
        fi
    fi
    
    # Generate reports
    generate_json_report
    
    if $GENERATE_HTML; then
        generate_html_report
    fi
    
    if $GENERATE_JUNIT; then
        generate_junit_report
    fi
    
    # Print summary
    print_summary
    
    # Exit with appropriate code
    if [[ $FAILED_TESTS -gt 0 ]]; then
        exit 1
    else
        exit 0
    fi
}

# Run main function
main "$@"
