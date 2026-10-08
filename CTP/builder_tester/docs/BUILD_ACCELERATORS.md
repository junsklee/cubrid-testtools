# Build Accelerators

**Date**: October 8, 2026
**Issue**: Build times roughly doubled (median 56s → 117s) without a matching change in ccache hit rate or engine size
**Outcome**: 99s → 52s on a controlled A/B, with zero network fetches during a steady-state build

## What was actually slow

Build durations were reconstructed from the embedded timestamps in
`log/requests/*/builds/build_*.log` (`Entering target [build]` → `Leaving target [build]`)
and from ninja's own per-edge timings in `.ninja_log`.

| | Jan–Apr 2026 | Sep–Oct 2026 |
|---|---|---|
| All builds (median) | 56s | 117s |
| Builds at ≥97% ccache hit | 36s | 69s |
| Builds at <80% ccache hit | 88s | 128s |
| Setup (clone + submodules) | 9–12s | 20–44s (peak 157s) |
| Translation units | 2877 | 2949 (+2.5%) |

Two things rule out the obvious explanations. Builds at an **identical** ≥97% cache hit
rate still doubled, so this was not a ccache problem; and the engine grew only 2.5% in
translation units, so it was not engine growth either.

`.ninja_log` showed where the time went. In one 256.2s build:

```
pl_install_jdk        242.3s     <- 95% of wall clock, alone on the critical path
(everything else finished at ~92s; the build simply waited)
```

`pl_install_jdk` downloads a 103MB Temurin JDK from GitHub releases. It runs **twice per
build**, because `pl_engine/cmake/install_jdk.cmake` guards the archive and the extract
directory independently and deletes the archive after extracting:

```cmake
if(NOT EXISTS ${JDK_ARCHIVE})     # download 103MB
if(NOT EXISTS ${JDK_DEST_DIR})    # extract, then delete the archive
```

So the install phase re-downloads an archive it then never unpacks. The leftover was
visible in the workspace: `vm/jdk8.tar.gz` (103,096,556 bytes) sitting unused next to an
already-extracted `vm/jdk8/`.

That step has been in the build since January. What changed is throughput to GitHub's
CDN — measured at 6.4 MB/s from this host, giving a ~16s floor per download and 65–242s
when the CDN throttles. Everything network-bound degraded together, which is also why the
clone/submodule phase went from 9s to 44s.

The build repeats more network work than just the JDK, because the generated script wipes
the workspace and the build directory before every build:

- the nine 3rdparty ExternalProjects re-download their tarballs (~70 cpu-s)
- the three submodules are cloned from GitHub, even though the host checkout mounted at
  `/cubrid-src` already holds their objects in `.git/modules/`

ccache covers the CUBRID compile step and nothing else.

## What was changed

All of it lives in builder_tester; the engine is untouched.

[`src/com/navercorp/cubridqa/builder/build-accel.sh`](../src/com/navercorp/cubridqa/builder/build-accel.sh)
defines five shell helpers, inlined verbatim into every generated build script by
`DockerBuildManager.writeBuildAccelHelpers()`:

| helper | what it does |
|---|---|
| `bt_seed_jdk` | copies the JDK out of the build image into `<build_dir>/vm/jdk8` and drops an archive marker, so both cmake guards short-circuit and neither download happens |
| `bt_seed_3rdparty` | primes `<build_dir>/3rdparty/Download` from a host cache under `/work/.cache` |
| `bt_save_3rdparty` | refreshes that cache from a build that just succeeded |
| `bt_local_submodules` | points submodule URLs at `/cubrid-src/.git/modules/*` instead of GitHub |
| `bt_phase` | emits `[BT-PHASE] name=… seconds=… total=…`, which the builder parses into one `Build phases [sha]: …` log line |

Every helper is advisory: it returns 0 and leaves the build on its normal downloading
path whenever it cannot do better, so a miss costs nothing beyond what the build already did.

### Why each shortcut is safe

**JDK.** The build image already ships the exact JDK the engine asks for — `/opt/jdk8` →
`/opt/jdk8u442-b06`, Temurin `1.8.0_442-b06`, the same `jdk8u442-b06` build the script
fetches. Seeding happens **only** when the image carries the exact version parsed out of
`install_jdk.cmake`, so a JDK bump upstream falls back to downloading rather than silently
building against the wrong JDK. The seeded tree is pruned of `man`/`sample`/`src.zip` to
match what `install_jdk.cmake` does after extracting.

