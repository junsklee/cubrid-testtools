#!/bin/bash
#
# Build script for Bisect Tool
#

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
CTP_BISECT_HOME="$SCRIPT_DIR"

# Create directories
mkdir -p "$CTP_BISECT_HOME/build"
mkdir -p "$CTP_BISECT_HOME/lib"

# Check for required dependencies
echo "Checking dependencies..."

# Check for JSON library
if [ ! -f "$CTP_BISECT_HOME/lib/json.jar" ]; then
    echo "Downloading JSON library..."
    wget -O "$CTP_BISECT_HOME/lib/json.jar" \
        "https://search.maven.org/remotecontent?filepath=org/json/json/20231013/json-20231013.jar"
fi

# Compile Java files
echo "Compiling Java sources..."
cd "$CTP_BISECT_HOME"

# Find all Java files
JAVA_FILES=$(find src -name "*.java")

# Compile
javac -cp "lib/*" -d build $JAVA_FILES

if [ $? -eq 0 ]; then
    echo "Build successful!"
    
    # Create JAR file
    echo "Creating JAR file..."
    cd build
    jar cf ../lib/bisect-tool.jar com/
    cd ..
    
    echo "JAR file created: lib/bisect-tool.jar"
else
    echo "Build failed!"
    exit 1
fi
