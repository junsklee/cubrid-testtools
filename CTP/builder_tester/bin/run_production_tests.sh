#!/bin/bash

# Production Hardening Test Runner
# Executes unit tests and integration tests for v2 hardening patches

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "========================================="
echo "Production Hardening Test Suite"
echo "========================================="
echo "Project: $PROJECT_ROOT"
echo ""

# Check for JAR
JAR_PATH="$PROJECT_ROOT/lib/builder-tester.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: builder-tester.jar not found at $JAR_PATH"
    echo "Please run: cd $PROJECT_ROOT/bin && sh compile.sh"
    exit 1
fi

echo "✓ Found JAR: $JAR_PATH"
echo ""

# Check for JUnit
JUNIT_JAR="$PROJECT_ROOT/lib/junit-4.12.jar"
if [ ! -f "$JUNIT_JAR" ]; then
    echo "WARNING: JUnit not found at $JUNIT_JAR"
    echo "Attempting to download JUnit..."
    mkdir -p "$PROJECT_ROOT/lib"
    curl -L -o "$JUNIT_JAR" https://repo1.maven.org/maven2/junit/junit/4.12/junit-4.12.jar || {
        echo "ERROR: Failed to download JUnit"
        echo "Please manually download junit-4.12.jar to $PROJECT_ROOT/lib/"
        exit 1
    }
fi

HAMCREST_JAR="$PROJECT_ROOT/lib/hamcrest-core-1.3.jar"
if [ ! -f "$HAMCREST_JAR" ]; then
    echo "Downloading Hamcrest..."
    curl -L -o "$HAMCREST_JAR" https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar || {
        echo "ERROR: Failed to download Hamcrest"
        exit 1
    }
fi

echo "✓ Found test dependencies"
echo ""

# Find test classes
TEST_CLASSES=(
    "com.navercorp.cubridqa.builder.test.NodeSnapshotParsingTest"
    "com.navercorp.cubridqa.builder.test.ProductionHardeningIntegrationTest"
)

echo "========================================="
echo "Running Production Hardening Tests"
echo "========================================="
echo ""

CLASSPATH="$JAR_PATH:$JUNIT_JAR:$HAMCREST_JAR"
PASSED=0
FAILED=0
ERRORS=()

for TEST_CLASS in "${TEST_CLASSES[@]}"; do
    echo "Running: $TEST_CLASS"
    if java -cp "$CLASSPATH" org.junit.runner.JUnitCore "$TEST_CLASS" 2>&1; then
        PASSED=$((PASSED + 1))
        echo "✓ PASSED"
    else
        FAILED=$((FAILED + 1))
        ERRORS+=("$TEST_CLASS")
        echo "✗ FAILED"
    fi
    echo ""
done

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
else
    echo ""
    echo "✓ All production hardening tests passed!"
    exit 0
fi
