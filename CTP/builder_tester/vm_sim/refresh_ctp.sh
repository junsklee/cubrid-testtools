#!/bin/bash
# Brings the container's ~/CTP up to CUBRID/cubrid-testtools develop (or the given ref), as a
# regression VM keeps its CTP current. setup_user.sh copies this host's checkout, which falls
# behind: cases written for the current init.sh then fail ("wait_until: command not found").
# Upstream files are extracted over ~/CTP, so the container's own files (conf/common.conf,
# conf/sql_local.conf, ...) stay. Run it between requests: a running case sees the change.
# usage: refresh_ctp.sh [name] [ref]
set -e
NAME=${1:-bt_vm_sim}
REF=${2:-develop}
REPO=https://github.com/CUBRID/cubrid-testtools.git
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git clone -q --depth 1 -b "$REF" "$REPO" "$WORK/ctt"
COMMIT=$(git -C "$WORK/ctt" rev-parse --short HEAD)
tar -C "$WORK/ctt" --exclude=CTP/builder_tester -cf - CTP \
  | docker exec -u shell -i "$NAME" bash -c 'tar -C ~ --overwrite -xf - && echo "'"$REF $COMMIT"'" > ~/CTP/.ctp_version'
echo "~/CTP in $NAME is $REPO $REF at $COMMIT"
