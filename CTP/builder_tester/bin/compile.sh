#!/bin/bash

# Compile Builder-Tester System

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

echo "Building Builder-Tester System..."
echo "Project root: $PROJECT_ROOT"

# Check for Java
if ! command -v javac &> /dev/null; then
    echo "Error: Java compiler (javac) not found. Please install JDK."
    exit 1
fi

# Create build directory
BUILD_DIR="$PROJECT_ROOT/build"
mkdir -p "$BUILD_DIR"

# Set classpath
CLASSPATH="$PROJECT_ROOT/lib/json.jar:$BUILD_DIR"

# Source directory
SRC_DIR="$PROJECT_ROOT/src"

# Find all Java files
echo "Finding Java files..."
JAVA_FILES=$(find "$SRC_DIR" -name "*.java")

if [ -z "$JAVA_FILES" ]; then
    echo "Error: No Java files found in $SRC_DIR"
    exit 1
fi

echo "Compiling $(echo "$JAVA_FILES" | wc -l) Java files..."

# Compile
javac -cp "$CLASSPATH" -d "$BUILD_DIR" $JAVA_FILES

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo "Build output: $BUILD_DIR"
    
    # Create JAR file
    echo "Creating JAR file..."
    cd "$BUILD_DIR"
    jar cf "$PROJECT_ROOT/lib/builder-tester.jar" com/
    cd - > /dev/null
    
    echo "JAR created: $PROJECT_ROOT/lib/builder-tester.jar"
else
    echo "Compilation failed!"
    exit 1
fi
