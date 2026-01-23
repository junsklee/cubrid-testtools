#!/bin/bash

###############################################################################
# Server Monitor Script
# Checks if the report server is running and restarts it if needed
# Can be run standalone or via cron (every 5 minutes)
###############################################################################

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

# Configuration
PORT=${REPORT_PORT:-8091}
HOST=${HOST:-localhost}
HEALTH_CHECK_URL="http://${HOST}:${PORT}/health"
TIMEOUT=5
PID_FILE="${SCRIPT_DIR}/.server.pid"
LOG_FILE="${SCRIPT_DIR}/monitor.log"
STATE_FILE="${SCRIPT_DIR}/.monitor-state"
MAX_RESTART_ATTEMPTS=3
RESTART_COOLDOWN=60  # seconds between restart attempts

command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Logging function
log() {
    local level=$1
    shift
    local message="$*"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] [$level] $message" | tee -a "$LOG_FILE"
}

read_state() {
    restart_count=0
    last_restart_time=0
    if [ -f "$STATE_FILE" ]; then
        local rc
        local ts
        rc=$(awk -F= '/^restart_count=/{print $2}' "$STATE_FILE" 2>/dev/null | head -n 1)
        ts=$(awk -F= '/^last_restart_time=/{print $2}' "$STATE_FILE" 2>/dev/null | head -n 1)
        if [[ "$rc" =~ ^[0-9]+$ ]]; then
            restart_count="$rc"
        fi
        if [[ "$ts" =~ ^[0-9]+$ ]]; then
            last_restart_time="$ts"
        fi
    fi
}

write_state() {
    printf 'restart_count=%s\nlast_restart_time=%s\n' "$restart_count" "$last_restart_time" > "$STATE_FILE"
}

reset_state() {
    restart_count=0
    last_restart_time=0
    write_state
}

is_report_server_pid() {
    local pid=$1
    local args
    args=$(ps -p "$pid" -o args= 2>/dev/null || true)
    echo "$args" | grep -Eq 'node( .*)? src/server\.js'
}

# Check if server is responding
check_server_health() {
    local response
    if ! command_exists curl; then
        log "ERROR" "curl not found; cannot perform health check"
        return 1
    fi

    response=$(curl -s -m "$TIMEOUT" "$HEALTH_CHECK_URL" 2>/dev/null)
    local exit_code=$?
    
    if [ $exit_code -eq 0 ] && echo "$response" | grep -q '"status":"healthy"'; then
        return 0  # Server is healthy
    else
        return 1  # Server is not responding
    fi
}

# Check if server process is running
check_server_process() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && is_report_server_pid "$pid"; then
            return 0  # Process exists
        else
            # PID file exists but process is dead
            rm -f "$PID_FILE"
        fi
    fi
    
    # Try to find process by port (preferred) or by cmdline (fallback)
    if command_exists lsof; then
        local pid
        pid=$(lsof -ti:"$PORT" 2>/dev/null | head -n 1)
        if [ -n "$pid" ] && is_report_server_pid "$pid"; then
            echo "$pid" > "$PID_FILE"
            return 0
        fi
    elif command_exists pgrep; then
        local pid
        pid=$(pgrep -f 'node( .*)? src/server\.js' 2>/dev/null | head -n 1)
        if [ -n "$pid" ]; then
            echo "$pid" > "$PID_FILE"
            return 0
        fi
    fi
    
    return 1  # No process found
}

# Start the server
start_server() {
    log "INFO" "Starting report server..."

    if ! command_exists node; then
        log "ERROR" "node not found; cannot start server"
        return 1
    fi

    # Check if already running
    if check_server_process; then
        log "WARN" "Server process already exists, skipping start"
        return 1
    fi
    
    # Start server in background (ensure config and health check target match)
    nohup env REPORT_PORT="$PORT" HOST="$HOST" node src/server.js "$PORT" > "${SCRIPT_DIR}/server.log" 2>&1 &
    local server_pid=$!
    
    # Wait a moment for server to start
    sleep 2
    
    # Check if process is still running
    if ! kill -0 "$server_pid" 2>/dev/null; then
        log "ERROR" "Server failed to start (process died immediately)"
        return 1
    fi
    
    # Save PID
    echo "$server_pid" > "$PID_FILE"
    log "INFO" "Server started with PID: $server_pid"
    
    # Wait for server to be ready
    local attempts=0
    local max_attempts=10
    while [ $attempts -lt $max_attempts ]; do
        sleep 1
        if check_server_health; then
            log "INFO" "Server is healthy and responding"
            return 0
        fi
        attempts=$((attempts + 1))
    done
    
    log "WARN" "Server started but health check failed after $max_attempts attempts"
    return 1
}

