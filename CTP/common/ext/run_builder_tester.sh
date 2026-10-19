#!/bin/bash
#
# Runs Builder-Tester cases on this QA VM between regression jobs.
#
# start_consumer.sh calls this script for messages on the Builder-Tester queue, after it has
# exported the message's properties (common/sched/init.sh). Listen on that queue next to the
# regression queue, so the VM takes one job at a time from either:
#
#   start_consumer.sh -q QUEUE_CUBRID_QA_SHELL_LINUX,QUEUE_CUBRID_QA_BUILDER_TESTER_LINUX \
#                     -exec run_shell,run_builder_tester
#
# The message names the Builder (BT_BUILDER_URL), the request (BT_REQUEST_ID) and this VM's
# share of it (BT_SLOT). The runner itself is downloaded from that Builder on every run, so this
# file is the only one deployed on the VM. The runner installs the request's builds into $CUBRID
# and runs the cases as CTP shell would, with this VM's CTP conf; the next regression job
# reinstalls its own build, as it does after any other job.
#
# $1 is "YES" when start_consumer.sh resumes an interrupted job; the Builder has already handed
# that job's case to another VM by then, so a resumed run only does what is still left.

# start_consumer.sh runs ext scripts with sh; this one needs bash
if [ -z "$BASH_VERSION" ]; then
    exec bash "$0" "$@"
fi

export CTP_HOME=${CTP_HOME:-$(cd "$(dirname "$(readlink -f "$0")")/../.."; pwd)}

log() {
    echo "[builder-tester] $*"
}

if [ -z "$BT_BUILDER_URL" ] || [ -z "$BT_REQUEST_ID" ] || [ -z "$BT_SLOT" ]; then
    log "not a Builder-Tester message (BT_BUILDER_URL, BT_REQUEST_ID or BT_SLOT missing); nothing to do"
    exit 0
fi
# The values come from the queue; keep them to what a URL, request ID and slot can be
if ! [[ "$BT_BUILDER_URL" =~ ^https?://[A-Za-z0-9.:_-]+$ && "$BT_REQUEST_ID" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ && "$BT_SLOT" =~ ^[0-9a-f]{32}$ ]]; then
    log "refusing malformed message values: $BT_BUILDER_URL $BT_REQUEST_ID $BT_SLOT"
    exit 0
fi

runner_home=$HOME/.builder_tester_consumer
lib=$runner_home/lib
mkdir -p "$lib"

fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsS --connect-timeout 10 --max-time 600 -o "$2" "$1"
    else
        wget -q -T 60 -O "$2" "$1"
    fi
}

# A finished or cancelled request leaves messages behind; skip those without downloading anything
state=$runner_home/config.json
if ! fetch "$BT_BUILDER_URL/consumer/config?requestId=$BT_REQUEST_ID&slot=$BT_SLOT" "$state"; then
    log "Builder $BT_BUILDER_URL is not reachable; skipping request $BT_REQUEST_ID"
    exit 0
fi
if ! grep -q '"active":true' "$state"; then
    log "request $BT_REQUEST_ID has no work for this message (finished or cancelled); nothing to do"
    exit 0
fi

# The runner's classpath: every jar the Builder serves, replaced as a whole
if ! fetch "$BT_BUILDER_URL/consumer/lib/" "$runner_home/jars.txt"; then
    log "could not list the runner jars on $BT_BUILDER_URL; skipping request $BT_REQUEST_ID"
    exit 0
fi
rm -rf "$lib.new" && mkdir -p "$lib.new"
classpath=
while read -r jar; do
    [[ "$jar" =~ ^[A-Za-z0-9._-]+\.jar$ ]] || continue
    if ! fetch "$BT_BUILDER_URL/consumer/lib/$jar" "$lib.new/$jar"; then
        log "could not download $jar from $BT_BUILDER_URL; skipping request $BT_REQUEST_ID"
        exit 0
    fi
    classpath=$classpath${classpath:+:}$lib/$jar
done < "$runner_home/jars.txt"
rm -rf "$lib" && mv "$lib.new" "$lib"

java=java
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java=$JAVA_HOME/bin/java
fi

# git must never wait for a password: the runner fetches testcases on its own
export GIT_TERMINAL_PROMPT=0
log "running request $BT_REQUEST_ID for $BT_BUILDER_URL (slot ${BT_SLOT:0:8})"
"$java" -Dfile.encoding=UTF-8 -cp "$classpath" com.navercorp.cubridqa.builder.consumer.ConsumerRunner \
    "$BT_BUILDER_URL" "$BT_REQUEST_ID" "$BT_SLOT" "$runner_home" "$CTP_HOME" 2>&1 | tee -a "$runner_home/runner.log"
log "request $BT_REQUEST_ID done on this VM"
exit 0
