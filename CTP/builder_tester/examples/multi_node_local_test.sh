#!/bin/bash

# Example: Testing multi-node distribution locally
# This script demonstrates testing with 3 local tester instances on different ports

echo "Multi-Node Testing Example"
echo "=========================="
echo ""
echo "This example shows how to run multiple tester nodes locally for testing."
echo ""

# Configuration
BUILDER_PORT=8089
TESTER_PORTS=(8090 8091 8092)
WORK_DIRS=(/tmp/tester_work_1 /tmp/tester_work_2 /tmp/tester_work_3)

echo "Step 1: Create configuration files for each tester"
echo "---------------------------------------------------"

for i in 0 1 2; do
    port=${TESTER_PORTS[$i]}
    work_dir=${WORK_DIRS[$i]}
    config_file="conf/tester_node_$((i+1)).conf"
    
    echo "Creating $config_file for port $port..."
    
    cat > "$config_file" <<EOF
# Tester Node $((i+1)) Configuration
tester_port=$port
max_concurrent_tests=2
cubrid_src_dir=~/cubrid
shell_tc_dir=~/cubrid-testcases-private-ex
shell_tc_branch=develop
build_dir=build_x86_64_debug
work_dir=$work_dir
use_docker_tester=true
use_prebuilt_docker_images=true
docker_test_image=cubridci/cubridci:test_shell
keep_failed_containers=false
max_request_logs=5
enable_request_grouping=true
retry_count=0
test_read_timeout_minutes=60
EOF
done

echo ""
echo "Step 2: Start tester nodes"
echo "--------------------------"
echo "Run these commands in separate terminals:"
echo ""
echo "# Terminal 1 - Tester Node 1:"
echo "export GITHUB_TOKEN=your_token"
echo "java -cp 'build/*:lib/*' com.navercorp.cubridqa.builder.Tester conf/tester_node_1.conf"
echo ""
echo "# Terminal 2 - Tester Node 2:"
echo "export GITHUB_TOKEN=your_token"
echo "java -cp 'build/*:lib/*' com.navercorp.cubridqa.builder.Tester conf/tester_node_2.conf"
echo ""
echo "# Terminal 3 - Tester Node 3:"
echo "export GITHUB_TOKEN=your_token"
echo "java -cp 'build/*:lib/*' com.navercorp.cubridqa.builder.Tester conf/tester_node_3.conf"
echo ""

echo "Step 3: Start the Builder"
echo "-------------------------"
echo "# Terminal 4 - Builder:"
echo "export GITHUB_TOKEN=your_token"
echo "./bin/start_builder.sh"
echo ""

echo "Step 4: Send test request"
echo "-------------------------"
echo "Use this request to test with all 3 local nodes:"
echo ""
cat <<'SCRIPT'
curl -X POST http://localhost:8089/build \
  -H "Content-Type: application/json" \
  -d '{
    "commits": ["6ea587e"],
    "tests": [
      "shell/_01_utility/_08_csql/cases/1001.sh",
      "shell/_01_utility/_08_csql/cases/1002.sh",
      "shell/_01_utility/_08_csql/cases/1003.sh",
      "shell/_01_utility/_08_csql/cases/1004.sh",
      "shell/_01_utility/_08_csql/cases/1005.sh",
      "shell/_01_utility/_08_csql/cases/1006.sh"
    ],
    "callbackUrl": "http://localhost:8089/callback",
    "workerIps": ["localhost:8090", "localhost:8091", "localhost:8092"],
    "buildType": "debug"
  }'
SCRIPT

echo ""
echo "Expected Distribution:"
echo "---------------------"
echo "- Test 1001.sh → localhost:8090 (Node 1)"
echo "- Test 1002.sh → localhost:8091 (Node 2)"
echo "- Test 1003.sh → localhost:8092 (Node 3)"
echo "- Test 1004.sh → localhost:8090 (Node 1)"
echo "- Test 1005.sh → localhost:8091 (Node 2)"
echo "- Test 1006.sh → localhost:8092 (Node 3)"
echo ""
echo "Check builder.log to verify the distribution!"
