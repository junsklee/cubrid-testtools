#!/bin/bash

# Test script to verify dual logging (system + request-specific)

echo "Testing Dual Logging Implementation"
echo "===================================="

# Configuration
LOG_DIR="$HOME/cubrid-testtools/CTP/builder_tester/log"
BUILDER_TESTER_DIR="$HOME/cubrid-testtools/CTP/builder_tester"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print status
print_status() {
    local status=$1
    local message=$2
    if [ "$status" = "ok" ]; then
        echo -e "${GREEN}✓${NC} $message"
    elif [ "$status" = "warn" ]; then
        echo -e "${YELLOW}⚠${NC} $message"
    else
        echo -e "${RED}✗${NC} $message"
    fi
}

# Function to check if a file contains expected content
check_file_content() {
    local file=$1
    local expected=$2
    if [ -f "$file" ]; then
        if grep -q "$expected" "$file" 2>/dev/null; then
            print_status "ok" "File $file contains expected content"
            return 0
        else
            print_status "error" "File $file does not contain expected content: $expected"
            return 1
        fi
    else
        print_status "error" "File $file does not exist"
        return 1
    fi
}

echo ""
echo "1. Checking Directory Structure"
echo "--------------------------------"

# Check if log directories exist
if [ -d "$LOG_DIR/system" ]; then
    print_status "ok" "System log directory exists"
else
    print_status "warn" "System log directory does not exist (will be created on first run)"
fi

if [ -d "$LOG_DIR/requests" ]; then
    print_status "ok" "Requests log directory exists"
else
    print_status "warn" "Requests log directory does not exist (will be created on first run)"
fi

echo ""
echo "2. Configuration Check"
echo "----------------------"

# Check configuration files
cd "$BUILDER_TESTER_DIR"
if grep -q "enable_request_grouping=true" conf/builder.conf; then
    print_status "ok" "Request grouping enabled in builder.conf"
else
    print_status "error" "Request grouping not enabled in builder.conf"
fi

if grep -q "max_request_logs=" conf/builder.conf; then
    MAX_LOGS=$(grep "max_request_logs=" conf/builder.conf | cut -d'=' -f2)
    print_status "ok" "Max request logs set to: $MAX_LOGS"
else
    print_status "error" "max_request_logs not configured"
fi

echo ""
echo "3. Testing Logging Mechanism"
echo "-----------------------------"

# Create a simple test to verify dual logging
cat > /tmp/test_dual_logging.java << 'EOF'
import com.navercorp.cubridqa.builder.logging.*;
import java.io.*;
import java.nio.file.*;
import java.util.logging.*;

public class TestDualLogging {
    public static void main(String[] args) throws Exception {
        // Initialize logging
        String logRoot = System.getProperty("user.home") + 
                        "/cubrid-testtools/CTP/builder_tester/log";
        LogConfig config = new LogConfig(5, 10, logRoot, true);
        RequestLogManager.initialize(config);
        
        // Generate request ID
        String requestId = RequestContext.generateRequestId();
        System.out.println("Generated Request ID: " + requestId);
        
        // Get request logger
        Logger logger = RequestLogManager.getInstance()
                          .getRequestLogger(requestId, "test");
        
        // Log test message
        logger.info("Test message for dual logging verification");
        
        // Check if logs exist
        String reqLog = logRoot + "/requests/" + requestId + "/test.log";
        String sysLog = logRoot + "/system/test.log";
        
        Thread.sleep(100); // Wait for logs to be written
        
        boolean reqExists = Files.exists(Paths.get(reqLog));
        boolean sysExists = Files.exists(Paths.get(sysLog));
        
        System.out.println("Request log exists: " + reqExists);
        System.out.println("System log exists: " + sysExists);
        
        if (reqExists && sysExists) {
            System.out.println("SUCCESS: Dual logging is working!");
        } else {
            System.out.println("FAILURE: Dual logging not working properly");
        }
    }
}
EOF

echo "Compiling test class..."
cd "$BUILDER_TESTER_DIR"
javac -cp "build:lib/*" /tmp/test_dual_logging.java -d /tmp/ 2>/dev/null

if [ $? -eq 0 ]; then
    echo "Running dual logging test..."
    java -cp "/tmp:build:lib/*" TestDualLogging
else
    print_status "warn" "Could not compile test class (services may need to be running)"
fi

echo ""
echo "4. Checking Log Structure After Test"
echo "-------------------------------------"

# Check for recent request directories
if [ -d "$LOG_DIR/requests" ]; then
    REQUEST_COUNT=$(find "$LOG_DIR/requests" -maxdepth 1 -type d -name "req_*" 2>/dev/null | wc -l)
    if [ $REQUEST_COUNT -gt 0 ]; then
        print_status "ok" "Found $REQUEST_COUNT request directories"
        
        # Show most recent request
        LATEST_REQ=$(ls -td "$LOG_DIR/requests"/req_* 2>/dev/null | head -1)
        if [ -n "$LATEST_REQ" ]; then
            echo "  Latest request: $(basename $LATEST_REQ)"
            
            # Check structure of latest request
            if [ -f "$LATEST_REQ/builder.log" ]; then
                print_status "ok" "  - builder.log exists"
            fi
            if [ -f "$LATEST_REQ/tester.log" ]; then
                print_status "ok" "  - tester.log exists"
            fi
            if [ -d "$LATEST_REQ/builds" ]; then
                print_status "ok" "  - builds/ directory exists"
            fi
            if [ -d "$LATEST_REQ/tests" ]; then
                print_status "ok" "  - tests/ directory exists"
            fi
        fi
    else
        print_status "warn" "No request directories found (run a build to create them)"
    fi
fi

echo ""
echo "5. Cleanup Test"
echo "---------------"

# Count tar files in work directory
WORK_DIR="/tmp/builder_work"
if [ -d "$WORK_DIR" ]; then
    TAR_COUNT=$(find "$WORK_DIR" -name "*.tar.gz" 2>/dev/null | wc -l)
    print_status "ok" "Found $TAR_COUNT tar files in work directory"
else
    print_status "warn" "Work directory does not exist yet"
fi

echo ""
echo "===================================="
echo "Summary"
echo "===================================="
echo ""
echo "The dual logging implementation ensures:"
echo "1. All logs are written to BOTH:"
echo "   - System directory: $LOG_DIR/system/"
echo "   - Request directory: $LOG_DIR/requests/req_XXX/"
echo ""
echo "2. Structure per request:"
echo "   /requests/req_XXX/"
echo "   ├── builder.log    (builder activities for this request)"
echo "   ├── tester.log     (tester activities for this request)"
echo "   ├── builds/        (individual build logs)"
echo "   │   └── build_*.log"
echo "   └── tests/         (individual test logs)"
echo "       └── test_*.log"
echo ""
echo "3. Automatic cleanup:"
echo "   - Keeps only last $MAX_LOGS request directories"
echo "   - Removes old tar files from $WORK_DIR"
echo ""
echo "To test the full system:"
echo "1. Start tester: ./bin/start_tester.sh"
echo "2. Start builder: ./bin/start_builder.sh"
echo "3. Send a request: ./bin/test_client.sh"
echo "4. Check logs in both locations"
