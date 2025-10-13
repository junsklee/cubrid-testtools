#!/bin/bash

# Ccache management script for Builder-Tester System

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
CONFIG_FILE="$PROJECT_ROOT/conf/builder.conf"

# Read configuration
if [ -f "$CONFIG_FILE" ]; then
    CCACHE_ENABLED=$(grep "^ccache_enabled=" "$CONFIG_FILE" | cut -d'=' -f2)
    CCACHE_DIR=$(grep "^ccache_dir=" "$CONFIG_FILE" | cut -d'=' -f2 | sed "s|~|$HOME|g" | sed "s|\$HOME|$HOME|g")
    CCACHE_MAX_SIZE=$(grep "^ccache_max_size=" "$CONFIG_FILE" | cut -d'=' -f2)
    DOCKER_HOST_ROOT=$(grep "^docker_host_root=" "$CONFIG_FILE" | cut -d'=' -f2 | sed "s|~|$HOME|g" | sed "s|\$HOME|$HOME|g")
else
    echo "Warning: Configuration file not found at $CONFIG_FILE"
    CCACHE_ENABLED="true"
    CCACHE_DIR="$HOME/ccache"
    CCACHE_MAX_SIZE="15G"
    DOCKER_HOST_ROOT="$HOME/docker-work"
fi

# Set defaults for any missing values
[ -z "$CCACHE_ENABLED" ] && CCACHE_ENABLED="true"
[ -z "$CCACHE_DIR" ] && CCACHE_DIR="$HOME/ccache"
[ -z "$CCACHE_MAX_SIZE" ] && CCACHE_MAX_SIZE="15G"
[ -z "$DOCKER_HOST_ROOT" ] && DOCKER_HOST_ROOT="$HOME/docker-work"

# Derive Docker cache directory used by DockerBuildManager (mounted as /work/.ccache)
DOCKER_CCACHE_DIR="${DOCKER_HOST_ROOT%/}/work/.ccache"

