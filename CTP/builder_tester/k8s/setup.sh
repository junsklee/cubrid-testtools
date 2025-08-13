#!/bin/bash

# Setup script for Kubernetes deployment of CUBRID Builder-Tester

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
K8S_DIR="$SCRIPT_DIR"

echo "CUBRID Builder-Tester Kubernetes Setup"
echo "======================================="

# Check kubectl
if ! command -v kubectl &> /dev/null; then
    echo "Error: kubectl not found. Please install kubectl first."
    exit 1
fi

# Check cluster connection
echo "Checking Kubernetes cluster connection..."
if ! kubectl cluster-info &> /dev/null; then
    echo "Error: Cannot connect to Kubernetes cluster. Please check your kubeconfig."
    exit 1
fi

echo "Connected to cluster: $(kubectl config current-context)"

# Create namespace
echo ""
echo "Creating namespace..."
kubectl apply -f "$K8S_DIR/namespace.yaml"

# Create secret for GitHub token
echo ""
echo "Setting up GitHub token secret..."
if [ -z "$GITHUB_TOKEN" ]; then
    echo "Warning: GITHUB_TOKEN environment variable not set."
    echo "Please create the secret manually:"
    echo "  kubectl create secret generic github-token --from-literal=token=YOUR_TOKEN -n cubrid-testing"
else
    kubectl create secret generic github-token \
        --from-literal=token="$GITHUB_TOKEN" \
        -n cubrid-testing \
        --dry-run=client -o yaml | kubectl apply -f -
    echo "GitHub token secret created/updated"
fi

# Apply RBAC
echo ""
echo "Applying RBAC configuration..."
kubectl apply -f "$K8S_DIR/rbac.yaml"

# Create PVCs
echo ""
echo "Creating Persistent Volume Claims..."
kubectl apply -f "$K8S_DIR/pvcs.yaml"

# Wait for PVCs to be bound (optional)
echo ""
echo "Waiting for PVCs to be bound (timeout 30s)..."
kubectl wait --for=condition=Bound pvc/builder-workspace-pvc -n cubrid-testing --timeout=30s || true
kubectl wait --for=condition=Bound pvc/cubrid-build-pvc -n cubrid-testing --timeout=30s || true
kubectl wait --for=condition=Bound pvc/cubrid-test-pvc -n cubrid-testing --timeout=30s || true

# Create ConfigMaps from conf files
echo ""
echo "Creating ConfigMaps..."
kubectl create configmap builder-config \
    --from-file=builder.conf="$SCRIPT_DIR/../conf/builder.conf" \
    -n cubrid-testing \
    --dry-run=client -o yaml | kubectl apply -f -

kubectl create configmap tester-config \
    --from-file=tester.conf="$SCRIPT_DIR/../conf/tester.conf" \
    -n cubrid-testing \
    --dry-run=client -o yaml | kubectl apply -f -

# Deploy services
echo ""
echo "Creating services..."
kubectl apply -f "$K8S_DIR/services.yaml"

# Deploy Builder
echo ""
echo "Deploying Builder..."
kubectl apply -f "$K8S_DIR/builder-deployment.yaml"

# Deploy Tester
echo ""
echo "Deploying Tester..."
kubectl apply -f "$K8S_DIR/tester-deployment.yaml"

# Wait for deployments
echo ""
echo "Waiting for deployments to be ready..."
kubectl rollout status deployment/cubrid-builder -n cubrid-testing --timeout=120s
kubectl rollout status deployment/cubrid-tester -n cubrid-testing --timeout=120s

# Show status
echo ""
echo "Deployment Status:"
echo "=================="
kubectl get all -n cubrid-testing

echo ""
echo "Service Endpoints:"
echo "=================="
BUILDER_IP=$(kubectl get svc cubrid-builder -n cubrid-testing -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
if [ "$BUILDER_IP" = "pending" ] || [ -z "$BUILDER_IP" ]; then
    BUILDER_PORT=$(kubectl get svc cubrid-builder -n cubrid-testing -o jsonpath='{.spec.ports[0].nodePort}')
    echo "Builder: Use NodePort - <node-ip>:$BUILDER_PORT"
    echo "  To get node IPs: kubectl get nodes -o wide"
else
    echo "Builder: http://$BUILDER_IP:8089"
fi

echo ""
echo "To check logs:"
echo "  kubectl logs -f deployment/cubrid-builder -n cubrid-testing"
echo "  kubectl logs -f deployment/cubrid-tester -n cubrid-testing"

echo ""
echo "To scale tester instances (for horizontal scaling):"
echo "  kubectl scale deployment/cubrid-tester --replicas=10 -n cubrid-testing"

echo ""
echo "Setup complete!"
