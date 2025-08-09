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

## Environment variables
- `GITHUB_TOKEN`: required for Docker builder/tester images to fetch dependencies/private repos
- `CTP_HOME`: if set, used inside direct execution; in Docker set to `/home/cubrid-testtools/CTP`