The archive marker is an empty file. It is never read: the branch that would extract it is
guarded by `NOT EXISTS ${JDK_DEST_DIR}`, which the seeded `jdk8/` already satisfies. That
same guard is why `file(REMOVE ${JDK_ARCHIVE})` never runs, so the marker survives for the
second invocation too.

**3rdparty.** CMake re-verifies every archive against its recorded SHA256 and re-downloads
on mismatch, so a stale or corrupt cache entry costs a download, never a bad build. The
save path overwrites unconditionally, which is what lets a bad entry heal itself.

**Submodules.** `git submodule sync` is what writes the GitHub URLs, so the rewrite runs
after it; the retry path re-runs that sync to put the remote URLs back before retrying.

### Configuration

```properties
# conf/builder.conf
build_accel_enabled=true    # single off-switch; false restores the all-downloads path
ccache_max_size=100G        # was 30G
```

`build_accel_enabled` reaches the container as `BT_ACCEL`. The ccache had been pinned at
24–27G against its 30G cap since January — permanently LRU-evicting at roughly 2.6G per
distinct build, so only ~11 builds fit. `/home` has ~647G free.

## Measured result

A/B on commit `6ba9522` (debug, warm ccache, same host), running the builder's own
generated script in the builder's own container setup, varying only `BT_ACCEL`:

| | accel off | accel on, cold cache | accel on, steady state |
|---|---|---|---|
| **wall** | **99s** | **55s** | **52s** |
| clone | 2s | 1s | 1s |
| submodules | 26s | 1s | 1s |
| seed | 0s | 1s | 0s |
| build | 59s | 42s | 37s |
| package | 10s | 9s | 10s |
| JDK downloads | 2 | 0 | 0 |
| 3rdparty network fetches | 9 | 9 | 0 |
| submodule clones from GitHub | 3 | 0 | 0 |
| ccache hit rate | 97.7% | 97.7% | 97.8% |

A steady-state build now makes **no network fetches at all**. The remaining variance is
compile and link work, which ccache and ninja already cover.

Package equivalence was checked against a package built by the normal download path for
the same commit: 448 entries each, no path unique to either, and the 203-entry `vm/jdk8`
tree identical on mode, size and path.

## Workspace reuse (opt-in, `build_workspace_reuse`)

With the network work gone, ninja's trace showed the next bottleneck: **the first
21.3s of a 79.4s build is a near-idle serial prologue**. 1234 of 1236 compile edges
wait on it.

```
libopenssl-download   0.1 -> 10.6     concurrency t=5-20s: ~5 edges on 16 cores
libodbc-configure     2.1 -> 10.5
pl_build (gradle)     3.0 -> 17.6
libopenssl-build     14.2 -> 20.2
---- CUBRID compilation finally starts at 21.3s ----
```

**Caching the built 3rdparty tree does not fix this**, and that was measured, not
assumed:

| attempt | result |
|---|---|
| restore installed artifacts + step stamps (35M) | all 8 libraries re-ran configure+build+install |
| restore the **entire** 3rdparty tree (383M) | still re-ran; prologue only 24.7s -> 21.3s |

CMake invalidates every ExternalProject step stamp when `CMakeCache.txt` and
`build.ninja` are regenerated from scratch, because a full reconfigure rewrites the
per-step scripts. The invalidation originates outside `3rdparty/`, so no amount of
restoring that directory helps. The control: running `build.sh` a second time in an
**untouched** build directory skips 3rdparty completely — 0 steps, 9 ninja edges, 20s.

So the lever is the workspace itself. `build_workspace_reuse=true` keeps
`/work/cubrid-build` between builds and checks out the next commit into it, which
lets CMake do an incremental reconfigure. It also keeps gradle's state in
`repo/pl_engine/.gradle`, which is what makes `pl_build` warm (5s instead of 14.5s)
— so it covers the pl_server.jar case too, with no separate cache.

| | clean clone (default) | workspace reused |
|---|---|---|
| wall | 50s | **37s** |
| build phase | 36s | **25s** |
| 3rdparty steps run | 19 | **0** |
| ccache operations | 3048 hits / 69 miss | 1 |

ccache drops to a single operation because ninja recompiles only what actually
changed between the two commits.

### Why it is off by default

It trades per-build isolation for speed. Today every build starts from a fresh clone,
so a build cannot be contaminated by its predecessor; with reuse, CMake and ninja's
incremental correctness is what keeps builds honest. That is what every developer
relies on locally, but it is a weaker guarantee, so turning it on is a deliberate
choice. Set `build_workspace_reuse=true` to enable it.

### Rails

