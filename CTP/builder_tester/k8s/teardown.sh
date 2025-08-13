#!/bin/bash

# Teardown script for Kubernetes deployment

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
K8S_DIR="$SCRIPT_DIR"

echo "CUBRID Builder-Tester Kubernetes Teardown"
echo "=========================================="

read -p "This will delete all resources in cubrid-testing namespace. Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
fi

echo "Deleting deployments..."
kubectl delete -f "$K8S_DIR/builder-deployment.yaml" --ignore-not-found=true
kubectl delete -f "$K8S_DIR/tester-deployment.yaml" --ignore-not-found=true

echo "Deleting services..."
kubectl delete -f "$K8S_DIR/services.yaml" --ignore-not-found=true

echo "Deleting ConfigMaps..."
kubectl delete configmap builder-config tester-config -n cubrid-testing --ignore-not-found=true

echo "Deleting PVCs..."
kubectl delete -f "$K8S_DIR/pvcs.yaml" --ignore-not-found=true

echo "Deleting RBAC..."
kubectl delete -f "$K8S_DIR/rbac.yaml" --ignore-not-found=true

echo "Deleting secrets..."
kubectl delete secret github-token -n cubrid-testing --ignore-not-found=true

echo "Deleting any remaining jobs..."
kubectl delete jobs --all -n cubrid-testing --ignore-not-found=true

echo "Deleting namespace..."
kubectl delete namespace cubrid-testing --ignore-not-found=true

echo "Teardown complete!"
