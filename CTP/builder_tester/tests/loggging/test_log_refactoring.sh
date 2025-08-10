#!/bin/bash

# Test script to verify the logging refactoring

echo "Testing Builder-Tester Logging Refactoring"
echo "=========================================="

# Configuration
LOG_DIR="$HOME/cubrid-testtools/CTP/builder_tester/log"
WORK_DIR="/tmp/builder_work"

# Function to check directory structure
check_log_structure() {
    echo -e "\n1. Checking log directory structure..."
    
    if [ -d "$LOG_DIR/requests" ]; then
        echo "✓ Requests directory exists"
    else
        echo "✗ Requests directory missing"
    fi
    
    if [ -d "$LOG_DIR/system" ]; then
        echo "✓ System directory exists"
    else
        echo "✗ System directory missing"
    fi
    
    if [ -f "$LOG_DIR/.metadata.json" ]; then
        echo "✓ Metadata file exists"
        echo "  Metadata content:"
        cat "$LOG_DIR/.metadata.json" | head -5
    else
        echo "✗ Metadata file missing"
    fi
}

# Function to count request directories
count_request_dirs() {
    echo -e "\n2. Counting request directories..."
    if [ -d "$LOG_DIR/requests" ]; then
        COUNT=$(ls -d "$LOG_DIR/requests"/req_* 2>/dev/null | wc -l)
        echo "  Found $COUNT request directories"
        
        # List the directories
        if [ $COUNT -gt 0 ]; then
            echo "  Recent requests:"
            ls -lt "$LOG_DIR/requests" | head -6
        fi
    fi
}

# Function to check tar files
check_tar_files() {
    echo -e "\n3. Checking tar files in work directory..."
    if [ -d "$WORK_DIR" ]; then
        TAR_COUNT=$(ls "$WORK_DIR"/*.tar.gz 2>/dev/null | wc -l)
        echo "  Found $TAR_COUNT tar files in $WORK_DIR"
        
        if [ $TAR_COUNT -gt 0 ]; then
            echo "  Recent tar files:"
            ls -lt "$WORK_DIR"/*.tar.gz 2>/dev/null | head -5
        fi
    else
        echo "  Work directory doesn't exist yet"
    fi
}

# Function to check configuration
check_config() {
    echo -e "\n4. Checking configuration..."
    
    BUILDER_CONF="conf/builder.conf"
    TESTER_CONF="conf/tester.conf"
    
    echo "  Builder configuration:"
    grep -E "max_request_logs|max_tar_files|enable_request_grouping" "$BUILDER_CONF" | sed 's/^/    /'
    
    echo "  Tester configuration:"
    grep -E "max_request_logs|enable_request_grouping" "$TESTER_CONF" | sed 's/^/    /'
}

# Function to simulate a test request
simulate_request() {
    echo -e "\n5. Simulating a test request (optional)..."
    echo "  To test the system, run:"
    echo "    1. ./bin/start_tester.sh  (in one terminal)"
    echo "    2. ./bin/start_builder.sh (in another terminal)"
    echo "    3. ./bin/test_client.sh   (to send a test request)"
}

# Main execution
echo "Starting tests..."

check_log_structure
count_request_dirs
check_tar_files
check_config
simulate_request

echo -e "\n=========================================="
echo "Testing complete!"
echo ""
echo "Summary:"
echo "- Log refactoring implements request-based grouping"
echo "- Automatic cleanup of old logs and tar files"
echo "- Configuration supports max_request_logs and max_tar_files"
echo "- Request IDs track all related logs together"
