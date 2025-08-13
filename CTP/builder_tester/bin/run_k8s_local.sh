#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"
K8S_DIR="$PROJECT_ROOT/k8s"
KUBECONFIG_PATH="$K8S_DIR/kubeconfig-kind"
KUBECTL_BIN="$SCRIPT_DIR/kubectl"

export KUBECONFIG="$KUBECONFIG_PATH"

if [ ! -x "$KUBECTL_BIN" ]; then
  echo "kubectl wrapper not found, run setup-kind.sh first" >&2
  exit 1
fi

echo "Creating namespace and resources in kind cluster..."
"$KUBECTL_BIN" apply -f "$PROJECT_ROOT/k8s/namespace.yaml"
"$KUBECTL_BIN" apply -f "$PROJECT_ROOT/k8s/rbac.yaml"

# Create simple RWX storage workaround using hostPath (kind) for demo
cat <<EOF | "$KUBECTL_BIN" apply -f -
apiVersion: v1
kind: PersistentVolume
metadata:
  name: pv-cubrid-build
  labels:
    type: local
spec:
  capacity:
    storage: 20Gi
  accessModes:
    - ReadWriteMany
  hostPath:
    path: /tmp/kind-pv/build
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: pv-cubrid-test
  labels:
    type: local
spec:
  capacity:
    storage: 20Gi
  accessModes:
    - ReadWriteMany
  hostPath:
    path: /tmp/kind-pv/test
EOF

mkdir -p /tmp/kind-pv/build /tmp/kind-pv/test

"$KUBECTL_BIN" apply -f "$PROJECT_ROOT/k8s/pvcs.yaml"
"$KUBECTL_BIN" wait --for=condition=Bound pvc/cubrid-build-pvc -n cubrid-testing --timeout=60s || true
"$KUBECTL_BIN" wait --for=condition=Bound pvc/cubrid-test-pvc -n cubrid-testing --timeout=60s || true

# Create configs from local files
"$KUBECTL_BIN" create configmap builder-config \
  --from-file=builder.conf="$PROJECT_ROOT/conf/builder.conf" \
  -n cubrid-testing --dry-run=client -o yaml | "$KUBECTL_BIN" apply -f -

"$KUBECTL_BIN" create configmap tester-config \
  --from-file=tester.conf="$PROJECT_ROOT/conf/tester.conf" \
  -n cubrid-testing --dry-run=client -o yaml | "$KUBECTL_BIN" apply -f -

# Create dummy github token secret if not present
if ! "$KUBECTL_BIN" get secret github-token -n cubrid-testing >/dev/null 2>&1; then
  "$KUBECTL_BIN" create secret generic github-token --from-literal=token="dummy" -n cubrid-testing
fi

"$KUBECTL_BIN" apply -f "$PROJECT_ROOT/k8s/services.yaml"
"$KUBECTL_BIN" apply -f "$PROJECT_ROOT/k8s/builder-deployment.yaml"
"$KUBECTL_BIN" apply -f "$PROJECT_ROOT/k8s/tester-deployment.yaml"

"$KUBECTL_BIN" rollout status deployment/cubrid-builder -n cubrid-testing --timeout=120s || true
"$KUBECTL_BIN" rollout status deployment/cubrid-tester -n cubrid-testing --timeout=120s || true

echo "Local K8s deployment completed"

