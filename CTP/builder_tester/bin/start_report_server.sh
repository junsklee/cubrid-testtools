#!/bin/bash

# CUBRID Builder-Tester Report Server Startup Script
# This script starts the integrated report server with dashboard

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPORT_SERVER_DIR="$SCRIPT_DIR/../report-server"

# Default configuration
export BUILDER_HOST="${BUILDER_HOST:-localhost}"
export BUILDER_PORT="${BUILDER_PORT:-8089}"
REPORT_PORT="${REPORT_PORT:-8091}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║     Starting CUBRID Builder-Tester Report Server              ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo -e "${RED}Error: Node.js is not installed. Please install Node.js first.${NC}"
    exit 1
fi

# Check if Builder is accessible
echo -e "${YELLOW}Checking Builder service at http://${BUILDER_HOST}:${BUILDER_PORT}...${NC}"
if curl -s -f -o /dev/null "http://${BUILDER_HOST}:${BUILDER_PORT}/health"; then
    echo -e "${GREEN}✓ Builder service is accessible${NC}"
else
    echo -e "${YELLOW}⚠ Warning: Builder service is not accessible at http://${BUILDER_HOST}:${BUILDER_PORT}${NC}"
    echo -e "${YELLOW}  The dashboard will still start but may not be able to submit builds.${NC}"
    echo ""
    read -p "Do you want to continue anyway? (y/n) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

# Check if GitHub token is set (optional but recommended)
if [ -z "$GITHUB_TOKEN" ]; then
    echo -e "${YELLOW}⚠ Warning: GITHUB_TOKEN is not set${NC}"
    echo -e "${YELLOW}  You may encounter rate limits when fetching commits from GitHub.${NC}"
    echo -e "${YELLOW}  Set it with: export GITHUB_TOKEN=your_github_token${NC}"
    echo ""
fi

# Change to report server directory
cd "$REPORT_SERVER_DIR" || exit 1

# Check if the integrated server file exists
if [ ! -f "report-server-integrated.js" ]; then
    echo -e "${RED}Error: report-server-integrated.js not found in $REPORT_SERVER_DIR${NC}"
    exit 1
fi

# Start the server
echo -e "${GREEN}Starting report server on port ${REPORT_PORT}...${NC}"
echo -e "${GREEN}Builder: http://${BUILDER_HOST}:${BUILDER_PORT}${NC}"
echo ""

# Start the Node.js server
node report-server-integrated.js "$REPORT_PORT"
