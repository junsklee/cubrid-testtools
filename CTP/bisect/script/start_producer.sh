#!/bin/bash
#
# Start script for Bisect Producer
#

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
CTP_BISECT_HOME="$(dirname "$SCRIPT_DIR")"

# Check if configuration file exists
CONFIG_FILE="$CTP_BISECT_HOME/conf/bisect_producer.conf"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Configuration file not found: $CONFIG_FILE"
    echo "Creating default configuration..."
    mkdir -p "$CTP_BISECT_HOME/conf"
    cat > "$CONFIG_FILE" << EOF
# Bisect Producer Configuration
listen_port=8089
cubrid_src_dir=$CUBRID
shell_tc_dir=~/cubrid-testcases-private-ex
build_arg=-g ninja -m debug build
build_dir=build_x86_64_debug
work_dir=/tmp/bisect_work
consumer_port=8090
max_concurrent_bisects=4
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

# Check if producer is already running
PID_FILE="$CTP_BISECT_HOME/bisect_producer.pid"
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Bisect Producer is already running (PID: $PID)"
        exit 1
    else
        echo "Removing stale PID file"
        rm -f "$PID_FILE"
    fi
fi

# Start the producer
LOG_FILE="$CTP_BISECT_HOME/log/bisect_producer.log"
echo "Starting Bisect Producer..."
echo "Configuration: $CONFIG_FILE"
echo "Log file: $LOG_FILE"

# Start in background
nohup java -cp "$CTP_BISECT_HOME/lib/*" \
    com.navercorp.cubridqa.bisect.BisectProducer \
    "$CONFIG_FILE" >> "$LOG_FILE" 2>&1 &
PID=$!

# Save PID
echo $PID > "$PID_FILE"

# Wait a moment and check if it started successfully
sleep 2
if ps -p "$PID" > /dev/null 2>&1; then
    echo "Bisect Producer started successfully (PID: $PID)"
    echo "Logs: tail -f $LOG_FILE"
else
    echo "Failed to start Bisect Producer"
    rm -f "$PID_FILE"
    tail -20 "$LOG_FILE"
    exit 1
fi