# Stop the server
stop_server() {
    log "INFO" "Stopping server..."
    
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && is_report_server_pid "$pid"; then
            kill "$pid" 2>/dev/null
            sleep 2
            
            # Force kill if still running
            if kill -0 "$pid" 2>/dev/null; then
                log "WARN" "Server did not stop gracefully, force killing..."
                kill -9 "$pid" 2>/dev/null
            fi
        fi
        rm -f "$PID_FILE"
    fi
    
    # Also try to kill by port (only if it matches the report server)
    if command_exists lsof; then
        local pid
        pid=$(lsof -ti:"$PORT" 2>/dev/null | head -n 1)
        if [ -n "$pid" ] && is_report_server_pid "$pid"; then
            kill "$pid" 2>/dev/null
            sleep 1
            if kill -0 "$pid" 2>/dev/null; then
                kill -9 "$pid" 2>/dev/null
            fi
        fi
    fi
    
    log "INFO" "Server stopped"
}

# Restart the server
restart_server() {
    log "INFO" "Restarting server..."
    stop_server
    sleep 2
    start_server
}

# Main monitoring function
monitor_server() {
    local restart_count
    local last_restart_time
    read_state
    
    # Check if server process exists
    if ! check_server_process; then
        log "WARN" "Server process not found"
        if start_server; then
            log "INFO" "Server started successfully"
            reset_state
        else
            log "ERROR" "Failed to start server"
            return 1
        fi
        return 0
    fi
    
    # Check server health
    if ! check_server_health; then
        log "WARN" "Server is not responding to health checks"
        
        local current_time=$(date +%s)
        local time_since_restart=$((current_time - last_restart_time))

        # Enforce cooldown between restart attempts
        if [ "$last_restart_time" -gt 0 ] && [ $time_since_restart -lt $RESTART_COOLDOWN ]; then
            log "WARN" "Restart cooldown active (${time_since_restart}s/${RESTART_COOLDOWN}s); skipping restart"
            return 1
        fi

        # Prevent restart loops across cron invocations
        if [ $restart_count -ge $MAX_RESTART_ATTEMPTS ]; then
            log "ERROR" "Too many restart attempts ($restart_count/$MAX_RESTART_ATTEMPTS). Manual intervention required."
            return 1
        fi
        
        # Restart server
        if restart_server; then
            restart_count=$((restart_count + 1))
            last_restart_time=$(date +%s)
            write_state
            log "INFO" "Server restarted successfully (attempt $restart_count)"
        else
            log "ERROR" "Failed to restart server"
            return 1
        fi
    else
        # Server is healthy - reset restart counter
        if [ "$restart_count" -gt 0 ]; then
            log "INFO" "Server is healthy, resetting restart counter"
        fi
        reset_state
    fi
    
    return 0
}

# Main execution
main() {
    local mode=${1:-"once"}
    
    case "$mode" in
        "once")
            monitor_server
            ;;
        "loop")
            log "INFO" "Starting monitor in loop mode (checking every 5 minutes)"
            while true; do
                monitor_server
                sleep 300  # 5 minutes
            done
            ;;
        "start")
            start_server
            ;;
        "stop")
            stop_server
            ;;
        "restart")
            restart_server
            ;;
        "status")
            if check_server_process && check_server_health; then
                echo -e "${GREEN}Server is running and healthy${NC}"
                if [ -f "$PID_FILE" ]; then
                    echo "PID: $(cat "$PID_FILE")"
                fi
                exit 0
            else
                echo -e "${RED}Server is not running or not healthy${NC}"
                exit 1
            fi
            ;;
        *)
            echo "Usage: $0 {once|loop|start|stop|restart|status}"
            echo ""
            echo "  once     - Check once and exit"
            echo "  loop     - Check every 5 minutes (runs indefinitely)"
            echo "  start    - Start the server"
            echo "  stop     - Stop the server"
            echo "  restart  - Restart the server"
            echo "  status   - Check server status"
            exit 1
            ;;
    esac
}

# Run main function
main "$@"
