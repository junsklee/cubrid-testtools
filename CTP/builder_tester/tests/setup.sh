#!/bin/bash
#
# Test Suite Installation Script
# Sets up the test environment and installs pre-commit hook
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== CTP Builder-Tester Test Suite Setup ===${NC}"
echo ""

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Check prerequisites
echo -e "${YELLOW}Checking prerequisites...${NC}"

# Check Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}✗ Java not found. Please install Java 8 or later.${NC}"
    exit 1
else
    echo -e "${GREEN}✓ Java found${NC}"
fi

# Check curl
if ! command -v curl &> /dev/null; then
    echo -e "${RED}✗ curl not found. Please install curl.${NC}"
    exit 1
else
    echo -e "${GREEN}✓ curl found${NC}"
fi

# Check Docker (optional)
if command -v docker &> /dev/null; then
    echo -e "${GREEN}✓ Docker found (optional)${NC}"
else
    echo -e "${YELLOW}⚠ Docker not found (some tests will be skipped)${NC}"
fi

echo ""

# Make all test scripts executable
echo -e "${YELLOW}Setting up test files...${NC}"
find "$SCRIPT_DIR" -name "*.sh" -type f -exec chmod +x {} \;
echo -e "${GREEN}✓ Test files configured${NC}"

# Compile project if needed
if [[ ! -d "$PROJECT_ROOT/build" ]]; then
    echo ""
    echo -e "${YELLOW}Compiling project...${NC}"
    if "$PROJECT_ROOT/bin/compile.sh"; then
        echo -e "${GREEN}✓ Project compiled${NC}"
    else
        echo -e "${RED}✗ Compilation failed${NC}"
        exit 1
    fi
fi

# Install pre-commit hook (optional)
echo ""
echo -e "${YELLOW}Install pre-commit hook?${NC}"
echo "This will run tests automatically before each commit."
read -p "Install? (y/N): " -n 1 -r
echo ""

if [[ $REPLY =~ ^[Yy]$ ]]; then
    GIT_ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
    if [[ -n "$GIT_ROOT" ]]; then
        cp "$SCRIPT_DIR/pre-commit.hook" "$GIT_ROOT/.git/hooks/pre-commit"
        chmod +x "$GIT_ROOT/.git/hooks/pre-commit"
        echo -e "${GREEN}✓ Pre-commit hook installed${NC}"
    else
        echo -e "${YELLOW}⚠ Not in a git repository, skipping hook installation${NC}"
    fi
else
    echo "Skipping pre-commit hook installation"
fi

echo ""
echo -e "${GREEN}=== Setup Complete ===${NC}"
echo ""
echo "To run the test suite:"
echo "  cd $SCRIPT_DIR"
echo "  ./run_tests.sh"
echo ""
echo "For help:"
echo "  ./run_tests.sh --help"
echo ""
echo "Quick test commands:"
echo "  ./run_tests.sh           # Run all tests"
echo "  ./run_tests.sh -s unit   # Run only unit tests"
echo "  ./run_tests.sh -p        # Run tests in parallel"
echo "  ./run_tests.sh -v        # Verbose output"
echo ""