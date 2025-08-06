#!/bin/bash
#
# Docker build wrapper for bisect tool
# Usage: docker_build.sh <commit_hash> <output_dir> [build_args]
#

set -e

if [ $# -lt 2 ]; then
    echo "Usage: $0 <commit_hash> <output_dir> [build_args]"
    exit 1
fi

COMMIT_HASH="$1"
OUTPUT_DIR="$2"
BUILD_ARGS="${3:-"-g ninja -m debug build"}"
CUBRID_SRC="${CUBRID_SRC:-$HOME/cubrid-src}"

echo "Building CUBRID commit $COMMIT_HASH using Docker..."

# Create build script for Docker
cat > "$OUTPUT_DIR/docker_build_internal.sh" << 'EOF'
#!/bin/bash
set -e

# Copy source to working directory
cp -r /cubrid-src /tmp/cubrid-build
cd /tmp/cubrid-build

# Checkout specific commit
git checkout ${COMMIT_HASH}
git submodule update --init --recursive

# Clean previous builds
rm -rf build_x86_64_*
rm -rf cubridmanager/*

# Build CUBRID
./build.sh ${BUILD_ARGS}

# Determine build directory
BUILD_DIR=$(ls -d build_x86_64_* | head -1)

# Create package
cd $BUILD_DIR
tar czf /output/cubrid_${COMMIT_HASH:0:7}.tar.gz .

echo "Build completed successfully"
EOF

chmod +x "$OUTPUT_DIR/docker_build_internal.sh"

# Run Docker build
docker run --rm \
    -v "$CUBRID_SRC:/cubrid-src:ro" \
    -v "$OUTPUT_DIR:/output:rw" \
    -e COMMIT_HASH="$COMMIT_HASH" \
    -e BUILD_ARGS="$BUILD_ARGS" \
    cubrid-bisect-builder:latest \
    bash /output/docker_build_internal.sh

echo "Docker build completed. Package: $OUTPUT_DIR/cubrid_${COMMIT_HASH:0:7}.tar.gz"
