#!/bin/bash
#
# Docker setup script for bisect tool
# This script ensures Docker images are built from cubridci repository
#

set -e

CUBRIDCI_DIR="$HOME/cubridci"

echo "Setting up Docker environment for bisect tool..."

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo "Docker is not installed. Please install Docker first."
    exit 1
fi

# Clone or update cubridci repository
if [ ! -d "$CUBRIDCI_DIR" ]; then
    echo "Cloning cubridci repository..."
    git clone https://github.com/CUBRID/cubridci.git "$CUBRIDCI_DIR"
else
    echo "Updating cubridci repository..."
    cd "$CUBRIDCI_DIR"
    git fetch origin
fi

cd "$CUBRIDCI_DIR"

# Build the builder image (from develop branch)
echo "Building CUBRID build environment image..."
git checkout develop
docker build -t cubrid-bisect-builder:latest docker/ci/

# Build the tester image (from test_shell branch)  
echo "Building CUBRID test environment image..."
git checkout test_shell
docker build -t cubrid-bisect-tester:latest docker/ci/

echo "Docker images built successfully!"
echo ""
echo "Available images:"
docker images | grep cubrid-bisect

echo ""
echo "Docker setup complete. The bisect tool can now use Docker for builds and tests."
