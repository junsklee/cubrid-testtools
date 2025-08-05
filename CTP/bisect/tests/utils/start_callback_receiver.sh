#!/bin/bash

# Start the test callback receiver for bisect results
# Usage: ./start_callback_receiver.sh [port]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

PORT=${1:-8080}

# Get the actual IP address
LOCAL_IP=$(hostname -I | awk '{print $1}' 2>/dev/null || echo "localhost")

echo "Starting Test Callback Receiver on port $PORT"
echo "Endpoints:"
echo "  Local:    http://localhost:$PORT/bisect/result"
echo "  Network:  http://$LOCAL_IP:$PORT/bisect/result"
echo "Press Ctrl+C to stop"
echo

# Compile if needed
if [ ! -f "$SCRIPT_DIR/TestCallbackReceiver.class" ] || [ "$SCRIPT_DIR/TestCallbackReceiver.java" -nt "$SCRIPT_DIR/TestCallbackReceiver.class" ]; then
    echo "Compiling TestCallbackReceiver..."
    cd "$SCRIPT_DIR"
    javac -cp "$BASE_DIR/lib/*" TestCallbackReceiver.java
    if [ $? -ne 0 ]; then
        echo "Compilation failed!"
        exit 1
    fi
    echo "Compilation successful."
    echo
fi

# Run the callback receiver
cd "$SCRIPT_DIR"
java -cp "$BASE_DIR/lib/*:." TestCallbackReceiver $PORT