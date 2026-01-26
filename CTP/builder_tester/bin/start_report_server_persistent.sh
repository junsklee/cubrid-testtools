#!/bin/bash

# CUBRID Builder-Tester Report Server Persistent Startup Script
# This script starts the report server and automatically restarts it if it shuts down

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
echo -e "${GREEN}║  Starting CUBRID Builder-Tester Report Server (Persistent)   ║${NC}"
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

# Create log directory (in report-server/)
LOG_DIR="$REPORT_SERVER_DIR/logs"
LOG_FILE="$LOG_DIR/report-server-persistent.log"
mkdir -p "$LOG_DIR" || {
    echo -e "${RED}Error: Failed to create log directory: $LOG_DIR${NC}"
    exit 1
}

# Check if the server file exists
if [ ! -f "src/server.js" ]; then
    echo -e "${RED}Error: src/server.js not found in $REPORT_SERVER_DIR${NC}"
    exit 1
fi

# Start the server with auto-restart
echo -e "${GREEN}Starting report server on port ${REPORT_PORT}...${NC}"
echo -e "${GREEN}Builder: http://${BUILDER_HOST}:${BUILDER_PORT}${NC}"
echo -e "${GREEN}Logs: ${LOG_FILE}${NC}"
echo -e "${YELLOW}Server will automatically restart if it shuts down.${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop.${NC}"
echo ""

# Track restart count to prevent infinite restart loops
RESTART_COUNT=0
MAX_RESTARTS=100
RESTART_DELAY=5  # seconds to wait before restarting

server_pid=""
stop_requested=0

# Trap SIGINT/SIGTERM to allow graceful shutdown (and ensure child process is stopped)
shutdown() {
    stop_requested=1
    echo -e "\n${YELLOW}Shutdown requested. Stopping server...${NC}"
    if [ -n "${server_pid}" ] && kill -0 "${server_pid}" 2>/dev/null; then
        kill -INT "${server_pid}" 2>/dev/null || true
        wait "${server_pid}" 2>/dev/null || true
    fi
    exit 0
}
trap shutdown SIGINT SIGTERM

# Main loop: keep server running and restart if it exits
while true; do
    # Start the Node.js server
    printf '\n[%s] Starting server (restart %s/%s)\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$RESTART_COUNT" "$MAX_RESTARTS" >> "$LOG_FILE"
    node src/server.js "$REPORT_PORT" >> "$LOG_FILE" 2>&1 &
    server_pid=$!
    wait "$server_pid"
    server_exit_code=$?
    server_pid=""

    printf '[%s] Server exited with code %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$server_exit_code" >> "$LOG_FILE"
    
    # Exit code 130 is SIGINT (Ctrl+C), exit gracefully
    if [ "$server_exit_code" -eq 130 ] || [ "$stop_requested" -eq 1 ]; then
        echo -e "${YELLOW}Server stopped by user.${NC}"
        exit 0
    fi
    
    # Exit code 0 is clean exit, don't restart
    if [ "$server_exit_code" -eq 0 ]; then
        echo -e "${GREEN}Server exited cleanly.${NC}"
        exit 0
    fi
    
    # Server crashed or exited with error, restart it
    next_restart_count=$((RESTART_COUNT + 1))
    if [ "$next_restart_count" -gt "$MAX_RESTARTS" ]; then
        echo -e "${RED}Error: Server has restarted ${RESTART_COUNT} times. Stopping to prevent infinite loop.${NC}"
        echo -e "${YELLOW}Please check the server logs for errors.${NC}"
        exit 1
    fi
    RESTART_COUNT="$next_restart_count"
    
    echo ""
    echo -e "${YELLOW}Server exited with code ${server_exit_code}. Restarting in ${RESTART_DELAY} seconds... (${RESTART_COUNT}/${MAX_RESTARTS})${NC}"
    sleep "$RESTART_DELAY"
    echo -e "${GREEN}Restarting server...${NC}"
    echo ""
done
