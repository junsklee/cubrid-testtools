#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
PID_FILE="$SCRIPT_DIR/tester.pid"

stop_by_pid() {
  local pid="$1"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "Stopping Tester (pid $pid)..."
    kill "$pid" 2>/dev/null || true
    sleep 2
    if kill -0 "$pid" 2>/dev/null; then
      echo "Force killing Tester (pid $pid)..."
      kill -9 "$pid" 2>/dev/null || true
    fi
  fi
}

# Stop via PID file if present
if [ -f "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE" 2>/dev/null || true)"
  stop_by_pid "${PID:-}"
  rm -f "$PID_FILE"
fi

# Fallback: kill by Java main class, only for processes started from this installation
# (its lib/ is on their classpath): a broad match also kills testers in containers or
# other checkouts running as the same user on this host.
pkill -f "${PROJECT_ROOT}/lib/json.jar.*com.navercorp.cubridqa.builder.Tester" 2>/dev/null || true

# Verify port is free (best effort)
if command -v netstat >/dev/null 2>&1; then
  if netstat -tlnp 2>/dev/null | grep -q ':8090'; then
    echo "Warning: Port 8090 still in use after stop attempt." >&2
  fi
fi

echo "Tester stop completed."