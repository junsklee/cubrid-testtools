# ---------------------------------------------------------------------------
# builder_tester build accelerators
#
# Emitted verbatim into every generated Docker build script. The CUBRID compile
# itself is already covered by ccache; what is not covered is the network work
# the build repeats from scratch every time, because the script wipes the
# workspace and the build directory before each build:
#
#   - pl_install_jdk downloads a 103MB Temurin JDK, twice per build
#   - the nine 3rdparty ExternalProjects re-download their tarballs
#   - the three submodules are cloned from GitHub
#
# Each helper is advisory: it returns 0 and leaves the build on its normal
# (downloading) path whenever it cannot do better, so a miss costs nothing
# beyond what the build already did. Set BT_ACCEL=0 to disable all of them.
# ---------------------------------------------------------------------------

if [ -d /work ]; then
  BT_CACHE_DIR=/work/.cache
else
  BT_CACHE_DIR=/tmp/.cache
fi
mkdir -p "$BT_CACHE_DIR" 2>/dev/null || true

BT_T0=$(date +%s)
BT_TPREV=$BT_T0

# bt_phase <name> - emit a parseable phase timing marker. Always on: without it
# a regression in any single phase is invisible in the build log.
bt_phase() {
  local now
  now=$(date +%s)
  echo "[BT-PHASE] name=$1 seconds=$((now - BT_TPREV)) total=$((now - BT_T0))"
  BT_TPREV=$now
}

# bt_local_submodules - point submodule URLs at the host checkout's object
# stores (mounted read-only at /cubrid-src) instead of GitHub. Must run after
# `git submodule sync`, which rewrites these URLs from .gitmodules; re-running
# that sync is what restores the network URLs for the fallback path.
bt_local_submodules() {
  [ "${BT_ACCEL:-1}" = "1" ] || return 0
  [ -d /cubrid-src/.git/modules ] || return 0
  local n p used=0
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    p="/cubrid-src/.git/modules/$n"
    [ -d "$p" ] || continue
    if git config "submodule.$n.url" "$p" 2>/dev/null; then
      used=$((used + 1))
    fi
  done <<EOF
$(git config -f .gitmodules --name-only --get-regexp '^submodule\..*\.url$' 2>/dev/null | sed -e 's/^submodule\.//' -e 's/\.url$//' || true)
EOF
  if [ "$used" -gt 0 ]; then
    echo "[bt-accel] submodules: $used resolved from the host object store"
  fi
  return 0
}

# bt_seed_jdk <build_dir> - satisfy pl_install_jdk from the JDK already in the
# build image.
#
# The engine's pl_engine/cmake/install_jdk.cmake does:
#     if(NOT EXISTS ${JDK_ARCHIVE})   -> download 103MB
#     if(NOT EXISTS ${JDK_DEST_DIR})  -> extract, then delete the archive
# The two guards are independent and the target runs twice per build (build and
# install phases), so the second run re-downloads an archive it never unpacks.
# Placing both an extracted JDK and an archive marker short-circuits each guard;
# the marker is never read, because the extract branch it guards is skipped.
#
# Seeding happens only when the image carries the exact version the engine asks
# for, so a JDK bump upstream falls back to downloading rather than silently
# building against the wrong JDK.
bt_seed_jdk() {
  [ "${BT_ACCEL:-1}" = "1" ] || return 0
  local bdir="$1"
  local cm="pl_engine/cmake/install_jdk.cmake"
  [ -n "$bdir" ] || return 0
  if [ ! -f "$cm" ]; then
    echo "[bt-accel] jdk: no $cm in this revision; leaving the JDK to the engine"
    return 0
  fi
  local want
  want=$(grep -oE 'jdk8u[0-9]+-b[0-9]+' "$cm" | head -1 || true)
  if [ -z "$want" ]; then
    echo "[bt-accel] jdk: could not read the required version; leaving the JDK to the engine"
    return 0
  fi
  local src=""
  if [ -d "/opt/$want" ]; then
    src="/opt/$want"
  elif [ -d /opt/jdk8 ]; then
    case "$(readlink -f /opt/jdk8 2>/dev/null || echo)" in
      */"$want") src=/opt/jdk8 ;;
    esac
  fi
  if [ -z "$src" ]; then
    echo "[bt-accel] jdk: image does not carry $want; leaving the JDK to the engine"
    return 0
  fi
  local vm="$bdir/vm"
  mkdir -p "$vm" 2>/dev/null || return 0
  rm -rf "$vm/jdk8" 2>/dev/null || true
  if ! cp -a "$src/" "$vm/jdk8" 2>/dev/null; then
    echo "[bt-accel] jdk: copy from $src failed; leaving the JDK to the engine"
    rm -rf "$vm/jdk8" 2>/dev/null || true
    return 0
  fi
  # install_jdk.cmake prunes these right after extracting; match it so the
  # packaged tree is identical whether the JDK was seeded or downloaded.
  rm -rf "$vm/jdk8/man" "$vm/jdk8/sample" "$vm/jdk8/src.zip" 2>/dev/null || true
  if ! "$vm/jdk8/bin/java" -version >/dev/null 2>&1; then
    echo "[bt-accel] jdk: seeded JDK is not runnable; leaving the JDK to the engine"
    rm -rf "$vm/jdk8" 2>/dev/null || true
    return 0
  fi
  : > "$vm/jdk8.tar.gz" 2>/dev/null || true
  echo "[bt-accel] jdk: seeded $want from $src (skips 2 x 103MB download)"
  return 0
}

