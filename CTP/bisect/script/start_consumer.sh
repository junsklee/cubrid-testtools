#!/bin/bash
#
# Start script for Bisect Consumer
#

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
CTP_BISECT_HOME="$(dirname "$SCRIPT_DIR")"

# Check if configuration file exists
CONFIG_FILE="$CTP_BISECT_HOME/conf/bisect_consumer.conf"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Configuration file not found: $CONFIG_FILE"
    echo "Creating default configuration..."
    mkdir -p "$CTP_BISECT_HOME/conf"
    cat > "$CONFIG_FILE" << EOF
# Bisect Consumer Configuration
# Note: consumer_port should be configured instead of listen_port
consumer_port=8090
work_dir=/tmp/bisect_consumer
cubrid_src_dir=$CUBRID
shell_tc_dir=~/cubrid-testcases-private-ex
EOF
    echo "Please edit $CONFIG_FILE and run again"
    exit 1
fi

# Check if JAR exists
if [ ! -f "$CTP_BISECT_HOME/lib/bisect-tool.jar" ]; then
    echo "Error: bisect-tool.jar not found. Please run build.sh first."
    exit 1
fi

# Create necessary directories
mkdir -p "$CTP_BISECT_HOME/log"
WORK_DIR=$(grep "^work_dir=" "$CONFIG_FILE" | cut -d'=' -f2 | tr -d ' ')
mkdir -p "$WORK_DIR"

# Check if consumer is already running
PID_FILE="$CTP_BISECT_HOME/bisect_consumer.pid"
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Bisect Consumer is already running (PID: $PID)"
        exit 1
    else
        echo "Removing stale PID file"
        rm -f "$PID_FILE"
    fi
fi

# Start the consumer
LOG_FILE="$CTP_BISECT_HOME/log/bisect_consumer.log"
echo "Starting Bisect Consumer..."
echo "Configuration: $CONFIG_FILE"
echo "Log file: $LOG_FILE"

# Start in background
nohup java -cp "$CTP_BISECT_HOME/lib/*" \
    com.navercorp.cubridqa.bisect.BisectConsumer \
    "$CONFIG_FILE" >> "$LOG_FILE" 2>&1 &
PID=$!

# Save PID
echo $PID > "$PID_FILE"

# Wait a moment and check if it started successfully
sleep 2
if ps -p "$PID" > /dev/null 2>&1; then
    echo "Bisect Consumer started successfully (PID: $PID)"
    echo "Logs: tail -f $LOG_FILE"
else
    echo "Failed to start Bisect Consumer"
    rm -f "$PID_FILE"
    tail -20 "$LOG_FILE"
    exit 1
fi
