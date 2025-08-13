# Configuration

Both services read Properties files with environment expansion (supports `~/` and `$VARS`).

## Common
- `cubrid_src_dir` (path): CUBRID source checkout (Builder clones if missing)
- `shell_tc_dir` (path): Shell testcases root directory
- `tester_port` (int): Tester HTTP port (default 8090)

## Builder (`conf/builder.conf`)
- `listen_port` (int): Builder HTTP port (default 8089)
- `max_concurrent_builds` (int): Max parallel builds (default 4)
- `max_concurrent_tests` (int): Max parallel tests per built artifact (default 4)
- `build_arg` (string): Args passed to `./build.sh` (default `-g ninja -m debug build`)
- `build_dir` (string): Build output directory name (default `build_x86_64_debug`)
- `work_dir` (path): Builder work dir (default `/tmp/builder_work`)
- `use_docker` (bool): Use Docker for builds (default true)
- `use_prebuilt_docker_images` (bool): Pull prebuilt images (default true)
- `docker_build_image` (string): Build image (default `cubridci/cubridci:develop`)
- `docker_test_image` (string): Test image (default `cubridci/cubridci:test_shell`)
- `keep_failed_containers` (bool): Keep failed test containers (default true)
- `build_timeout_minutes` (int): Per-build timeout (default 180)
- `build_cache_size` (int): Max cached builds (default 20)
- `docker_host_root` (path): Host dir for `/work` and Gradle cache binds (default `~/docker-work`)
### Log management
- `max_request_logs` (int): Keep last N request directories (default 5)
- `max_tar_files` (int): Keep last N tar archives in work dir (default 10)
- `enable_request_grouping` (bool): Group logs by request ID (default true)

### Kubernetes configuration
- `kubernetes.enabled` (bool): Enable Kubernetes mode (default false)
- `kubernetes.namespace` (string): K8s namespace (default `cubrid-testing`)
- `kubernetes.context` (string): K8s context to use (optional)
- `kubernetes.kubeconfig` (path): Kubeconfig file (default `~/.kube/config`)
- `kubernetes.build.cpu.request` (string): CPU request for builds (default `2`)
- `kubernetes.build.cpu.limit` (string): CPU limit for builds (default `4`)
- `kubernetes.build.memory.request` (string): Memory request for builds (default `4Gi`)
- `kubernetes.build.memory.limit` (string): Memory limit for builds (default `8Gi`)
- `kubernetes.test.cpu.request` (string): CPU request for tests (default `1`)
- `kubernetes.test.cpu.limit` (string): CPU limit for tests (default `2`)
- `kubernetes.test.memory.request` (string): Memory request for tests (default `2Gi`)
- `kubernetes.test.memory.limit` (string): Memory limit for tests (default `4Gi`)
- `kubernetes.job.backoffLimit` (int): Job retry limit (default 3)
- `kubernetes.job.ttlSecondsAfterFinished` (int): Cleanup delay (default 3600)
- `kubernetes.job.activeDeadlineSeconds` (int): Build job timeout (default 7200)
- `kubernetes.test.job.activeDeadlineSeconds` (int): Test job timeout (default 1800)
- `kubernetes.nodeSelector.*` (string): Node selector labels
- `kubernetes.build.nodeSelector.*` (string): Build-specific node selectors
- `kubernetes.test.nodeSelector.*` (string): Test-specific node selectors
- `kubernetes.loadBalancing` (string): Strategy - RoundRobin/Random/LeastConnection (default `RoundRobin`)
- `kubernetes.preferLocalNode` (bool): Prefer local node execution (default true)
- `kubernetes.enableAntiAffinity` (bool): Enable pod anti-affinity (default true)
- `kubernetes.maxJobsPerNode` (int): Max concurrent jobs per node (default 4)
- `kubernetes.build.pvc` (string): Build PVC name (default `cubrid-build-pvc`)
- `kubernetes.test.pvc` (string): Test PVC name (default `cubrid-test-pvc`)
- `kubernetes.storageClass` (string): Storage class name (default `standard`)
- `kubernetes.build.image` (string): Build image (default `cubridci/cubridci:develop`)
- `kubernetes.test.image` (string): Test image (default `cubridci/cubridci:test_shell`)
- `kubernetes.imagePullPolicy` (string): Pull policy (default `IfNotPresent`)
- `kubernetes.imagePullSecret` (string): Secret for private registries (optional)

## Tester (`conf/tester.conf`)
- `tester_port` (int): Tester HTTP port (default 8090)
- `cubrid_src_dir` (path): Unused for Docker mode; kept for parity
- `shell_tc_dir` (path): Shell testcases root directory
- `build_dir` (string): Optional build subdir name; not required for packaged tarballs
- `work_dir` (path): Tester work dir (default `/tmp/tester_work`)
- `use_docker_tester` (bool): Use Docker for tests (default true)
- `use_prebuilt_docker_images` (bool): Pull prebuilt images (default true)
- `docker_test_image` (string): Test image (default `cubridci/cubridci:test_shell`)
- `keep_failed_containers` (bool): Keep failed test containers (default true)
### Log management
- `max_request_logs` (int): Keep last N request directories (default 5)
- `enable_request_grouping` (bool): Group logs by request ID (default true)

## Environment variables
- `GITHUB_TOKEN`: required for Docker builder/tester images to fetch dependencies/private repos
- `CTP_HOME`: if set, used inside direct execution; in Docker set to `/home/cubrid-testtools/CTP`