#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
LOG_FILE="$SCRIPT_DIR/builder_output.log"
PID_FILE="$SCRIPT_DIR/builder.pid"

# Check for Java
if ! command -v java >/dev/null 2>&1; then
  echo "Error: Java runtime not found. Please install JRE/JDK." >&2
  exit 1
fi

# Set classpath
CLASSPATH="$PROJECT_ROOT/lib/json.jar:$PROJECT_ROOT/lib/builder-tester.jar:$PROJECT_ROOT/build"

# Configuration file
CONFIG_FILE="$PROJECT_ROOT/conf/builder.conf"
if [ "${1:-}" != "" ]; then
  CONFIG_FILE="$1"
fi
if [ ! -f "$CONFIG_FILE" ]; then
  echo "Error: Configuration file not found: $CONFIG_FILE" >&2
  exit 1
fi

# If already running, refuse to start
if [ -f "$PID_FILE" ]; then
  oldpid=$(cat "$PID_FILE" || true)
  if [ -n "${oldpid:-}" ] && kill -0 "$oldpid" 2>/dev/null; then
    echo "Builder already running with pid $oldpid (PID file: $PID_FILE)" >&2
    exit 0
  fi
fi

# Start in background
nohup java -cp "$CLASSPATH" \
  -Djava.util.logging.SimpleFormatter.format='%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n' \
  com.navercorp.cubridqa.builder.Builder "$CONFIG_FILE" \
  >> "$LOG_FILE" 2>&1 &
PID=$!

echo "$PID" > "$PID_FILE"
echo "Builder started (pid $PID). Logs: $LOG_FILE, PID file: $PID_FILE"