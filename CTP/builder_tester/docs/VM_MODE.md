# VM mode (`execution_mode=vm`)

VM mode runs shell cases on a dedicated QA VM the way QAHome regression runs them, so a
case that passes in Builder-Tester is expected to pass in VM regression and vice versa.
It trades the container mode's parallelism for fidelity: one case at a time per VM.

## Why not containers

Container runs differ from the regression VMs in ways that change case results:
cases run as root (permission checks such as `chmod 000` are bypassed, and the shell
prompt is `# ` instead of `$ `), the case tree is not under `/home/$USER`, the image
lacks packages the VMs have (EUC-KR iconv, python, valgrind, bzip2, make, ...), the
host's `core_pattern` keeps core files out of the case directory, the QA asset
directories (`~/dblink_*`, `~/cubrid-testcases`) are missing, and many cases share one
host. VM mode avoids all of these by construction: it uses the VM's own user, packages,
CTP, assets and kernel settings.

## What runs for each case

The tester installs the build once per package, then for every attempt reproduces CTP
shell's per-case steps (`CTP/shell/.../main/Test.java`) without CTP's Java dispatcher:

| Step | CTP equivalent |
|---|---|
| Install: `cubrid service stop`, replace `vm_cubrid_home`, extract, copy CCI headers/libs, snapshot `~/.CUBRID_SHELL_FM` | `run_cubrid_install` (tarball path, `cpCCIDriver`) + `DeployOneNode.backup_linux` |
| Kill the user's CUBRID/csql/broker/sleep/expect and non-CTP java processes, remove shared memory | `resetProcess` (`Constants.createLinKillScripts`, local) |
| Restore `conf/` and `databases/` from `~/.CUBRID_SHELL_FM`, clear logs, var, locale libs, core files | `resetCUBRID_linux` |
| `ulimit -c unlimited`, `TEST_BIG_SPACE`, `CUBRID_CHARSET`, empty `.result`, `TEST_SSH_HOST/PORT/USER`, `TEST_BUIILD_ID`, `sh <case>.sh` | `runTestCase_linux` + `addSshInfoScript` |
| `SKIP_CHECK_FATAL_ERROR`, `do_check_more_errors` from the VM's CTP (core files / new FATAL ERROR => NOK) | `doFinalCheck` |
| Verdict: blank `.result` or any line containing `NOK` fails | `collectGeneralResult` |

Before each attempt the case directory (the directory holding `cases/`) is restored to
the requested testcase commit with `git checkout` + `git clean`, so repeated runs start
from a fresh tree, as a regression checkout does. Custom scripts and attachments from
the request are applied after the restore.

## Setting up a VM

The VM is a regular QA regression VM (or one set up the same way):

1. A non-root user with `~/.cubrid.sh` sourced from its login profile, and the packages,
   locales and kernel settings regression uses (`core_pattern` writing `core.*` into the
   working directory, `ulimit -c unlimited`).
2. `~/CTP`: a cubrid-testtools CTP checkout (only `shell/init_path`, `bin` and
   `common/script` are used; nothing needs building).
3. `~/cubrid-testcases-private-ex`: a clone with an `upstream` (or `origin`) remote the
   tester can fetch requested commits from.
4. Any regression assets the cases expect under `$HOME` (`~/dblink_mysql`, `~/dblink_mariadb`,
   `~/dblink_oracle`, `~/cubrid-testcases` for the cases that call `ctp.sh`, ...).
5. Java 8+ and this builder_tester directory (`lib/` + `build/` or `lib/builder-tester.jar`).

Then:

```bash
cp conf/tester_vm.conf conf/tester.conf   # or pass the file directly
./bin/start_tester.sh $PWD/conf/tester_vm.conf
curl http://<vm>:8090/health              # "executionMode":"vm", concurrency max 1
```

and add `<vm>:8090` to `vm_tester_nodes` in the Builder's `builder.conf`. Each VM
advertises one slot, so the builder sends it one case at a time; add more VMs to run more
cases in parallel. In the dashboard, choose Advanced Configuration → Execution Mode → VM.

The VM must be dedicated to Builder-Tester while the tester runs: each case kills the
user's CUBRID and helper processes and replaces `~/CUBRID` and `~/.CUBRID_SHELL_FM`
when a new build arrives, exactly as a regression run would.

## Configuration

See `conf/tester_vm.conf`. VM-specific keys:

| Key | Default | Meaning |
|---|---|---|
| `execution_mode` | `docker` / `direct` | `vm` enables this mode (forces one case at a time, disables Docker) |
| `vm_cubrid_home` | `~/CUBRID` | Where builds are installed; CTP's `$CUBRID` |
| `vm_ctp_home` | `~/CTP` | CTP used for `init_path` and `do_check_more_errors` |
| `vm_testcase_timeout_in_secs` | `7200` | CTP's `testcase_timeout_in_secs`; the case's process group is killed on timeout and the case fails |
| `vm_ssh_port` | `22` | Exported as `TEST_SSH_PORT` |
| `vm_big_space_dir` | (unset) | CTP's big space dir, exported as `TEST_BIG_SPACE` |
| `vm_default_dbcharset` | (inherit) | Exported as `CUBRID_CHARSET` |
| `vm_ignore_core_by_keywords` | (unset) | CTP's `ignore_core_by_keywords` (`EXCLUDED_CORES_BY_ASSERT_LINE`) |
| `vm_keep_artifacts` | `fail` | Collect the case's files and `$CUBRID/log` after the run: `fail`, `always`, `never` |

