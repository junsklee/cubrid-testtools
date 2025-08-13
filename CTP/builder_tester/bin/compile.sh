#!/bin/bash

# Compile Builder-Tester System with Kubernetes support

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

echo "Building Builder-Tester System with Kubernetes support..."
echo "Project root: $PROJECT_ROOT"

# Check for Java
if ! command -v javac &> /dev/null; then
    echo "Error: Java compiler (javac) not found. Please install JDK."
    exit 1
fi

# Create build directory
BUILD_DIR="$PROJECT_ROOT/build"
mkdir -p "$BUILD_DIR"

# Build classpath with all JAR files in lib directory
CLASSPATH=""
for jar in "$PROJECT_ROOT"/lib/*.jar; do
    if [ -f "$jar" ]; then
        if [ -z "$CLASSPATH" ]; then
            CLASSPATH="$jar"
        else
            CLASSPATH="$CLASSPATH:$jar"
        fi
    fi
done
CLASSPATH="$CLASSPATH:$BUILD_DIR"

echo "Classpath: $CLASSPATH"

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

# Compile with Kubernetes client support
javac -cp "$CLASSPATH" -d "$BUILD_DIR" -Xlint:unchecked $JAVA_FILES

echo "Copying resources (non-Java files) into build..."
# Copy all non-Java files from src into build, preserving paths
find "$SRC_DIR" -type f ! -name "*.java" | while IFS= read -r file; do
    rel_path="${file#$SRC_DIR/}"
    dest_dir="$BUILD_DIR/$(dirname "$rel_path")"
    mkdir -p "$dest_dir"
    cp "$file" "$dest_dir/"
done

if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    echo "Build output: $BUILD_DIR"
    
    # Create JAR file
    echo "Creating JAR file..."
    cd "$BUILD_DIR"
    jar cf "$PROJECT_ROOT/lib/builder-tester.jar" com/
    cd - > /dev/null
    
    echo "JAR created: $PROJECT_ROOT/lib/builder-tester.jar"
    echo ""
    echo "Note: If Kubernetes features are needed, ensure K8s client libraries are downloaded:"
    echo "  Run: $SCRIPT_DIR/download-k8s-libs.sh"
else
    echo "Compilation failed!"
    exit 1
fi
