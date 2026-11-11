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
- `build_arg` (string): Args passed to `./build.sh` (default `-g ninja -c -DENABLE_SYSTEMTAP=OFF build`; `-m` comes from the request's `buildType`: release (default), debug, coverage or profile)
- `build_dir` (string): Build output directory name (default `build_x86_64_debug`)
- `work_dir` (path): Builder work dir (default `/tmp/builder_work`)
- `use_docker` (bool): Use Docker for builds (default true)
- `use_prebuilt_docker_images` (bool): Pull prebuilt images (default true)
- `docker_build_image` (string): Build image (default `cubridci/cubridci:develop`)
- `docker_test_image` (string): Test image (default `cubridci/cubridci:test_rl8.10`)
- `keep_failed_containers` (bool): Keep failed test containers (default false)
- `build_timeout_minutes` (int): Per-build timeout (default 180)
- `build_cache_size` (int): Max cached builds (default 10)
- `docker_host_root` (path): Host dir for `/work` and Gradle cache binds (default `~/docker-work`)
- `sql_full_suite_batch` (bool, default true): the full SQL suite (`sqlFullSuite`) runs as CTP batches
  (see `docs/SQL_TESTER.md`, "How the full suite runs"); false runs every case one by one.
- `sql_batch_shards` (int, default 10), `sql_batch_per_tester` (int, default 2: shards a Docker tester runs
  at once), `sql_batch_timeout_minutes` (int, default 180), `sql_batch_rerun_max` (int, default 1000: failed cases of a build re-run one by one at most; past it the batch verdicts are reported as they are), `sql_batch_shard_mem_mb` (int, default 4096: a shard container's hard memory limit, always applied, and
  the memory a shard declares to the tester's admission control).
- `official_build_store_url` (URL): Build store that `officialBuilds` versions come from (default
  `http://192.168.1.91:8080/REPO_ROOT/store_01/`); testers download packages from it. The dashboard lists
  the store the Builder reports in `/health` (the report server's `OFFICIAL_BUILD_STORE_URL` is only its
  fallback while the Builder does not answer).
### Test execution configuration
- `run_mode` (string): How tests are executed (default `until-pass`)
  - `until-pass`: May stop early on first success after `min_runs`
  - `until-fail`: May stop early on first failure after `min_runs`
  - `fixed-runs`: Never stops early
- `min_runs` (int ≥ 1): Lower bound on attempts
- `max_runs` (int ≥ `min_runs`): Upper bound on attempts
- `time_budget_ms` (int ≥ 1, optional): Additional early-stop condition
### Ccache configuration
- `ccache_enabled` (bool): Enable compiler cache (default true)
- `ccache_dir` (path): Cache directory (default `~/ccache`)
- `ccache_max_size` (string): Maximum cache size (default `15G`)
- `ccache_compilercheck` (string): Compiler check method (default `content`)
- `ccache_hardlink` (bool): Use hard links to save space (default true)
- `deterministic_build_version` (bool, default false): with ccache, build every commit as
  `x.y.z.0000-0000000`. By default a build reports its commit's own version, as a regression build
  does (`11.5.0.1234-604c504`, the requested commit's count and hash even when cherry-picked onto a
  baseline). The version is in `version.h`, which `config.h` includes, so each new commit costs a
  preprocessor run per file (ccache preprocessed-mode hits instead of direct-mode hits); `true`
  trades a correct build string for those direct-mode hits.
- `parallel_jobs` (int): Parallel compilation jobs, 0=auto-detect (default 0)
### Log management
- `max_request_logs` (int): Keep last N request directories (default 5)
- `max_tar_files` (int): Keep last N tar archives in work dir (default 5)
- `enable_request_grouping` (bool): Group logs by request ID (default true)

## Tester (`conf/tester.conf`)
- `tester_port` (int): Tester HTTP port (default 8090)
- `cubrid_src_dir` (path): Unused for Docker mode; kept for parity
- `shell_tc_dir` (path): Shell testcases root directory
- `build_dir` (string): Optional build subdir name; not required for packaged tarballs
- `work_dir` (path): Tester work dir (default `/tmp/builder_work`, as for the Builder; `conf/tester.conf` sets `~/tmp/tester_work`)
- `use_docker_tester` (bool): Use Docker for tests (default true)
- `shell_ctp_from_repo` (bool, default true): Docker shell cases run with the CTP of `ctp_sql_repo` /
  `ctp_sql_ref` (CUBRID/cubrid-testtools `develop`, pinned per request by the Builder), as regression VMs
  keep their CTP current, instead of this host's `~/cubrid-testtools/CTP` checkout, which falls behind
  (cases written for the current `init.sh` failed with `wait_until: command not found`). When it cannot be
  provisioned, the case runs with the local checkout and a warning.
- `use_prebuilt_docker_images` (bool): Pull prebuilt images (default true)
- `docker_test_image` (string): Test image (default `cubridci/cubridci:test_rl8.10`). The Tester pulls it at
  startup and builds every per-commit image (`cubrid-test:*`) on the id the tag resolves to, recording that
  id in the image name, so a republished tag or a switch to another image rebuilds per-commit images instead
  of reusing ones made on the old base. To hold the base still, pin a digest:
  `docker_test_image=cubridci/cubridci:test_rl8.10@sha256:<digest>`. `test_rl8.10` replaces the older
  `test_shell`, which lacks `make` (cases that compile their helpers passed without running) and `valgrind`.
- `keep_failed_containers` (bool): Keep failed test containers (default false)
### Log management
- `max_request_logs` (int): Keep last N request directories (default 5)
- `enable_request_grouping` (bool): Group logs by request ID (default true)

## Environment variables
- `GITHUB_TOKEN`: required for Docker builder/tester images to fetch dependencies/private repos
- `CTP_HOME`: if set, used inside direct execution; in Docker set to `/home/cubrid-testtools/CTP`