# Kubernetes Deployment Guide

This guide explains how to deploy the CUBRID Builder-Tester system on Kubernetes for workload management and horizontal scaling.

## Prerequisites

1. **Kubernetes Cluster**: Access to a Kubernetes cluster (1.19+)
2. **kubectl**: Configured to access your cluster
3. **Storage**: A StorageClass that supports ReadWriteMany (e.g., NFS, GlusterFS, or cloud storage)
4. **GitHub Token**: For accessing private repositories

## Quick Start

1. **Set GitHub Token**:
   ```bash
   export GITHUB_TOKEN=your-github-token
   ```

2. **Run Setup**:
   ```bash
   cd k8s
   chmod +x setup.sh
   ./setup.sh
   ```

## Architecture

The Kubernetes deployment consists of:

- **Builder Deployment**: Single instance that receives build requests
- **Tester Deployment**: Multiple instances (scalable) for parallel test execution
- **Kubernetes Jobs**: Each build/test creates a Job that runs in a pod
- **Persistent Volumes**: Shared storage for builds, test cases, and logs
- **Services**: LoadBalancer for Builder, ClusterIP for Testers

## Configuration

### Enable Kubernetes Mode

Edit `conf/builder.conf` and `conf/tester.conf`:

```properties
# Enable Kubernetes
kubernetes.enabled=true

# Configure namespace
kubernetes.namespace=cubrid-testing

# Resource limits
kubernetes.build.cpu.request=2
kubernetes.build.cpu.limit=4
kubernetes.build.memory.request=4Gi
kubernetes.build.memory.limit=8Gi
```

### Node Selection

To run workloads on specific nodes:

1. **Label your nodes**:
   ```bash
   kubectl label nodes node1 cubrid-testing=enabled
   kubectl label nodes node2 workload=build
   kubectl label nodes node3 workload=test
   ```

2. **Configure node selectors** in conf files:
   ```properties
   kubernetes.nodeSelector.cubrid-testing=enabled
   kubernetes.build.nodeSelector.workload=build
   kubernetes.test.nodeSelector.workload=test
   ```

## Scaling

### Horizontal Scaling

Scale tester instances across nodes:

```bash
# Scale to 10 tester instances
kubectl scale deployment/cubrid-tester --replicas=10 -n cubrid-testing

# Auto-scale based on CPU
kubectl autoscale deployment/cubrid-tester \
  --min=2 --max=20 --cpu-percent=70 \
  -n cubrid-testing
```

### Multi-Node Distribution

The system automatically distributes workload across nodes using:

- **Pod Anti-Affinity**: Spreads tester pods across different nodes
- **Load Balancing**: Round-robin or least-loaded node selection
- **Resource Limits**: Prevents overloading individual nodes

### Adding New Nodes

To add nodes to the cluster for scaling:

1. **Join node to cluster** (on new node):
   ```bash
   kubeadm join <master-ip>:6443 --token <token>
   ```

2. **Label the node**:
   ```bash
   kubectl label nodes new-node cubrid-testing=enabled
   ```

3. **Workload automatically distributes** to the new node

## Monitoring

### Check Status

```bash
# View all resources
kubectl get all -n cubrid-testing

# View running jobs
kubectl get jobs -n cubrid-testing

# View pods distribution across nodes
kubectl get pods -n cubrid-testing -o wide

# Check builder logs
kubectl logs -f deployment/cubrid-builder -n cubrid-testing

# Check specific job logs
kubectl logs job/build-abc123 -n cubrid-testing
```

### Resource Usage

```bash
# Node resource usage
kubectl top nodes

# Pod resource usage
kubectl top pods -n cubrid-testing
```

## Storage Configuration

### Using NFS

1. **Set up NFS server**
2. **Create StorageClass**:
   ```yaml
   apiVersion: storage.k8s.io/v1
   kind: StorageClass
   metadata:
     name: nfs-storage
   provisioner: example.com/nfs
   parameters:
     server: nfs-server.example.com
     path: /shared
   ```

3. **Update PVCs** to use the StorageClass

### Using Cloud Storage

For AWS EFS, Azure Files, or GCP Filestore, follow cloud provider documentation.

## Troubleshooting

### Jobs Stuck in Pending

Check for:
- Insufficient resources: `kubectl describe pod <pod-name>`
- Node selector mismatch: Verify node labels
- PVC not bound: Check storage provisioner

### Cannot Access Builder Service

- Check LoadBalancer status: `kubectl get svc -n cubrid-testing`
- Use NodePort if LoadBalancer unavailable
- Check firewall rules

### Performance Issues

- Scale up replicas
- Increase resource limits
- Check node capacity
- Enable anti-affinity for better distribution

## Cleanup

To remove all resources:

```bash
cd k8s
./teardown.sh
```

## Advanced Configuration

### Custom Docker Registry

Use private registry for images:

```properties
kubernetes.build.image=myregistry.com/cubrid:build
kubernetes.imagePullSecret=my-registry-secret
```

### Network Policies

Restrict network traffic between pods:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cubrid-network-policy
  namespace: cubrid-testing
spec:
  podSelector:
    matchLabels:
      app: cubrid-testing
  policyTypes:
  - Ingress
  - Egress
```

### Resource Quotas

Limit namespace resources:

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: cubrid-quota
  namespace: cubrid-testing
spec:
  hard:
    requests.cpu: "100"
    requests.memory: 200Gi
    persistentvolumeclaims: "10"
```

## Integration with CI/CD

Example Jenkins pipeline:

```groovy
pipeline {
    agent any
    stages {
        stage('Build and Test') {
            steps {
                script {
                    def response = httpRequest(
                        url: 'http://cubrid-builder:8089/build',
                        httpMode: 'POST',
                        contentType: 'APPLICATION_JSON',
                        requestBody: '''{
                            "commits": ["${GIT_COMMIT}"],
                            "tests": ["all"],
                            "callbackUrl": "${JENKINS_URL}/callback"
                        }'''
                    )
                }
            }
        }
    }
}
```