usage() {
    echo "Usage: $0 {install|setup|status|clear|stats [host|docker|all]}"
    echo
    echo "Commands:"
    echo "  install  - Install ccache on the system"
    echo "  setup    - Set up ccache directory and configuration"
    echo "  status   - Show ccache status and statistics"
    echo "  clear    - Clear ccache contents"
    echo "  stats    - Show detailed ccache statistics (host, docker, or all)"
    echo
    echo "Current configuration:"
    echo "  Enabled: $CCACHE_ENABLED"
    echo "  Directory: $CCACHE_DIR"
    echo "  Max Size: $CCACHE_MAX_SIZE"
    echo "  Docker host root: $DOCKER_HOST_ROOT"
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
        # Prefer dnf on RHEL/Rocky/Alma/CentOS 8+
        if command -v dnf &> /dev/null; then
            # Load OS metadata
            OS_ID=""; OS_VER=""
            if [ -f /etc/os-release ]; then
                OS_ID=$(grep -E '^ID=' /etc/os-release | cut -d'=' -f2 | tr -d '"')
                OS_VER=$(grep -E '^VERSION_ID=' /etc/os-release | cut -d'=' -f2 | tr -d '"')
            fi
            echo "Detected OS: ${OS_ID:-unknown} ${OS_VER:-unknown}"
            # Ensure dnf plugins are available for config-manager
            sudo dnf install -y dnf-plugins-core || true
            # Attempt to enable common repos on EL8/EL9 families
            if [[ "${OS_VER}" == 8* ]]; then
                sudo dnf config-manager --set-enabled powertools || sudo dnf config-manager --set-enabled PowerTools || true
                sudo dnf install -y epel-release epel-next-release || true
            elif [[ "${OS_VER}" == 9* ]]; then
                sudo dnf config-manager --set-enabled crb || true
                sudo dnf install -y epel-release || true
            else
                sudo dnf install -y epel-release || true
            fi
            sudo dnf makecache || true
            echo "Installing ccache using dnf..."
            if ! sudo dnf --enablerepo=epel --setopt=install_weak_deps=False install -y ccache; then
                echo "ccache not found in enabled repos. Trying with additional repos..."
                if ! sudo dnf --enablerepo=epel,crb,powertools --setopt=install_weak_deps=False install -y ccache; then
                    echo "ccache package still not available via dnf. Falling back to source build."
                    install_ccache_from_source || {
                        echo "Error: Failed to install ccache from source."; exit 1;
                    }
                    return 0
                fi
            fi
        elif command -v apt-get &> /dev/null; then
            echo "Installing ccache using apt-get..."
            sudo apt-get update
            sudo apt-get install -y ccache
        elif command -v yum &> /dev/null; then
            echo "Installing ccache using yum..."
            if ! sudo yum install -y ccache; then
                echo "ccache not found with yum. Trying to enable EPEL and retry..."
                sudo yum install -y epel-release || true
                sudo yum makecache || true
                if ! sudo yum install -y ccache; then
                    echo "ccache package still not available via yum. Falling back to source build."
                    install_ccache_from_source || { echo "Error: Failed to install ccache from source."; exit 1; }
                    return 0
                fi
            fi
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

# Fallback: build and install ccache from source
install_ccache_from_source() {
    echo "Attempting to build ccache from source..."
    CCACHE_VERSION="4.10.2"
    TMP_DIR="/tmp/ccache-src-$$"
    mkdir -p "$TMP_DIR"
    pushd "$TMP_DIR" >/dev/null || return 1
    # Ensure build deps
    if command -v dnf &> /dev/null; then
        sudo dnf install -y gcc gcc-c++ make cmake tar xz zlib zlib-devel || true
    elif command -v yum &> /dev/null; then
        sudo yum install -y gcc gcc-c++ make cmake tar xz zlib zlib-devel || true
    elif command -v apt-get &> /dev/null; then
        sudo apt-get update || true
        sudo apt-get install -y build-essential cmake tar xz-utils zlib1g-dev || true
    fi
    echo "Downloading ccache v$CCACHE_VERSION sources..."
    curl -fsSL -o ccache.tar.xz "https://github.com/ccache/ccache/releases/download/v$CCACHE_VERSION/ccache-$CCACHE_VERSION.tar.xz" || return 1
    tar xf ccache.tar.xz || return 1
    cd "ccache-$CCACHE_VERSION" || return 1
    cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DENABLE_TESTING=OFF || return 1
    cmake --build build --parallel || return 1
    echo "Installing ccache to /usr/local (requires sudo)..."
    sudo cmake --install build || return 1
    popd >/dev/null || true
    rm -rf "$TMP_DIR"
    command -v ccache && return 0 || return 1
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

    local which="${1:-auto}"
    echo "=== Detailed Ccache Statistics ==="

    # helper: print stats for a given directory with a label
    _print_stats_for_dir() {
        local dir="$1"; local label="$2"
        if [ -z "$dir" ]; then return; fi
        if [ ! -d "$dir" ]; then
            echo "[$label] Cache directory not found: $dir"
            return
        fi
        echo "[$label] CCACHE_DIR=$dir"
        CCACHE_DIR="$dir" ccache -s | sed 's/^/  /'
        echo
    }

    case "$which" in
        host)
            _print_stats_for_dir "$CCACHE_DIR" "host"
            ;;
        docker)
            _print_stats_for_dir "$DOCKER_CCACHE_DIR" "docker"
            ;;
        all)
            _print_stats_for_dir "$CCACHE_DIR" "host"
            _print_stats_for_dir "$DOCKER_CCACHE_DIR" "docker"
            ;;
        auto|*)
            # If docker cache exists, show both; else show host
            if [ -d "$DOCKER_CCACHE_DIR" ]; then
                _print_stats_for_dir "$CCACHE_DIR" "host"
                _print_stats_for_dir "$DOCKER_CCACHE_DIR" "docker"
            else
                _print_stats_for_dir "$CCACHE_DIR" "host"
            fi
            ;;
    esac
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
        show_stats "$2"
        ;;
    *)
        usage
        ;;
esac