`test_read_timeout_minutes` (Builder -> Tester) must outlast one case plus an install.

## Results and logs

Per attempt, under the request's `tests/` directory (also sent back to the builder):

- `vm_<commit>_<case>_<hash>.log`: install/reset/run/final-check output, phases marked
  with `===== [builder-tester vm]`. The hash of the test path keeps cases that share a
  script name apart.
- `result_vm_<commit>_<case>_<hash>.result`: the case's `.result` after `do_check_more_errors`.
- `vmcase_<commit>_<case>_<hash>.txt` (artifact `case_files`, failed attempts by default):
  the text files the case left in its directory plus `$CUBRID/log`; a `.tar.gz` of the
  same files stays on the VM.
- `..._runner.sh`: the exact script that ran, for reproducing by hand on the VM.

SQL requests (`testType=sql`) are not supported in VM mode; send them to a Docker tester.

## Client integration (differences from Docker mode)

The Builder API, callback and `results.json` schema are unchanged. A client that already
drives Docker mode needs only the following.

**Choosing VM nodes.** Send `"executionMode": "vm"`. Without `workerIps` the request runs
on the Builder's `vm_tester_nodes` (`GET /health` on the Builder lists them as
`vmTesterNodes`); with `workerIps`, on those nodes. The Builder checks each node's
`/health` (`"executionMode": "vm"`, `concurrency.max == 1` on a VM tester) and rejects the
request with HTTP 400 if a node runs the other mode: Docker requests (`executionMode`
absent or `"docker"`) cannot use VM testers, and VM requests cannot use Docker testers.

**Request fields.**
- `executionMode`: `"vm"` (see above); `"docker"` is the default.
- `testType`: `shell` (default). A VM request with `"testType": "sql"` is rejected.
- `tests`, `commits`, `buildType`, `shellTcBranch`, `shellTcCommit`, `runMode`, `minRuns`,
  `maxRuns`, `customShellScript`, `customAttachments`, `customScriptTestPath`: as in Docker
  mode. Each attempt starts from the case directory restored to `shellTcCommit`, then the
  custom script/attachments are applied.
- `keepAlive` has no effect.

**Throughput and timeouts.** One case at a time per VM node, so a request takes roughly
sum(case durations x runs) / number of VM nodes. A case is killed and failed after
`vm_testcase_timeout_in_secs` (default 7200 s). Keep the builder's
`test_read_timeout_minutes` above that plus a build install (`builder.conf` has 160).

**Statuses** (per attempt and overall, same values as Docker mode):

| status | VM-mode meaning |
|---|---|
| `pass` | `.result` non-empty and no line contains `NOK` (after `do_check_more_errors`) |
| `fail` | a `NOK` line (including `: NOK found core file ...` / `: NOK found fatal error ...`), `message: "blank result"`, or `message: "Case timeout after <n>s"` |
| `environment_error` | CTP missing on the VM, case script not found, package download failed, unsafe `vm_cubrid_home`, or `testType=sql` |
| `build_error` | installing the build on the VM failed |
| `flaky` | computed by the builder from mixed attempt results, as before |

`results.json` entries do not record the execution mode; a client that needs it should
keep it from the node list it sent, or recognise the `vm_` log-file prefix.

**Logs and artifacts** in `attemptLogMetadata` (fetched exactly as in Docker mode):
- Attempt logs: `{"logFileName": "vm_<commitShort>_<testName>_<h>[.<attempt>].log", "attempt": n, "status": ...}`.
  `<h>` is the first 8 hex digits of SHA-1(testPath), so a client can compute the name;
  the `.<attempt>` suffix is present from attempt 2.
- Case-file bundles: `{"logFileName": "vmcase_vm_<...>.txt", "attempt": n, "artifactType": "case_files", "executionEnv": "vm"}`
  (no `status`), present for failed attempts unless `vm_keep_artifacts` says otherwise.
  Filter metadata entries by `artifactType` to tell them from attempt logs.
- Each attempt log is a sequence of phases, each starting with a line
  `===== [builder-tester vm] <phase>`: `install ...` / `snapshot ~/.CUBRID_SHELL_FM` /
  `install done` (only when a new build was installed), `reset processes`,
  `reset CUBRID`, `run case <path> (build <id>)`, then the case output, then
  `case exit code: <n>`, `final check (do_check_more_errors)` and `result file`, after
  which the final `.result` content is printed. A timed-out case ends with
  `===== [builder-tester vm] TIMEOUT after <n>s`.
