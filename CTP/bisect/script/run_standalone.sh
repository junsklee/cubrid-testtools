#!/bin/bash

# Standalone Bisect Service Launcher
# This script builds and runs the bisect service in standalone mode

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BISECT_HOME="$(dirname "$SCRIPT_DIR")"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}CUBRID Bisect - Standalone Mode${NC}"
echo -e "${GREEN}========================================${NC}"

# Check if configuration file exists
CONFIG_FILE="$BISECT_HOME/conf/bisect_standalone.conf"
if [ ! -f "$CONFIG_FILE" ]; then
    echo -e "${YELLOW}Configuration file not found: $CONFIG_FILE${NC}"
    echo "Creating default configuration..."
    
    # Create default configuration
    cat > "$CONFIG_FILE" << EOF
# Standalone Bisect Configuration - Auto-generated
listen_port=8091
cubrid_src_dir=~/cubrid-src
shell_tc_dir=~/cubrid-testcases-private-ex
build_arg=-g ninja -m debug build
build_dir=build_x86_64_debug
work_dir=/tmp/bisect_standalone_work
max_concurrent_bisects=2
standalone_mode=true
use_docker=true
docker_standalone_image=cubrid-bisect-standalone:latest
EOF
    echo -e "${GREEN}Created default configuration at: $CONFIG_FILE${NC}"
fi

# Function to check Docker
check_docker() {
    if ! command -v docker &> /dev/null; then
        echo -e "${RED}Docker is not installed or not in PATH${NC}"
        echo "Standalone mode requires Docker. Please install Docker first."
        exit 1
    fi
    
    if ! docker ps &> /dev/null; then
        echo -e "${RED}Docker daemon is not running or you don't have permissions${NC}"
        echo "Please start Docker daemon or add your user to docker group"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Docker is available${NC}"
}

# Function to check and clone cubridci repository
check_cubridci() {
    CUBRIDCI_DIR="$HOME/cubridci"
    
    if [ ! -d "$CUBRIDCI_DIR" ]; then
        echo -e "${YELLOW}CubridCI repository not found${NC}"
        echo "Cloning cubridci repository..."
        git clone https://github.com/CUBRID/cubridci.git "$CUBRIDCI_DIR"
        if [ $? -ne 0 ]; then
            echo -e "${RED}Failed to clone cubridci repository${NC}"
            exit 1
        fi
    fi
    
    echo -e "${GREEN}✓ CubridCI repository available at: $CUBRIDCI_DIR${NC}"
}

# Function to build the Java application
build_application() {
    echo -e "${YELLOW}Building Java application...${NC}"
    
    cd "$BISECT_HOME"
    
    # Compile the application
    if [ -f "build.sh" ]; then
        ./build.sh
    else
        # Manual compilation if build.sh doesn't exist
        mkdir -p build
        javac -cp "lib/*" -d build src/com/navercorp/cubridqa/bisect/*.java
        
        # Create JAR
        cd build
        jar cf bisect-standalone.jar com/
        cd ..
    fi
    
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Application built successfully${NC}"
    else
        echo -e "${RED}Failed to build application${NC}"
        exit 1
    fi
}

# Function to run the standalone service
run_service() {
    echo -e "${YELLOW}Starting Standalone Bisect Service...${NC}"
    
    cd "$BISECT_HOME"
    
    # Set classpath
    CLASSPATH="build/bisect-standalone.jar:lib/*"
    
    # Run the service
    java -cp "$CLASSPATH" \
         -Djava.util.logging.config.file=conf/logging.properties \
         com.navercorp.cubridqa.bisect.StandaloneBisectService \
         "$CONFIG_FILE"
}

# Main execution
main() {
    echo "Checking prerequisites..."
    
    # Check Docker
    check_docker
    
    # Check cubridci repository
    check_cubridci
    
    # Build application
    build_application
    
    # Start service
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Starting service...${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    
    run_service
}

# Handle script arguments
case "${1:-}" in
    start)
        main
        ;;
    build)
        build_application
        ;;
    check)
        check_docker
        check_cubridci
        echo -e "${GREEN}All checks passed!${NC}"
        ;;
    *)
        echo "Usage: $0 {start|build|check}"
        echo ""
        echo "  start  - Build and start the standalone service"
        echo "  build  - Build the Java application only"
        echo "  check  - Check prerequisites (Docker, cubridci repo)"
        echo ""
        echo "Default action: start"
        main
        ;;
esac