- **A build that does not finish cleanly forces the next one to wipe.** The script
  writes `.bt_dirty` into the workspace up front and only clears it after the build
  succeeds, so a crash, timeout or kill cannot leave a poisoned workspace in use. The
  failed tree is left intact for inspection.
- **Bounded lifetime**: the workspace is wiped after `build_workspace_reuse_max`
  builds (default 20).
- **Missing commit falls back to a clean clone**, checked with `git cat-file -e`.
- `build_accel_enabled=false` disables reuse along with everything else.

### One real hazard this surfaced

`CMakeCache.txt` persists absolute paths, and the git shim was created at
`/tmp/git-shim-$$` — a PID that differs per container. A reused workspace then
configured against a shim that no longer existed:

```
GIT_EXECUTABLE:FILEPATH=/tmp/git-shim-12/git
CMake Error at CMakeLists.txt:157: Could not get count information from Git
```

The shim path is now the deterministic `/tmp/git-shim`. This is worth remembering if
anything else ever writes a per-container path that CMake might cache.

### Verification

- With reuse **off**, builds are byte-for-byte the old behaviour: clean wipe, 19
  3rdparty steps, 49-50s. No regression.
- With reuse **on**: first build clean (50s), subsequent builds 37s with 0 3rdparty steps.
- Crash rail: planting `.bt_dirty` made the next build wipe, as intended.
- Package equivalence: a reused build and a clean build of the same commit produce
  packages with identical file sets (447 entries each, no differences).

## What an adversarial soak found

Four develop commits spread over six months (Apr/Jun/Aug/Oct 2026) were built as a
reuse chain and each package compared against a clean build of the same commit.
Adjacent commits would have found nothing; the spread is what surfaced both defects.

Comparing packages needs care, because **CUBRID builds are not bit-reproducible**.
Two independent *clean* builds of one commit differ in 10 of 447 files, with
`libcubrid.so` among them — so byte comparison would have hidden exactly the files
that matter. Traced, the whole ELF difference is **2 bytes**:

```
build 1:  built on: Thu Oct  8 04:03:06 2026 UTC
build 2:  built on: Thu Oct  8 04:03:58 2026 UTC
```

OpenSSL's build timestamp, baked into statically linked libcrypto; `.text`, `.data`
and `.data.rel.ro` are identical. So packages are compared ELF section by section and
jars by entry CRC, leaving one known-benign difference: the javadoc jar, whose HTML
embeds a generation date.

**Defect 1 — stale artifacts outliving their commit (silent).** CMake's install step
only ever adds files; it never removes one no longer in the install set, and
`_install/CUBRID` was never cleaned between reused builds. A reused build of the June
commit shipped **both** `cubrid-jdbc-11.3.2.0000.jar` (correct) and
`cubrid-jdbc-11.4.0.0000.jar` (left over from the preceding August build), plus four
stale `cm_ssl` certs — two JDBC drivers on the classpath of the artifact the testers
install, with no error anywhere. Fixed by `bt_reset_install`.

**Defect 2 — 3rdparty version changes breaking the build (loud).** OpenSSL 3.5.7 back
to 1.1.1w in a reused workspace fails in `libopenssl-build`; ExternalProject cannot
migrate a built tree across a version change. The `.bt_dirty` rail contained it, but
at the cost of a failed build. Fixed by `bt_guard_build_inputs` (see defect 3).

**Defect 3 — a submodule bump shipping the previous commit's artifacts (silent).**
`cubridmanager` is an ExternalProject, and ExternalProject stamps do not track a
submodule's source tree. Between two *develop* commits eight days apart
(`b572177a1` -> `1eebef802`) the cubridmanager pointer advanced, its stamps stayed
valid, the subproject was skipped entirely (0 steps against 3 in a clean build), and
the package shipped the previous commit's TLS certificates:

| | clean | reused |
|---|---|---|
| `cm_ssl_cert_2048.crt/.key` | present | absent |
| `cm_ssl_cert_1024.crt/.key` | present | absent |
| `cm_ssl_cert.crt` | symlink to the 2048 cert | 774-byte regular file from the previous commit |

The submodule checkout was correct in both builds; the build simply never looked at
it. `cub_manager` is compiled with `CMS_DEFAULT_CERT_MD5` of the cert it expects, so
the mismatch can also surface at runtime.

Fixed by `bt_guard_build_inputs`, which hashes `3rdparty/CMakeLists.txt` **plus every
submodule pointer** and rebuilds the build directory when any of it moves. Note what
this costs: submodule pointers move often on develop (six cubrid-jdbc bumps in six
weeks, plus cubridmanager), so the guard fires frequently and such a build is barely
faster than a clean one - 54s against 61s, saving only the clone and submodules.

