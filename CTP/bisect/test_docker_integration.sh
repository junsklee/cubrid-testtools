#!/bin/bash
#
# Test script for Docker integration
#

echo "=== Docker Integration Test ==="
echo

# Check if Docker is available
if command -v docker &> /dev/null; then
    echo "✓ Docker is installed"
    docker version --format 'Docker version: {{.Server.Version}}'
else
    echo "✗ Docker is not installed"
    echo "  Please install Docker to use Docker-based builds"
    exit 1
fi

echo

# Check if Docker daemon is running
if docker ps &> /dev/null; then
    echo "✓ Docker daemon is running"
else
    echo "✗ Docker daemon is not running"
    echo "  Please start Docker daemon"
    exit 1
fi

echo

# Check for cubridci repository
CUBRIDCI_DIR="$HOME/cubridci"
if [ -d "$CUBRIDCI_DIR" ]; then
    echo "✓ cubridci repository exists at $CUBRIDCI_DIR"
    cd "$CUBRIDCI_DIR"
    CURRENT_BRANCH=$(git branch --show-current)
    echo "  Current branch: $CURRENT_BRANCH"
else
    echo "✗ cubridci repository not found"
    echo "  Run ./script/setup_docker.sh to set up Docker environment"
fi

echo

# Check for Docker images
echo "Checking for Docker images..."
if docker images | grep -q "cubrid-bisect-builder"; then
    echo "✓ cubrid-bisect-builder image exists"
else
    echo "✗ cubrid-bisect-builder image not found"
    echo "  Run ./script/setup_docker.sh to build images"
fi

if docker images | grep -q "cubrid-bisect-tester"; then
    echo "✓ cubrid-bisect-tester image exists"
else
    echo "✗ cubrid-bisect-tester image not found"
    echo "  Run ./script/setup_docker.sh to build images"
fi

echo
echo "=== Configuration Check ==="
echo

# Check producer configuration
if [ -f "conf/bisect_producer.conf" ]; then
    USE_DOCKER=$(grep "^use_docker=" conf/bisect_producer.conf | cut -d'=' -f2)
    if [ "$USE_DOCKER" = "true" ]; then
        echo "✓ Producer configured to use Docker (use_docker=true)"
    else
        echo "✗ Producer not configured to use Docker (use_docker=$USE_DOCKER)"
        echo "  Edit conf/bisect_producer.conf to enable Docker"
    fi
else
    echo "✗ Producer configuration file not found"
fi

# Check consumer configuration
if [ -f "conf/bisect_consumer.conf" ]; then
    USE_DOCKER=$(grep "^use_docker=" conf/bisect_consumer.conf 2>/dev/null | cut -d'=' -f2)
    if [ -z "$USE_DOCKER" ]; then
        echo "  Consumer Docker configuration not set (optional)"
    elif [ "$USE_DOCKER" = "true" ]; then
        echo "  Consumer configured to use Docker (use_docker=true)"
    else
        echo "  Consumer configured without Docker (use_docker=false)"
    fi
else
    echo "✗ Consumer configuration file not found"
fi

echo
echo "=== Test Complete ==="
