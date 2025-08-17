#!/bin/bash

# Test Client for Builder-Tester System

# Configuration
BUILDER_URL="http://192.168.1.5:8089"
CALLBACK_URL="http://192.168.1.5:8089/callback"  # Your callback endpoint
WORKER_IP="192.168.1.5"  # Where tester is running

# Test data
COMMITS='["1609a3a41c5b73492cf5b716ced19196bd428494", "8dae125ebffa6cd333cd55406e0a0439b6f2b82d", "dd64b39dbf6914a739636e80c66a5c25cb2daa46"]'  # Replace with real commit hashes
TESTS='["shell/_06_issues/_12_2h/bug_bts_9521_1/cases/bug_bts_9521_1.sh", "shell/_06_issues/_17_1h/cbrd_21279/cases/cbrd_21279.sh", "shell/_06_issues/_20_1h/cbrd_23613_5/cases/cbrd_23613_5.sh", "shell/_06_issues/_23_1h/cbrd_24850/cases/cbrd_24850.sh", "shell/_06_issues/_25_1h/cbrd_26020/cases/cbrd_26020.sh", "shell/_37_elderberry/cbrd_23990_qcache_overflow/cases/cbrd_23990_qcache_overflow.sh", "shell/_10_plcsql/bug_fix/cbrd_25894/cases/cbrd_25894.sh", "shell/_35_cherry/issue_21654_server_side_loaddb/loaddb_CS/_26_apricot_qa/_04_i18/tr_TR/_02_unloaddb_monetary/cases/_02_unloaddb_monetary.sh", "shell/_38_fig/cbrd_24425/cases/cbrd_24425.sh", "shell/_38_fig/cbrd_24882/vacuumdb/cases/vacuumdb.sh"]'

# Create request JSON
REQUEST_JSON=$(cat <<EOF
{
    "commits": $COMMITS,
    "tests": $TESTS,
    "callbackUrl": "$CALLBACK_URL",
    "workerIp": "$WORKER_IP",
    "buildType": "debug"
}
EOF
)

echo "Sending build request to Builder service..."
echo "Request:"
echo "$REQUEST_JSON"

# Send request
RESPONSE=$(curl -s -X POST \
    -H "Content-Type: application/json" \
    -d "$REQUEST_JSON" \
    "$BUILDER_URL/build")

echo ""
echo "Response:"
echo "$RESPONSE"

# Extract task ID if successful
TASK_ID=$(echo "$RESPONSE" | grep -o '"taskId":"[^"]*"' | cut -d'"' -f4)

if [ ! -z "$TASK_ID" ]; then
    echo ""
    echo "Task ID: $TASK_ID"
    echo ""
    echo "You can check status at: $BUILDER_URL/status?taskId=$TASK_ID"
fi 