Branch-gating reuse to develop would **not** have caught this: both commits are on
develop. The invalidating event is a submodule pointer moving, not a branch change.

With both fixes the same chain passes: all four build, file sets match the clean
reference exactly, no `.text` differs, every functional jar matches by CRC, and the
only remaining difference is the javadoc jar — i.e. the clean-vs-clean floor.

**A caveat on that run.** Those four commits have *different* 3rdparty definitions, so
`bt_guard_3rdparty` wiped the build directory at each step: all three reused builds ran
25 3rdparty steps where true deep reuse shows 0. That run validated the guard, not the
incremental-compile path. It also means the headline 37s only holds for commits sharing
a 3rdparty definition (the ordinary PR case); across distant commits reuse gives back
most of its benefit, still beating a clean build only because the clone, submodules and
ccache survive.

### Recommendation

Keep `build_workspace_reuse=false`. A four-commit chain surfaced one silent wrong
artifact and one hard failure; both are fixed, but that hit rate on a small sample says
the failure surface is not fully mapped, and the prize is ~13s on a pipeline where tests
take minutes. Use the per-request **Re-use Artifacts** toggle for single-commit,
repeat-build work (flaky-test hunting, iterating on one commit), where the speed matters
and cross-commit contamination cannot apply.

### Branch gating

`build_workspace_reuse_branches` (default `develop`) lists the branches whose builds
may reuse a workspace. Applied after the per-request override, so it wins over an
explicit `reuseArtifacts: true`.

This is defence in depth, not the main guard. The contamination that was actually
measured happened between two develop commits, which `bt_guard_build_inputs` is what
catches; branch gating would not have prevented it. What it adds is a stop on a
maintenance branch — a 11.4 line built right after develop, say — ever reusing a
workspace from a different release line, where far more than a submodule differs.

The gate vets the *incoming* request, so on its own it would still let a develop build
inherit a workspace a maintenance branch left behind. The branch is therefore also part
of the workspace identity hashed by `bt_guard_build_inputs`, so a branch change rebuilds
the build directory the same way a submodule bump does.

### Per-request override

The dashboard's Advanced Configuration has a **Re-use Artifacts** toggle, off by
default and matching the global default, which warns when switched on. It sends
`reuseArtifacts: true` in the request. The field is a three-way override:

| request field | effect |
|---|---|
| absent | use `build_workspace_reuse` (off by default) |
| `true` | reuse the previous workspace for this request |
| `false` | force a clean build even where reuse is enabled globally |

## Still worth doing

- **`pigz` is missing from the build image** (~9s/build). The packaging code already
  prefers `pigz` and silently falls back to single-threaded `gzip`. Measured on the
  real 659MB tree: `gzip -1` = 10s, `pigz -1` = 1s, same output size. Installing it
  per-build costs 16s, so this only pays off in a derived image.
- **ccache is version 3.1.6 (2011)**. A cold build showed 1307 *preprocessed* hits
  against 977 direct; every preprocessed hit re-runs the preprocessor, which ccache
  4.x's depend mode skips entirely. Note this is uneven — a warm build showed 3047
  direct against 1 preprocessed — so it pays off on cold and header-churn builds,
  which is the PR case. Also: `include_file_ctime` in `ccache_sloppiness` is a no-op,
  since it was only added in ccache 3.2.
- Those two together argue for one thin derived image
  (`FROM cubridci/cubridci:develop` + pigz + modern ccache).
- **Upstream, in the engine**: nest the JDK download inside the
  `NOT EXISTS ${JDK_DEST_DIR}` guard, add `TIMEOUT`/`INACTIVITY_TIMEOUT`/
  `EXPECTED_HASH`, and honor a `CUBRID_JDK8_DIR` override. That fixes the wasted
  second download for every consumer, not just this harness.
- **`CCACHE_BASEDIR` is wrong**: set to `/tmp/cubrid-build` in `DockerBuildManager`
  while the workspace is always `/work/cubrid-build`, so basedir path rewriting never
  applies. Harmless today because the path is fixed and `CCACHE_NOHASHDIR=1` is set,
  and correcting it would invalidate the whole cache for no present gain — but it
  should be fixed whenever the cache is next reset.
- **`parallel_jobs` is a no-op**: it becomes `MAKEFLAGS=-j16`, which ninja ignores.
  Either pass `-j` through to `cmake --build` or drop the knob.