# bt_seed_3rdparty <build_dir> - prime the ExternalProject download directory
# from a host-side cache. CMake re-verifies every archive against its recorded
# SHA256 and re-downloads on mismatch, so a stale or corrupt cache entry costs a
# download, never a bad build.
bt_seed_3rdparty() {
  [ "${BT_ACCEL:-1}" = "1" ] || return 0
  local bdir="$1"
  local cache="$BT_CACHE_DIR/3rdparty-download"
  [ -n "$bdir" ] || return 0
  if [ ! -d "$cache" ]; then
    echo "[bt-accel] 3rdparty: cache is cold; this build populates it"
    return 0
  fi
  mkdir -p "$bdir/3rdparty/Download" 2>/dev/null || return 0
  cp -a "$cache/." "$bdir/3rdparty/Download/" 2>/dev/null || true
  echo "[bt-accel] 3rdparty: seeded $(find "$bdir/3rdparty/Download" -type f 2>/dev/null | wc -l) archive(s) from cache"
  return 0
}

# bt_save_3rdparty <build_dir> - refresh the host cache from a build that has
# just succeeded, so anything CMake had to re-download is cached already
# hash-verified. Overwrites unconditionally: that is what lets a bad cache entry
# heal itself on the next successful build.
bt_save_3rdparty() {
  [ "${BT_ACCEL:-1}" = "1" ] || return 0
  local bdir="$1"
  local dl="$bdir/3rdparty/Download"
  local cache="$BT_CACHE_DIR/3rdparty-download"
  [ -n "$bdir" ] || return 0
  [ -d "$dl" ] || return 0
  mkdir -p "$cache" 2>/dev/null || return 0
  cp -a "$dl/." "$cache/" 2>/dev/null || true
  echo "[bt-accel] 3rdparty: cache holds $(find "$cache" -type f 2>/dev/null | wc -l) archive(s)"
  return 0
}

# bt_prepare_workspace <target> <commit> - decide whether to reuse the existing
# build workspace, leaving the answer in BT_WORKSPACE_REUSED.
#
# This is what makes the 3rdparty ExternalProjects skip. Restoring the built
# 3rdparty tree does not work: CMake re-runs every configure/build/install step
# whenever CMakeCache.txt and build.ninja are regenerated from scratch, and that
# invalidation comes from outside 3rdparty/. Keeping the workspace lets CMake do
# an incremental reconfigure instead, so the step stamps stay valid. It also keeps
# gradle's state in repo/pl_engine/.gradle, which is what makes pl_build warm.
#
# Guards, in order; anything that fails falls back to a clean wipe:
#   - BT_WORKSPACE_REUSE must be 1 (opt-in)
#   - the previous build must have finished cleanly (.bt_dirty absent)
#   - the reuse budget must not be exhausted (BT_WORKSPACE_REUSE_MAX)
#   - the workspace must still be a git repo that already has the wanted commit
bt_prepare_workspace() {
  local target="$1" commit="${2:-}"
  local reason="" count=0 max="${BT_WORKSPACE_REUSE_MAX:-20}"
  BT_WORKSPACE_REUSED=0
  if [ "${BT_ACCEL:-1}" != "1" ] || [ "${BT_WORKSPACE_REUSE:-0}" != "1" ]; then
    reason="workspace reuse is disabled"
  elif [ ! -d "$target/repo/.git" ]; then
    reason="no previous workspace"
  elif [ -f "$target/.bt_dirty" ]; then
    reason="previous build did not finish cleanly"
  else
    count=$(cat "$target/.bt_reuse_count" 2>/dev/null || echo 0)
    case "$count" in ''|*[!0-9]*) count=0 ;; esac
    if [ "$count" -ge "$max" ]; then
      reason="reuse budget of $max builds reached"
    elif [ -n "$commit" ] && ! git -C "$target/repo" cat-file -e "${commit}^{commit}" 2>/dev/null; then
      reason="workspace does not have commit ${commit:0:7}"
    else
      BT_WORKSPACE_REUSED=1
      count=$((count + 1))
      echo "$count" > "$target/.bt_reuse_count" 2>/dev/null || true
      echo "[bt-accel] workspace: reusing $target (build $count of $max before a forced clean)"
    fi
  fi
  if [ "$BT_WORKSPACE_REUSED" != "1" ]; then
    rm -rf "$target"
    mkdir -p "$target"
    echo 1 > "$target/.bt_reuse_count" 2>/dev/null || true
    echo "[bt-accel] workspace: clean build ($reason)"
  fi
  # Dirty for the duration of the build; bt_workspace_ok clears it on success, so a
  # crash, timeout or kill leaves the marker and forces the next build to wipe.
  : > "$target/.bt_dirty" 2>/dev/null || true
  export BT_WORKSPACE_REUSED
  return 0
}

