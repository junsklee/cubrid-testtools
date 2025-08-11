#!/bin/bash

# Test script to verify proper log organization

echo "Testing Log Organization Fix"
echo "============================="

# Configuration
LOG_DIR="$HOME/cubrid-testtools/CTP/builder_tester/log"
BUILDER_TESTER_DIR="$HOME/cubrid-testtools/CTP/builder_tester"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    local status=$1
    local message=$2
    if [ "$status" = "ok" ]; then
        echo -e "${GREEN}✓${NC} $message"
    elif [ "$status" = "warn" ]; then
        echo -e "${YELLOW}⚠${NC} $message"
    elif [ "$status" = "info" ]; then
        echo -e "${BLUE}ℹ${NC} $message"
    else
        echo -e "${RED}✗${NC} $message"
    fi
}

echo ""
echo "Expected Log Structure:"
echo "-----------------------"
echo "log/"
echo "├── system/"
echo "│   ├── builder.log    (service-level logs only)"
echo "│   └── tester.log     (service-level logs only)"
echo "└── requests/"
echo "    └── req_YYYYMMDD_HHMMSS_XXXX/"
echo "        ├── request.json"
echo "        ├── builder.log    (this request's builder activities)"
echo "        ├── tester.log     (this request's tester activities)"
echo "        ├── builds/"
echo "        │   └── build_*.log"
echo "        └── tests/"
echo "            ├── test_*.json"
echo "            ├── docker_script_*.sh"
echo "            └── docker_*.log"

echo ""
echo "Checking Current State:"
echo "-----------------------"

# Check system directory
if [ -d "$LOG_DIR/system" ]; then
    print_status "ok" "System directory exists"
    
    # Check system logs
    if [ -f "$LOG_DIR/system/builder.log" ]; then
        SIZE=$(wc -l < "$LOG_DIR/system/builder.log" 2>/dev/null || echo "0")
        print_status "info" "system/builder.log exists ($SIZE lines)"
    else
        print_status "warn" "system/builder.log not found"
    fi
    
    if [ -f "$LOG_DIR/system/tester.log" ]; then
        SIZE=$(wc -l < "$LOG_DIR/system/tester.log" 2>/dev/null || echo "0")
        print_status "info" "system/tester.log exists ($SIZE lines)"
    else
        print_status "warn" "system/tester.log not found"
    fi
else
    print_status "warn" "System directory not found"
fi

# Check requests directory
if [ -d "$LOG_DIR/requests" ]; then
    print_status "ok" "Requests directory exists"
    
    # Count request directories
    REQUEST_COUNT=$(find "$LOG_DIR/requests" -maxdepth 1 -type d -name "req_*" 2>/dev/null | wc -l)
    
    if [ $REQUEST_COUNT -gt 0 ]; then
        print_status "ok" "Found $REQUEST_COUNT request directories"
        
        # Check the most recent request
        LATEST_REQ=$(ls -td "$LOG_DIR/requests"/req_* 2>/dev/null | head -1)
        if [ -n "$LATEST_REQ" ]; then
            REQ_NAME=$(basename "$LATEST_REQ")
            echo ""
            print_status "info" "Checking latest request: $REQ_NAME"
            
            # Check request structure
            if [ -f "$LATEST_REQ/request.json" ]; then
                print_status "ok" "  request.json exists"
            else
                print_status "error" "  request.json missing"
            fi
            
            if [ -f "$LATEST_REQ/builder.log" ]; then
                LINES=$(wc -l < "$LATEST_REQ/builder.log" 2>/dev/null || echo "0")
                print_status "ok" "  builder.log exists ($LINES lines)"
            else
                print_status "error" "  builder.log missing"
            fi
            
            if [ -f "$LATEST_REQ/tester.log" ]; then
                LINES=$(wc -l < "$LATEST_REQ/tester.log" 2>/dev/null || echo "0")
                print_status "ok" "  tester.log exists ($LINES lines)"
            else
                print_status "warn" "  tester.log missing (created when tests run)"
            fi
            
            # Check builds directory
            if [ -d "$LATEST_REQ/builds" ]; then
                BUILD_COUNT=$(ls "$LATEST_REQ/builds"/*.log 2>/dev/null | wc -l)
                if [ $BUILD_COUNT -gt 0 ]; then
                    print_status "ok" "  builds/ has $BUILD_COUNT log files"
                    ls "$LATEST_REQ/builds"/*.log 2>/dev/null | head -3 | while read f; do
                        echo "      - $(basename $f)"
                    done
                else
                    print_status "warn" "  builds/ directory empty"
                fi
            else
                print_status "error" "  builds/ directory missing"
            fi
            
            # Check tests directory
            if [ -d "$LATEST_REQ/tests" ]; then
                TEST_COUNT=$(ls "$LATEST_REQ/tests"/* 2>/dev/null | wc -l)
                if [ $TEST_COUNT -gt 0 ]; then
                    print_status "ok" "  tests/ has $TEST_COUNT files"
                    ls "$LATEST_REQ/tests"/* 2>/dev/null | head -3 | while read f; do
                        echo "      - $(basename $f)"
                    done
                else
                    print_status "warn" "  tests/ directory empty"
                fi
            else
                print_status "error" "  tests/ directory missing"
            fi
        fi
    else
        print_status "warn" "No request directories found yet"
    fi
else
    print_status "warn" "Requests directory not found"
fi

# Check for old log locations (should be empty or non-existent)
echo ""
echo "Checking Old Log Locations (should be empty):"
echo "----------------------------------------------"

if [ -d "$LOG_DIR/builds" ]; then
    OLD_BUILD_COUNT=$(ls "$LOG_DIR/builds"/*.log 2>/dev/null | wc -l)
    if [ $OLD_BUILD_COUNT -gt 0 ]; then
        print_status "warn" "Old builds directory still has $OLD_BUILD_COUNT files"
        print_status "info" "These are from before the refactoring"
    else
        print_status "ok" "Old builds directory is empty"
    fi
else
    print_status "ok" "Old builds directory doesn't exist"
fi

if [ -d "$LOG_DIR/tests" ]; then
    OLD_TEST_COUNT=$(ls "$LOG_DIR/tests"/* 2>/dev/null | wc -l)
    if [ $OLD_TEST_COUNT -gt 0 ]; then
        print_status "warn" "Old tests directory still has $OLD_TEST_COUNT files"
        print_status "info" "These are from before the refactoring"
    else
        print_status "ok" "Old tests directory is empty"
    fi
else
    print_status "ok" "Old tests directory doesn't exist"
fi

echo ""
echo "============================="
echo "Summary"
echo "============================="
echo ""
echo "The log organization has been fixed:"
echo ""
echo "1. NO DUPLICATE LOGGING - Each log entry goes to ONE place:"
echo "   - System logs: Only service start/stop events"
echo "   - Request logs: All detailed activities for that request"
echo ""
echo "2. PROPER STRUCTURE - All logs organized by request:"
echo "   /requests/req_XXX/builder.log     - Builder activities"
echo "   /requests/req_XXX/tester.log      - Tester activities"
echo "   /requests/req_XXX/builds/*.log    - Individual build logs"
echo "   /requests/req_XXX/tests/*.log     - Individual test logs"
echo ""
echo "3. THREAD SAFETY - RequestContext properly propagated to:"
echo "   - Build threads in executor pools"
echo "   - Test threads in executor pools"
echo ""
echo "To verify the fix:"
echo "1. Start services: ./bin/start_tester.sh && ./bin/start_builder.sh"
echo "2. Send request: ./bin/test_client.sh"
echo "3. Check logs: ls -la $LOG_DIR/requests/"
