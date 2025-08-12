#!/bin/bash

# Test script for the report server functionality

echo "Testing CUBRID Report Server..."
echo "================================"

# Sample test data
TS=$(date +%s)000
cat > /tmp/test_results.json <<EOF
{
  "taskId": "test_123456",
  "results": [
    {"commit": "abc123def", "test": "shell/_01_utility/test1.sh", "status": "pass"},
    {"commit": "abc123def", "test": "shell/_01_utility/test2.sh", "status": "fail"},
    {"commit": "abc123def", "test": "shell/_01_utility/test3.sh", "status": "pass"},
    {"commit": "def456ghi", "test": "shell/_01_utility/test1.sh", "status": "pass"},
    {"commit": "def456ghi", "test": "shell/_01_utility/test2.sh", "status": "pass"},
    {"commit": "def456ghi", "test": "shell/_01_utility/test3.sh", "status": "fail"},
    {"commit": "ghi789jkl", "test": "shell/_01_utility/test1.sh", "status": "fail"},
    {"commit": "ghi789jkl", "test": "shell/_01_utility/test2.sh", "status": "fail"},
    {"commit": "ghi789jkl", "test": "shell/_01_utility/test3.sh", "status": "pass"}
  ],
  "timestamp": $TS
}
EOF

# Check if server is running
if curl -s http://localhost:8091/health > /dev/null 2>&1; then
    echo "✓ Report server is running on port 8091"
    
    echo "Sending test results..."
    RESPONSE=$(curl -s -X POST http://localhost:8091/callback \
        -H "Content-Type: application/json" \
        -d @/tmp/test_results.json \
        -w "\nHTTP_STATUS:%{http_code}")
    
    HTTP_STATUS=$(echo "$RESPONSE" | grep "HTTP_STATUS" | cut -d: -f2)
    
    if [ "$HTTP_STATUS" = "200" ]; then
        echo "✓ Results submitted successfully"
        echo ""
        echo "View reports at: http://localhost:8091/report"
    else
        echo "✗ Failed to submit results (HTTP $HTTP_STATUS)"
    fi
else
    echo "✗ Report server is not running"
    echo ""
    echo "Start it with:"
    echo "  cd report-server && node report-server.js"
fi

rm -f /tmp/test_results.json
