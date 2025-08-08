#!/bin/bash

# Run Tester Service

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

# Check for Java
if ! command -v java &> /dev/null; then
    echo "Error: Java runtime not found. Please install JRE/JDK."
    exit 1
fi

# Set classpath
CLASSPATH="$PROJECT_ROOT/lib/json.jar:$PROJECT_ROOT/lib/builder-tester.jar:$PROJECT_ROOT/build"

# Configuration file
CONFIG_FILE="$PROJECT_ROOT/conf/tester.conf"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Configuration file not found: $CONFIG_FILE"
    exit 1
fi

# Check if custom config provided
if [ "$1" != "" ]; then
    CONFIG_FILE="$1"
fi

echo "Starting Tester Service..."
echo "Configuration: $CONFIG_FILE"
echo "----------------------------------------"

# Run Tester
java -cp "$CLASSPATH" \
     -Djava.util.logging.SimpleFormatter.format='%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n' \
     com.navercorp.cubridqa.builder.Tester "$CONFIG_FILE"