# bt_workspace_ok <target> - record that this build finished cleanly, which is what
# lets the next build reuse the workspace.
bt_workspace_ok() {
  rm -f "$1/.bt_dirty" 2>/dev/null || true
  return 0
}

# bt_reset_install <build_dir> - drop the staged install tree before a reused build.
#
# CMake's install step only ever ADDS files; it never removes one that is no longer
# part of the install set. In a reused workspace that means anything a previous
# commit installed survives into this commit's package. Measured: a reused build
# shipped both cubrid-jdbc-11.3.2.0000.jar (correct) and cubrid-jdbc-11.4.0.0000.jar
# (left over from the previous commit), plus four stale cm_ssl certs - silent
# classpath pollution in the artifact the testers install.
#
# The install tree is cheap to rebuild (it is a copy, a few seconds) and is not what
# makes reuse fast; 3rdparty and the object files are. So drop it every reused build.
bt_reset_install() {
  [ "${BT_WORKSPACE_REUSED:-0}" = "1" ] || return 0
  local bdir="$1"
  [ -n "$bdir" ] || return 0
  [ -d "$bdir/_install" ] || return 0
  rm -rf "$bdir/_install" 2>/dev/null || true
  echo "[bt-accel] workspace: cleared the staged install tree so no artifact outlives its commit"
  return 0
}

# bt_guard_build_inputs <build_dir> <marker> - fall back to a clean build directory
# when this commit's 3rdparty definitions or submodule pointers differ from the ones
# the workspace was last built at.
#
# Two measured failures motivate this, both silent or costly:
#
#   - ExternalProject cannot migrate a built tree across a version change. Going
#     from OpenSSL 3.5.7 back to 1.1.1w in a reused workspace fails in
#     libopenssl-build.
#   - ExternalProject stamps do not track a submodule's source tree. When the
#     cubridmanager pointer advanced, its stamps stayed valid, the whole subproject
#     was skipped, and the package shipped the PREVIOUS commit's TLS certificates -
#     cm_ssl_cert.crt as a 774-byte regular file instead of the new RSA-2048 pair.
#     The build reported success.
#
# Submodule pointers move often on develop, so this will frequently force a rebuild;
# that is the point. The clone and its submodules are still reused, so the cost is
# the 3rdparty and subproject rebuild, not the checkout.
bt_guard_build_inputs() {
  local bdir="$1" marker="$2" now=""
  [ -n "$bdir" ] && [ -n "$marker" ] || return 0
  # The branch is part of the identity: the gate in BuilderTask only vets the incoming
  # request, so without this a develop build could still inherit a workspace left by a
  # maintenance branch whose submodule pointers happened to match.
  now=$( { echo "branch=${BT_BRANCH:-}"
           cat 3rdparty/CMakeLists.txt 2>/dev/null
           git ls-tree -r HEAD 2>/dev/null | awk '$2=="commit"{print $3, $4}'
         } | md5sum 2>/dev/null | cut -d' ' -f1 || true)
  [ -n "$now" ] || return 0
  if [ "${BT_WORKSPACE_REUSED:-0}" = "1" ] && [ -d "$bdir" ]; then
    if [ ! -f "$marker" ] || [ "$(cat "$marker" 2>/dev/null)" != "$now" ]; then
      rm -rf "$bdir" 2>/dev/null || true
      echo "[bt-accel] workspace: 3rdparty or submodule pointers changed; rebuilding the build directory"
    fi
  fi
  # Recorded on every build, reused or not, so the next reuse has something to compare.
  echo "$now" > "$marker" 2>/dev/null || true
  return 0
}
