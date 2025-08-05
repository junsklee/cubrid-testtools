#!/bin/bash

# CTP Bisect System Diagnostic Script
# Checks both producer and consumer status

# Function to check if a process is listening on a port
check_port() {
    local port=$1
    local service=$2
    if lsof -i :$port >/dev/null 2>&1; then
        echo "✓ Port $port ($service) is in use"
        return 0
    else
        echo "✗ Port $port ($service) is not in use"
        return 1
    fi
}

# Function to test HTTP endpoints
test_endpoint() {
    local url=$1
    local name=$2
    if curl -s -f "$url" >/dev/null 2>&1; then
        echo "✓ $name endpoint is responding"
        return 0
    else
        echo "✗ $name endpoint is not responding"
        return 1
    fi
}

# Check if services are running
PRODUCER_RUNNING=1
CONSUMER_RUNNING=1

if ps aux | grep -v grep | grep -q "BisectProducer"; then
    PRODUCER_RUNNING=0
fi

if ps aux | grep -v grep | grep -q "BisectConsumer"; then
    CONSUMER_RUNNING=0
fi

echo "CTP Bisect System Diagnostic"
echo "============================"
echo

# 1. Check service status
echo "1. Checking Service Status"
echo "-------------------------"
if [ $PRODUCER_RUNNING -eq 0 ]; then
    echo "✓ BisectProducer is running"
    PRODUCER_PID=$(ps aux | grep -v grep | grep "BisectProducer" | awk '{print $2}')
    echo "  PID: $PRODUCER_PID"
else
    echo "✗ BisectProducer is not running"
fi

if [ $CONSUMER_RUNNING -eq 0 ]; then
    echo "✓ BisectConsumer is running"
    CONSUMER_PID=$(ps aux | grep -v grep | grep "BisectConsumer" | awk '{print $2}')
    echo "  PID: $CONSUMER_PID"
else
    echo "✗ BisectConsumer is not running"
fi
echo

echo "2. Checking Ports"
echo "----------------"
check_port 8089 "Producer"
check_port 8090 "Consumer"
echo

# 3. Test endpoints
echo "3. Testing HTTP Endpoints"
echo "------------------------"
test_endpoint "http://localhost:8089/health" "Producer health"
test_endpoint "http://localhost:8090/health" "Consumer health"
echo

# 4. Test consumer with sample request
if [ $CONSUMER_RUNNING -eq 0 ]; then
    echo "4. Testing Consumer with Sample Request"
    echo "--------------------------------------"
    
    # Create a minimal test directory and script
    TEST_DIR="/tmp/bisect_test_$$"
    mkdir -p "$TEST_DIR"
    
    cat > "$TEST_DIR/test.sh" << 'EOF'
#!/bin/bash
echo "Test executed" > test.result
echo "OK" >> test.result
exit 0
EOF
    chmod +x "$TEST_DIR/test.sh"
    
    # Create a dummy build package
    BUILD_DIR="/tmp/bisect_build_$$"
    mkdir -p "$BUILD_DIR/bin"
    echo '#!/bin/bash' > "$BUILD_DIR/bin/cubrid"
    echo 'echo "Dummy CUBRID"' >> "$BUILD_DIR/bin/cubrid"
    chmod +x "$BUILD_DIR/bin/cubrid"
    
    cd "$BUILD_DIR" && tar czf /tmp/test_build_$$.tar.gz . && cd - > /dev/null
    
    # Create test request
    cat > /tmp/test_request_$$.json << EOF
{
  "buildPackage": "/tmp/test_build_$$.tar.gz",
  "testPath": "shell/test.sh",
  "testDir": "$TEST_DIR",
  "testScript": "test.sh",
  "testName": "diagnostic_test"
}
EOF
    
    echo "Sending test request to consumer..."
    RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" \
        -d @/tmp/test_request_$$.json \
        http://localhost:8090/test 2>&1)
    
    echo "Response: $RESPONSE"
    
    # Cleanup
    rm -rf "$TEST_DIR" "$BUILD_DIR" /tmp/test_build_$$.tar.gz /tmp/test_request_$$.json
else
    echo "4. Skipping consumer test (consumer not running)"
fi
echo

# 5. Check configuration files
echo "5. Checking Configuration Files"
echo "------------------------------"
for conf in ../conf/bisect_producer.conf ../conf/bisect_consumer.conf; do
    if [ -f "$conf" ]; then
        echo "✓ $conf exists"
        # Check key settings
        if grep -q "cubrid_src_dir" "$conf"; then
            CUBRID_SRC=$(grep "cubrid_src_dir" "$conf" | cut -d= -f2)
            if [ -d "$CUBRID_SRC" ]; then
                echo "  ✓ cubrid_src_dir exists: $CUBRID_SRC"
            else
                echo "  ✗ cubrid_src_dir does not exist: $CUBRID_SRC"
            fi
        fi
    else
        echo "✗ $conf does not exist"
        echo "  Create it from ${conf}.example"
    fi
done
echo

# 6. Check recent logs
echo "6. Recent Log Activity"
echo "--------------------"
for log in ../log/bisect_producer.log ../log/bisect_consumer.log; do
    if [ -f "$log" ]; then
        echo "$log:"
        echo "  Last modified: $(stat -f "%Sm" "$log" 2>/dev/null || stat -c "%y" "$log" 2>/dev/null)"
        echo "  Last 5 lines:"
        tail -5 "$log" | sed 's/^/    /'
    else
        echo "✗ $log does not exist"
    fi
    echo
done

# 7. Recommendations
echo "7. Recommendations"
echo "-----------------"
if [ $PRODUCER_RUNNING -ne 0 ]; then
    echo "• Start the producer:"
    echo "    ./script/start_producer.sh"
fi
if [ $CONSUMER_RUNNING -ne 0 ]; then
    echo "• Start the consumer:"
    echo "    ./script/start_consumer.sh"
fi
if [ $PRODUCER_RUNNING -eq 0 ] && [ $CONSUMER_RUNNING -eq 0 ]; then
    echo "• Both services are running. Check the logs for any errors."
    echo "• Try sending a test bisect request:"
    echo "    ./test_bisect.sh"
fi

echo
echo "Diagnostics complete."