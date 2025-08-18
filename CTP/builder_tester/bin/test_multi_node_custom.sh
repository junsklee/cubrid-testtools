#!/bin/bash

# Multi-Node Test with Fixed Download Endpoint
# Using the commits and tests specified by the user

BUILDER_HOST="${BUILDER_HOST:-localhost}"
BUILDER_PORT="${BUILDER_PORT:-8089}"

# Ccache pre-checks and baseline stats
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
CONFIG_FILE="$PROJECT_ROOT/conf/builder.conf"

echo ""
echo "Ccache - Pre-checks"
echo "===================="
if [ -f "$CONFIG_FILE" ]; then
  CCACHE_DIR_CFG=$(grep "^ccache_dir=" "$CONFIG_FILE" | cut -d'=' -f2 | sed "s|~|$HOME|g")
  export CCACHE_DIR="$CCACHE_DIR_CFG"
  echo "Configured CCACHE_DIR: ${CCACHE_DIR:-unset}"
fi

if [ -x "$SCRIPT_DIR/manage_ccache.sh" ]; then
  "$SCRIPT_DIR/manage_ccache.sh" status || true
else
  echo "manage_ccache.sh not found; skipping status"
fi

if command -v ccache >/dev/null 2>&1; then
  echo "Resetting ccache statistics (ccache -z)"
  ccache -z || true
fi

echo "Multi-Node Test - Fixed Download Endpoint"
echo "========================================="
echo "Builder: http://${BUILDER_HOST}:${BUILDER_PORT}"
echo "Local Tester: localhost:8090 (192.168.1.5)"
echo "Remote Tester: 192.168.1.15:8090 (junHA user)"
echo ""
echo "Using custom commits and tests from this script"
echo "Commits: 911e5d3, 6407e07"
echo "Tests: 8 test cases as listed in the payload"
echo ""
echo "Sending build request with test distribution..."
echo ""

# Request with the specified commits and tests
cat <<EOF | curl -X POST "http://${BUILDER_HOST}:${BUILDER_PORT}/build" \
  -H "Content-Type: application/json" \
  -d @-
{
  "commits": ["911e5d3561156d0b3ad2043a6d5a00dbc937c994", "6407e0769f8426cb2ead1d47f127bb0876f9c37d"],
  "tests": [
    "shell/_05_addition/cubridsus1961/cases/cubridsus1961.sh",
    "shell/_06_issues/_12_2h/bug_bts_9521_1/cases/bug_bts_9521_1.sh",
    "shell/_06_issues/_17_1h/cbrd_20867/cases/cbrd_20867.sh",
    "shell/_06_issues/_25_1h/cbrd_26020/cases/cbrd_26020.sh",
    "shell/_28_features_844/issue_10984_query_profiling/_03_mixed_test/_07_show_columns/cases/_07_show_columns.sh",
    "shell/_08_shard/_50_cubridsus/bug_bts_10130/cases/bug_bts_10130.sh",
    "shell/_10_plcsql/bug_fix/cbrd_25894/cases/cbrd_25894.sh",
    "shell/_38_fig/cbrd_24882/vacuumdb/cases/vacuumdb.sh"
  ],
  "callbackUrl": "http://localhost:8089/callback",
  "workerIps": ["localhost", "192.168.1.15:8090"],
  "buildType": "debug"
}
EOF

echo ""
echo "Ccache - Post-request snapshot (after short wait)"
echo "=================================================="
sleep 30
if [ -x "$SCRIPT_DIR/manage_ccache.sh" ]; then
  "$SCRIPT_DIR/manage_ccache.sh" stats || true
fi

echo ""
echo ""
echo "Request sent! Expected test distribution:"
echo "========================================="
echo "Round-robin distribution across 2 nodes with 8 tests per commit (16 total tests):"
echo ""
echo "For each commit (911e5d3, 6407e07):"
echo "- Test 1 (cubridsus1961.sh) → localhost:8090 (Local)"
echo "- Test 2 (bug_bts_9521_1.sh) → 192.168.1.15:8090 (Remote)"
echo "- Test 3 (cbrd_20867.sh) → localhost:8090 (Local)"
echo "- Test 4 (cbrd_26020.sh) → 192.168.1.15:8090 (Remote)"
echo "- Test 5 (_07_show_columns.sh) → localhost:8090 (Local)"
echo "- Test 6 (bug_bts_10130.sh) → 192.168.1.15:8090 (Remote)"
echo "- Test 7 (cbrd_25894.sh) → localhost:8090 (Local)"
echo "- Test 8 (vacuumdb.sh) → 192.168.1.15:8090 (Remote)"
echo ""
echo "Total: 8 tests per node (16 tests total)"
echo ""
echo "Key fixes applied:"
echo "- ✅ Fixed BuildDownloadHandler to search in build subdirectories"
echo "- ✅ Build packages now accessible via HTTP for remote testers"
echo "- ✅ Multi-node validation supports both workerIp and workerIps"
echo ""
echo "Monitor progress with:"
echo "  tail -f log/requests/req_\$(date +%Y%m%d)_*/builder.log"
echo ""
echo "Check download endpoint:"
echo "  curl -I http://${BUILDER_HOST}:${BUILDER_PORT}/download/build/cubrid_911e5d3.tar.gz"