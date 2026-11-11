#!/bin/bash

# Compile Builder-Tester System
#
# Compiles into fresh staging locations and swaps them in only after a successful build:
# running Builder/Tester JVMs use build/ and lib/builder-tester.jar as their classpath, and
# deleting them first (as before) broke those JVMs, and left nothing to run if javac failed.
# A replaced jar keeps its old inode open in running JVMs, so they keep their classes.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

echo "Building Builder-Tester System..."
echo "Project root: $PROJECT_ROOT"

# Check for Java
if ! command -v javac &> /dev/null; then
    echo "Error: Java compiler (javac) not found. Please install JDK."
    exit 1
fi

BUILD_DIR="$PROJECT_ROOT/build"
STAGE_DIR="$PROJECT_ROOT/build.new"
JAR_PATH="$PROJECT_ROOT/lib/builder-tester.jar"
STAGE_JAR="$JAR_PATH.new"

# Fresh staging directory (force full rebuild)
rm -rf "$STAGE_DIR" "$STAGE_JAR"
mkdir -p "$STAGE_DIR"

# Remove any stray .class files from src directory (they shouldn't be there)
echo "Cleaning stray class files from source directory..."
find "$PROJECT_ROOT/src" -name "*.class" -type f -delete 2>/dev/null || true

# Set classpath
CLASSPATH="$PROJECT_ROOT/lib/json.jar:$PROJECT_ROOT/lib/jsch-0.1.55.jar:$STAGE_DIR"
# JMS/ActiveMQ API for consumer execution (consumer/ConsumerQueue.java), from the enclosing CTP
CTP_ROOT="$( cd "$PROJECT_ROOT/.." && pwd )"
CLASSPATH="$CLASSPATH:$CTP_ROOT/common/sched/lib/activemq-client-5.8.0.jar:$CTP_ROOT/common/sched/lib/geronimo-jms_1.1_spec-1.1.1.jar"

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
javac -encoding UTF-8 -cp "$CLASSPATH" -d "$STAGE_DIR" $JAVA_FILES
JAVAC_EXIT=$?

if [ $JAVAC_EXIT -ne 0 ]; then
    rm -rf "$STAGE_DIR"
    echo "Compilation failed! The previous build was left in place."
    exit 1
fi

echo "Copying resources (non-Java files) into build..."
# Copy all non-Java files from src into build, preserving paths (POSIX-compatible)
find "$SRC_DIR" -type f ! -name "*.java" | while IFS= read -r file; do
    rel_path="${file#$SRC_DIR/}"
    dest_dir="$STAGE_DIR/$(dirname "$rel_path")"
    mkdir -p "$dest_dir"
    cp "$file" "$dest_dir/"
done

# Which code this is (BuildStamp.java): the Builder warns when a tester runs other code than its own
FINGERPRINT=$(cd "$SRC_DIR" && find . -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -c1-12)
printf 'fingerprint=%s\nbuilt=%s\n' "$FINGERPRINT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    > "$STAGE_DIR/com/navercorp/cubridqa/builder/build_stamp.properties"

# Create JAR file
echo "Creating JAR file..."
(cd "$STAGE_DIR" && jar cf "$STAGE_JAR" com/) || {
    rm -rf "$STAGE_DIR" "$STAGE_JAR"
    echo "Creating the JAR failed! The previous build was left in place."
    exit 1
}

# Swap in: rename, so readers see either the old or the new version, never a partial one
rm -rf "$BUILD_DIR.old"
[ -d "$BUILD_DIR" ] && mv "$BUILD_DIR" "$BUILD_DIR.old"
mv "$STAGE_DIR" "$BUILD_DIR"
mv -f "$STAGE_JAR" "$JAR_PATH"
rm -rf "$BUILD_DIR.old"

echo "Compilation successful!"
echo "Build output: $BUILD_DIR"
echo "JAR created: $JAR_PATH"
