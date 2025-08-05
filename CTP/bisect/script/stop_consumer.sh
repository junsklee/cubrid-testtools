#!/bin/bash

# Consumer stop script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_FILE="$BASE_DIR/runtime/bisect_consumer.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "BisectConsumer is not running (no PID file found)"
    exit 0
fi

PID=$(cat "$PID_FILE")

if ps -p $PID > /dev/null 2>&1; then
    echo "Stopping BisectConsumer (PID: $PID)..."
    kill $PID
    
    # Wait for graceful shutdown
    for i in {1..10}; do
        if ! ps -p $PID > /dev/null 2>&1; then
            echo "BisectConsumer stopped successfully"
            rm -f "$PID_FILE"
            exit 0
        fi
        sleep 1
    done
    
    # Force kill if still running
    echo "Force killing BisectConsumer..."
    kill -9 $PID
    rm -f "$PID_FILE"
else
    echo "BisectConsumer is not running (process $PID not found)"
    rm -f "$PID_FILE"
fi