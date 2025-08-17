#!/bin/bash

# Final Testing Plan: Build-Based Batch Distribution
# Tests the complete implementation with real commits and test cases
# Uses localhost (192.168.1.5) + remote tester (192.168.1.15:8090)

BUILDER_HOST="${BUILDER_HOST:-localhost}"
BUILDER_PORT="${BUILDER_PORT:-8089}"

echo "============================================================================"
echo "FINAL BATCH DISTRIBUTION TESTING"
echo "============================================================================"
echo ""
echo "Testing the complete build-based batch distribution implementation"
echo ""
echo "Configuration:"
echo "  Builder: ${BUILDER_HOST}:${BUILDER_PORT}"
echo "  Local Tester: localhost:8090 (192.168.1.5)"
echo "  Remote Tester: 192.168.1.15:8090"
echo ""
echo "Key Concepts Being Tested:"
echo "  ✓ Build-based batching (one container per commit)"
echo "  ✓ Localhost priority (minimize network transfers)"
echo "  ✓ Complete test suites per build"
echo "  ✓ HTTP package serving for remote testers"
echo "  ✓ Round-robin distribution of commits"
echo ""

# Test the real commits and test cases provided
echo "Test Scenario: 3 commits × 10 tests = 30 total test executions"
echo "Expected Distribution:"
echo "  Batch 1: Commit 1609a3a + all 10 tests → localhost (priority)"
echo "  Batch 2: Commit 8dae125 + all 10 tests → 192.168.1.15:8090 (remote)"
echo "  Batch 3: Commit dd64b39 + all 10 tests → localhost (round-robin)"
echo ""
echo "Total: 3 containers, 30 test executions"
echo "============================================================================"
echo ""

# Real test request with provided commits and tests
cat <<EOF | curl -X POST "http://${BUILDER_HOST}:${BUILDER_PORT}/build" \
  -H "Content-Type: application/json" \
  -d @-
{
  "commits": [
    "1609a3a41c5b73492cf5b716ced19196bd428494", 
    "8dae125ebffa6cd333cd55406e0a0439b6f2b82d", 
    "dd64b39dbf6914a739636e80c66a5c25cb2daa46"
  ],
  "tests": [
    "shell/_06_issues/_12_2h/bug_bts_9521_1/cases/bug_bts_9521_1.sh",
    "shell/_06_issues/_17_1h/cbrd_21279/cases/cbrd_21279.sh", 
    "shell/_06_issues/_20_1h/cbrd_23613_5/cases/cbrd_23613_5.sh",
    "shell/_06_issues/_23_1h/cbrd_24850/cases/cbrd_24850.sh",
    "shell/_06_issues/_25_1h/cbrd_26020/cases/cbrd_26020.sh",
    "shell/_37_elderberry/cbrd_23990_qcache_overflow/cases/cbrd_23990_qcache_overflow.sh",
    "shell/_10_plcsql/bug_fix/cbrd_25894/cases/cbrd_25894.sh",
    "shell/_35_cherry/issue_21654_server_side_loaddb/loaddb_CS/_26_apricot_qa/_04_i18/tr_TR/_02_unloaddb_monetary/cases/_02_unloaddb_monetary.sh",
    "shell/_38_fig/cbrd_24425/cases/cbrd_24425.sh",
    "shell/_38_fig/cbrd_24882/vacuumdb/cases/vacuumdb.sh"
  ],
  "callbackUrl": "http://localhost:8089/callback",
  "workerIps": ["localhost", "192.168.1.15:8090"],
  "buildType": "debug"
}
EOF

echo ""
echo ""
echo "============================================================================"
echo "MONITORING AND VERIFICATION"
echo "============================================================================"
echo ""

# Generate current timestamp for log monitoring
TIMESTAMP=$(date +%Y%m%d_%H%M)
REQUEST_PATTERN="req_${TIMESTAMP:0:8}_${TIMESTAMP:9:4}*"

echo "1. Monitor Build Progress:"
echo "   tail -f log/requests/${REQUEST_PATTERN}/builder.log"
echo ""

