#!/bin/bash

# Unit test runner
# Runs every test class in src/com/navercorp/cubridqa/builder/test. The tests are plain
# main() programs that check with `assert`, so they run with -ea against the compiled jar.
#
# Usage: bin/run_production_tests.sh [TestClassName ...]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEST_SRC="$PROJECT_ROOT/src/com/navercorp/cubridqa/builder/test"

echo "========================================="
echo "Builder-Tester Unit Tests"
echo "========================================="
echo "Project: $PROJECT_ROOT"
echo ""

JAR_PATH="$PROJECT_ROOT/lib/builder-tester.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: builder-tester.jar not found at $JAR_PATH"
    echo "Please run: cd $PROJECT_ROOT/bin && sh compile.sh"
    exit 1
fi
CLASSPATH="$JAR_PATH:$PROJECT_ROOT/lib/json.jar:$PROJECT_ROOT/lib/jsch-0.1.55.jar"

if [ $# -gt 0 ]; then
    TEST_CLASSES=("$@")
else
    TEST_CLASSES=()
    for f in "$TEST_SRC"/*.java; do
        TEST_CLASSES+=("$(basename "$f" .java)")
    done
fi

PASSED=0
FAILED=0
ERRORS=()

# Tests write scratch files relative to the working directory
RUN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/bt_tests.XXXXXX")
trap 'rm -rf "$RUN_DIR"' EXIT

for TEST_CLASS in "${TEST_CLASSES[@]}"; do
    echo "Running: $TEST_CLASS"
    if (cd "$RUN_DIR" && timeout 300 java -ea -cp "$CLASSPATH" "com.navercorp.cubridqa.builder.test.$TEST_CLASS" > "$RUN_DIR/$TEST_CLASS.out" 2>&1); then
        PASSED=$((PASSED + 1))
        echo "  PASSED"
    else
        FAILED=$((FAILED + 1))
        ERRORS+=("$TEST_CLASS")
        echo "  FAILED; last lines:"
        tail -5 "$RUN_DIR/$TEST_CLASS.out" | sed 's/^/    /'
    fi
done

echo ""
echo "========================================="
echo "Test Results Summary"
echo "========================================="
echo "Total Tests: $((PASSED + FAILED))"
echo "Passed: $PASSED"
echo "Failed: $FAILED"

if [ $FAILED -gt 0 ]; then
    echo ""
    echo "Failed Tests:"
    for ERROR in "${ERRORS[@]}"; do
        echo "  - $ERROR"
    done
    exit 1
fi
echo ""
echo "All tests passed"
exit 0
