#!/bin/bash

# Consumer start script with enhanced logging

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$BASE_DIR/log"
PID_FILE="$BASE_DIR/bisect_consumer.pid"
LOG_FILE="$LOG_DIR/bisect_consumer.log"

# Check if already running
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p $PID > /dev/null 2>&1; then
        echo "BisectConsumer is already running with PID $PID"
        exit 1
    else
        echo "Removing stale PID file"
        rm -f "$PID_FILE"
    fi
fi

# Create log directory
mkdir -p "$LOG_DIR"

# Check configuration
CONFIG_FILE="$BASE_DIR/conf/bisect_consumer.conf"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Configuration file not found: $CONFIG_FILE"
    echo "Creating default configuration..."
    
    mkdir -p "$BASE_DIR/conf"
    cat > "$CONFIG_FILE" << 'EOF'
# Bisect Consumer Configuration
consumer_port=8090
work_dir=/tmp/bisect_consumer
cubrid_src_dir=$CUBRID
shell_tc_dir=~/cubrid-testcases-private-ex
EOF
    
    echo "Default configuration created. Please edit: $CONFIG_FILE"
    exit 1
fi

echo "Starting BisectConsumer..."
echo "Configuration: $CONFIG_FILE"
echo "Log file: $LOG_FILE"

# Start consumer with proper logging
cd "$BASE_DIR"
nohup java -cp "build/*:lib/*" \
    -Djava.util.logging.config.file=/dev/null \
    -Djava.util.logging.SimpleFormatter.format='%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s: %5$s%6$s%n' \
    com.navercorp.cubridqa.bisect.BisectConsumer "$CONFIG_FILE" \
    >> "$LOG_FILE" 2>&1 &

PID=$!
echo $PID > "$PID_FILE"

# Wait a moment and check if it started successfully
sleep 2
if ps -p $PID > /dev/null 2>&1; then
    echo "BisectConsumer started successfully with PID $PID"
    echo "Log file: $LOG_FILE"
    echo "To monitor: tail -f $LOG_FILE"
    
    # Show initial log output
    echo
    echo "Initial log output:"
    tail -10 "$LOG_FILE"
else
    echo "Failed to start BisectConsumer"
    echo "Check the log file for errors: $LOG_FILE"
    rm -f "$PID_FILE"
    exit 1
fi