echo "2. Verify Batch Distribution (look for these log entries):"
echo "   ✓ 'Building 3 commits, each will run 10 tests'"
echo "   ✓ 'Worker priority order: [localhost, 192.168.1.15:8090]'"
echo "   ✓ 'Assigning batch: commit 1609a3a (10 tests) to worker localhost'"
echo "   ✓ 'Assigning batch: commit 8dae125 (10 tests) to worker 192.168.1.15:8090'"
echo "   ✓ 'Assigning batch: commit dd64b39 (10 tests) to worker localhost'"
echo ""

echo "3. Verify Package Transfer Strategy:"
echo "   ✓ 'Batch for commit 1609a3a using local build file on localhost'"
echo "   ✓ 'Batch for commit 8dae125 will download build from http://192.168.1.5:8089/download/build/'"
echo "   ✓ 'Batch for commit dd64b39 using local build file on localhost'"
echo ""

echo "4. Check Tester Logs:"
echo "   Local:  tail -f log/requests/${REQUEST_PATTERN}/tester.log"
echo "   Remote: ssh 192.168.1.15 'tail -f ~/cubrid-testtools/CTP/builder_tester/log/requests/${REQUEST_PATTERN}/tester.log'"
echo ""

echo "5. Verify Container Efficiency:"
echo "   ✓ Only 3 containers created (one per commit)"
echo "   ✓ Each container runs all 10 tests sequentially"
echo "   ✓ No redundant CUBRID installations"
echo ""

echo "6. Performance Analysis:"
echo "   Expected timeline: ~3 batch durations (parallel execution)"
echo "   Network transfers: Only 1 build package to remote tester"
echo "   Resource efficiency: 3 containers vs 30 (if individual test containers)"
echo ""

echo "============================================================================"
echo "QUICK VERIFICATION COMMANDS"
echo "============================================================================"
echo ""

echo "# Find the latest request directory"
echo "LATEST_REQ=\$(ls -1t log/requests/ | head -1)"
echo ""

echo "# Check batch assignments"
echo "grep 'Assigning batch' log/requests/\$LATEST_REQ/builder.log"
echo ""

echo "# Check worker priority"
echo "grep 'Worker priority order' log/requests/\$LATEST_REQ/builder.log"
echo ""

echo "# Check build transfer strategy"
echo "grep -E '(local build file|will download build)' log/requests/\$LATEST_REQ/builder.log"
echo ""

echo "# Monitor batch completion"
echo "grep 'Batch completed' log/requests/\$LATEST_REQ/builder.log"
echo ""

echo "# Check final results"
echo "grep 'Builder task.*completed' log/requests/\$LATEST_REQ/builder.log"
echo ""

echo "============================================================================"
echo "EXPECTED RESULTS SUMMARY"
echo "============================================================================"
echo ""
echo "✅ Successful Implementation Indicators:"
echo ""
echo "1. BATCH DISTRIBUTION:"
echo "   - 3 batches created (one per commit)"
echo "   - Round-robin assignment with localhost priority"
echo "   - Each batch contains all 10 tests"
echo ""
echo "2. NETWORK EFFICIENCY:"
echo "   - 2 batches use local files (localhost)"
echo "   - 1 batch uses HTTP download (remote)"
echo "   - Total network transfers: 1 build package"
echo ""
echo "3. CONTAINER EFFICIENCY:"
echo "   - 3 containers total (not 30)"
echo "   - Each container installs CUBRID once"
echo "   - Sequential test execution within container"
echo ""
echo "4. PARALLEL EXECUTION:"
echo "   - Localhost handles 2 batches"
echo "   - Remote handles 1 batch"
echo "   - Batches run in parallel across workers"
echo ""
echo "5. FINAL RESULTS:"
echo "   - 30 test results (3 commits × 10 tests)"
echo "   - Proper pass/fail status for each test"
echo "   - Callback sent to http://localhost:8089/callback"
echo ""
echo "This test validates the complete build-based batch distribution"
echo "implementation, demonstrating significant efficiency improvements"
echo "over individual test distribution."
echo ""
echo "============================================================================"