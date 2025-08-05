#!/bin/bash

echo "CTP Bisect Consumer Diagnostic and Fix Script"
echo "============================================="
echo

# Function to check if a process is listening on a port
check_port() {
    local port=$1
    if lsof -i :$port >/dev/null 2>&1; then
        echo "✓ Port $port is in use"
        return 0
    else
        echo "✗ Port $port is not in use"
        return 1
    fi
}

# 1. Check if consumer is running
echo "1. Checking Consumer Status..."
if ps aux | grep -v grep | grep -q "BisectConsumer"; then
    echo "✓ BisectConsumer process is running"
    CONSUMER_PID=$(ps aux | grep -v grep | grep "BisectConsumer" | awk '{print $2}')
    echo "  PID: $CONSUMER_PID"
else
    echo "✗ BisectConsumer is not running"
fi

# 2. Check port status
echo
echo "2. Checking Port Status..."
check_port 8090

# 3. Test consumer endpoint
echo
echo "3. Testing Consumer Endpoint..."
if curl -s -f -X GET http://localhost:8090/ >/dev/null 2>&1; then
    echo "✓ Consumer HTTP server is responding"
else
    echo "✗ Consumer HTTP server is not responding"
fi

# 4. Create a simple test
echo
echo "4. Creating Simple Test Case..."
mkdir -p /tmp/test_shell
cat > /tmp/test_shell/simple_test.sh << 'EOF'
#!/bin/bash
echo "Test executed successfully" > simple_test.result
echo "OK" >> simple_test.result
EOF
chmod +x /tmp/test_shell/simple_test.sh

# 5. Create a minimal build package
echo
echo "5. Creating Minimal Build Package..."
mkdir -p /tmp/test_build/bin
mkdir -p /tmp/test_build/lib
echo "#!/bin/bash" > /tmp/test_build/bin/cubrid
echo "echo 'Dummy CUBRID'" >> /tmp/test_build/bin/cubrid
chmod +x /tmp/test_build/bin/cubrid
cd /tmp/test_build
tar czf /tmp/test_cubrid.tar.gz .
cd - >/dev/null

# 6. Send test request
echo
echo "6. Sending Test Request to Consumer..."
cat > /tmp/test_request.json << EOF
{
  "buildPackage": "/tmp/test_cubrid.tar.gz",
  "testPath": "shell/simple_test.sh",
  "testDir": "/tmp/test_shell",
  "testScript": "simple_test.sh",
  "testName": "simple_test"
}
EOF

echo "Request payload:"
cat /tmp/test_request.json | python3 -m json.tool

echo
echo "Sending request..."
RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}\n" -X POST \
  -H "Content-Type: application/json" \
  -d @/tmp/test_request.json \
  http://localhost:8090/test)

HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
BODY=$(echo "$RESPONSE" | grep -v "HTTP_CODE:")

echo "Response code: $HTTP_CODE"
echo "Response body: $BODY"

# 7. Check consumer logs
echo
echo "7. Recent Consumer Logs..."
if [ -f ~/cubrid-testtools/CTP/bisect/bisect_consumer.log ]; then
    echo "Last 10 lines of consumer log:"
    tail -n 10 ~/cubrid-testtools/CTP/bisect/bisect_consumer.log
else
    echo "Consumer log file not found at expected location"
fi

# 8. Provide recommendations
echo
echo "8. Recommendations:"
echo "=================="

if ! check_port 8090 >/dev/null 2>&1; then
    echo "• Consumer is not running. Start it with:"
    echo "  cd ~/cubrid-testtools/CTP/bisect"
    echo "  java -cp 'build/*:lib/*' com.navercorp.cubridqa.bisect.BisectConsumer conf/bisect_consumer.conf"
fi

if [ "$HTTP_CODE" != "200" ]; then
    echo "• Consumer is not processing requests properly. Check:"
    echo "  - Consumer configuration file"
    echo "  - File permissions in work directory"
    echo "  - Java classpath and dependencies"
fi

echo
echo "• To monitor consumer in real-time:"
echo "  tail -f ~/cubrid-testtools/CTP/bisect/bisect_consumer.log"

# Cleanup
rm -f /tmp/test_request.json
rm -rf /tmp/test_shell
rm -rf /tmp/test_build
rm -f /tmp/test_cubrid.tar.gz

echo
echo "Diagnostic complete."
