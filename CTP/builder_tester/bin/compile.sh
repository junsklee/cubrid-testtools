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

# Clean and create build directory (force full rebuild)
BUILD_DIR="$PROJECT_ROOT/build"
echo "Cleaning previous build..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# Clean old JAR
rm -f "$PROJECT_ROOT/lib/builder-tester.jar"

# Remove any stray .class files from src directory (they shouldn't be there)
echo "Cleaning stray class files from source directory..."
find "$PROJECT_ROOT/src" -name "*.class" -type f -delete 2>/dev/null || true

# Set classpath
CLASSPATH="$PROJECT_ROOT/lib/json.jar:$PROJECT_ROOT/lib/jsch-0.1.55.jar:$BUILD_DIR"

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

# Compile (capture exit code immediately)
javac -cp "$CLASSPATH" -d "$BUILD_DIR" $JAVA_FILES
JAVAC_EXIT=$?

if [ $JAVAC_EXIT -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Copying resources (non-Java files) into build..."
# Copy all non-Java files from src into build, preserving paths (POSIX-compatible)
find "$SRC_DIR" -type f ! -name "*.java" | while IFS= read -r file; do
    rel_path="${file#$SRC_DIR/}"
    dest_dir="$BUILD_DIR/$(dirname "$rel_path")"
    mkdir -p "$dest_dir"
    cp "$file" "$dest_dir/"
done

echo "Compilation successful!"
echo "Build output: $BUILD_DIR"

# Create JAR file
echo "Creating JAR file..."
cd "$BUILD_DIR"
jar cf "$PROJECT_ROOT/lib/builder-tester.jar" com/
cd - > /dev/null

echo "JAR created: $PROJECT_ROOT/lib/builder-tester.jar"
