#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
BIN_DIR="$SCRIPT_DIR"
K8S_DIR="$PROJECT_ROOT/k8s"
KUBECONFIG_PATH="$K8S_DIR/kubeconfig-kind"

CLUSTER_NAME="ctp"
KIND_BIN="$BIN_DIR/kind"
KUBECTL_BIN="$BIN_DIR/kubectl"

echo "Installing local Kubernetes cluster with kind (Kubernetes-in-Docker)..."

if ! command -v docker >/dev/null 2>&1; then
  echo "Error: docker is required but not found" >&2
  exit 1
fi

# Download kind
if [ ! -x "$KIND_BIN" ]; then
  echo "Downloading kind..."
  curl -sL -o "$KIND_BIN" "https://kind.sigs.k8s.io/dl/v0.22.0/kind-linux-amd64"
  chmod +x "$KIND_BIN"
fi

# Download kubectl
if [ ! -x "$KUBECTL_BIN" ]; then
  echo "Downloading kubectl..."
  KUBECTL_VERSION="v1.29.4"
  curl -sL -o "$KUBECTL_BIN" "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl"
  chmod +x "$KUBECTL_BIN"
fi

# Create kind cluster if not exists
if ! "$KIND_BIN" get clusters | grep -q "^${CLUSTER_NAME}$"; then
  echo "Creating kind cluster '${CLUSTER_NAME}'..."
  "$KIND_BIN" create cluster --name "$CLUSTER_NAME"
else
  echo "Kind cluster '${CLUSTER_NAME}' already exists"
fi

mkdir -p "$K8S_DIR"
echo "Writing kubeconfig to $KUBECONFIG_PATH"
"$KIND_BIN" get kubeconfig --name "$CLUSTER_NAME" > "$KUBECONFIG_PATH"

echo "Cluster is ready. Use: KUBECONFIG=$KUBECONFIG_PATH $KUBECTL_BIN get nodes"
echo "Done."

