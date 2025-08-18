#!/bin/bash

# Example: Testing with retry for flaky test detection
# This script demonstrates how to use retry functionality to detect flaky tests

echo "Flaky Test Detection Example"
echo "============================"
echo ""
echo "This example shows how to configure retry counts to automatically detect flaky tests."
echo ""

echo "Configuration for Flaky Test Detection"
echo "--------------------------------------"
echo ""
echo "In conf/tester.conf, set retry_count to enable retries:"
echo ""
cat <<'EOF'
# Enable retry for flaky test detection
retry_count=3              # Retry failed tests up to 3 times
test_timeout_minutes=10    # Timeout per test attempt
EOF

echo ""
echo "How It Works:"
echo "-------------"
echo "1. When a test fails, the system automatically retries it"
echo "2. If the test passes on retry, it's marked as 'flaky'"
echo "3. The report shows 'Flaky: Passed after X attempts'"
echo "4. Both failed and successful attempt logs are preserved"
echo ""

echo "Example Request with Tests That May Be Flaky:"
echo "---------------------------------------------"
cat <<'EOF'
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["6ea587e", "abc123"],
    "tests": [
      "shell/_01_utility/unstable_test.sh",
      "shell/_02_sql/timing_sensitive.sh",
      "shell/_03_object/race_condition.sh"
    ],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIp": "localhost",
    "buildType": "debug"
  }'
EOF

echo ""
echo "Expected Results in Report:"
echo "---------------------------"
echo ""
echo "The verdict column will show:"
echo "- 'Pass: Not reproduced' - Test passed on first attempt"
echo "- 'Flaky: Passed after 2 attempts' - Failed once, passed on retry"
echo "- 'Flaky: Passed after 3 attempts' - Failed twice, passed on third try"
echo "- 'Fail' - Failed all attempts including retries"
echo ""

echo "Viewing Flaky Test Results:"
echo "---------------------------"
echo ""
echo "1. Check the HTML report:"
echo "   curl http://localhost:8089/report?id=req_xxxxx"
echo ""
echo "2. Look for the 'Flaky Tests' statistic in the dashboard"
echo ""
echo "3. Check test logs for retry attempts:"
echo "   ls ~/cubrid-testtools/CTP/builder_tester/log/requests/req_*/tests/"
echo ""
echo "   You'll see multiple log files for retried tests:"
echo "   - docker_6ea587e_unstable_test.log (first attempt)"
echo "   - docker_6ea587e_unstable_test_retry1.log"
echo "   - docker_6ea587e_unstable_test_retry2.log"
echo ""

echo "Benefits of Flaky Test Detection:"
echo "---------------------------------"
echo "1. Identifies unreliable tests that need fixing"
echo "2. Distinguishes between real failures and intermittent issues"
echo "3. Helps prioritize test maintenance efforts"
echo "4. Provides data on test stability over time"
echo ""

echo "Best Practices:"
echo "---------------"
echo "- Set retry_count between 1-3 (higher values slow down testing)"
echo "- Monitor flaky test trends over time"
echo "- Fix flaky tests to improve CI/CD reliability"
echo "- Use keep_failed_containers=true to debug flaky tests"