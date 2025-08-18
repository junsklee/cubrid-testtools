#!/bin/bash

# Ccache management script for Builder-Tester System

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
CONFIG_FILE="$PROJECT_ROOT/conf/builder.conf"

# Read configuration
if [ -f "$CONFIG_FILE" ]; then
    CCACHE_ENABLED=$(grep "^ccache_enabled=" "$CONFIG_FILE" | cut -d'=' -f2)
    CCACHE_DIR=$(grep "^ccache_dir=" "$CONFIG_FILE" | cut -d'=' -f2 | sed "s|~|$HOME|g")
    CCACHE_MAX_SIZE=$(grep "^ccache_max_size=" "$CONFIG_FILE" | cut -d'=' -f2)
else
    echo "Warning: Configuration file not found at $CONFIG_FILE"
    CCACHE_ENABLED="true"
    CCACHE_DIR="$HOME/ccache"
    CCACHE_MAX_SIZE="5G"
fi

usage() {
    echo "Usage: $0 {install|setup|status|clear|stats}"
    echo
    echo "Commands:"
    echo "  install  - Install ccache on the system"
    echo "  setup    - Set up ccache directory and configuration"
    echo "  status   - Show ccache status and statistics"
    echo "  clear    - Clear ccache contents"
    echo "  stats    - Show detailed ccache statistics"
    echo
    echo "Current configuration:"
    echo "  Enabled: $CCACHE_ENABLED"
    echo "  Directory: $CCACHE_DIR"
    echo "  Max Size: $CCACHE_MAX_SIZE"
    exit 1
}

install_ccache() {
    echo "Checking for ccache installation..."
    
    if command -v ccache &> /dev/null; then
        echo "ccache is already installed:"
        ccache --version
        return 0
    fi
    
    echo "ccache is not installed. Installing..."
    
    # Detect OS and install ccache
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        if command -v apt-get &> /dev/null; then
            echo "Installing ccache using apt-get..."
            sudo apt-get update
            sudo apt-get install -y ccache
        elif command -v yum &> /dev/null; then
            echo "Installing ccache using yum..."
            sudo yum install -y ccache
        elif command -v dnf &> /dev/null; then
            echo "Installing ccache using dnf..."
            sudo dnf install -y ccache
        else
            echo "Error: Unable to detect package manager. Please install ccache manually."
            exit 1
        fi
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        if command -v brew &> /dev/null; then
            echo "Installing ccache using Homebrew..."
            brew install ccache
        else
            echo "Error: Homebrew not found. Please install Homebrew or install ccache manually."
            exit 1
        fi
    else
        echo "Error: Unsupported OS. Please install ccache manually."
        exit 1
    fi
    
    if command -v ccache &> /dev/null; then
        echo "ccache installed successfully:"
        ccache --version
    else
        echo "Error: Failed to install ccache"
        exit 1
    fi
}

setup_ccache() {
    echo "Setting up ccache..."
    
    if [ "$CCACHE_ENABLED" != "true" ]; then
        echo "Warning: ccache is disabled in configuration. Enable it by setting ccache_enabled=true in $CONFIG_FILE"
    fi
    
    # Create ccache directory
    if [ ! -d "$CCACHE_DIR" ]; then
        echo "Creating ccache directory: $CCACHE_DIR"
        mkdir -p "$CCACHE_DIR"
    else
        echo "Ccache directory already exists: $CCACHE_DIR"
    fi
    
    # Check if ccache is installed
    if ! command -v ccache &> /dev/null; then
        echo "Warning: ccache is not installed. Run '$0 install' to install it."
        return 1
    fi
    
    # Configure ccache
    echo "Configuring ccache..."
    export CCACHE_DIR="$CCACHE_DIR"
    ccache --max-size="$CCACHE_MAX_SIZE"
    
    echo "Ccache setup complete!"
    echo
    show_status
}

show_status() {
    echo "=== Ccache Status ==="
    
    if ! command -v ccache &> /dev/null; then
        echo "ccache is not installed"
        return 1
    fi
    
    export CCACHE_DIR="$CCACHE_DIR"
    
    echo "Configuration:"
    echo "  Directory: $CCACHE_DIR"
    echo "  Max size: $(ccache --show-config | grep max_size | awk '{print $3}')"
    echo
    
    echo "Statistics:"
    ccache -s | grep -E "cache hit|cache miss|cache size|max cache size" || ccache -s
}

clear_ccache() {
    echo "Clearing ccache..."
    
    if ! command -v ccache &> /dev/null; then
        echo "Error: ccache is not installed"
        return 1
    fi
    
    export CCACHE_DIR="$CCACHE_DIR"
    ccache -C
    echo "Ccache cleared successfully"
}

show_stats() {
    if ! command -v ccache &> /dev/null; then
        echo "Error: ccache is not installed"
        return 1
    fi
    
    export CCACHE_DIR="$CCACHE_DIR"
    echo "=== Detailed Ccache Statistics ==="
    ccache -s
}

# Main script
case "$1" in
    install)
        install_ccache
        ;;
    setup)
        setup_ccache
        ;;
    status)
        show_status
        ;;
    clear)
        clear_ccache
        ;;
    stats)
        show_stats
        ;;
    *)
        usage
        ;;
esac
