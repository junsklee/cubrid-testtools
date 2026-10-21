#!/bin/bash
# Creates the local stand-in for a QAHome shell regression VM: a container from the CUBRID test
# image, set up like 192.168.2.152 (user shell, packages, locales, dblink assets, Oracle root
# setup), running a Builder-Tester VM tester. See README.md.
#
# usage: create_vm_sim.sh [name] [host port]
#   env: TESTCASES (shell testcases repo), SQL_TESTCASES (cubrid-testcases repo), DBLINK_DIR
#        (dir holding dblink_mariadb, dblink_mysql, dblink_oracle), HOST_IP, IMAGE
set -e
NAME=${1:-bt_vm_sim}
PORT=${2:-18090}
CTP_DIR=$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)
TESTCASES=${TESTCASES:-$HOME/cubrid-testcases-private-ex}
SQL_TESTCASES=${SQL_TESTCASES:-$HOME/cubrid-testcases}
DBLINK_DIR=${DBLINK_DIR:-$HOME/vm_sim_assets}
HOST_IP=${HOST_IP:-$(hostname -I | awk '{print $1}')}
IMAGE=${IMAGE:-cubridci/cubridci:test_shell}

[ -d "$TESTCASES/.git" ] || { echo "no shell testcases repo at $TESTCASES"; exit 1; }
[ -f "$CTP_DIR/builder_tester/lib/builder-tester.jar" ] || { echo "build Builder-Tester first (bin/compile.sh)"; exit 1; }
for d in dblink_mariadb dblink_mysql dblink_oracle; do
  [ -d "$DBLINK_DIR/$d" ] || echo "WARNING: $DBLINK_DIR/$d missing: dblink cases will not run (copy ~/$d from a regression VM)"
done
MOUNTS=(-v "$CTP_DIR:/mnt/ctp:ro" -v "$TESTCASES:/mnt/tc:ro")
[ -d "$SQL_TESTCASES/.git" ] && MOUNTS+=(-v "$SQL_TESTCASES:/mnt/sqltc:ro")
[ -d "$DBLINK_DIR" ] && MOUNTS+=(-v "$DBLINK_DIR:/mnt/dblink:ro")

docker run -d --name "$NAME" --hostname "${NAME//_/-}" -p "$PORT:8090" \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" -e BT_ADVERTISED="$HOST_IP:$PORT" \
  "${MOUNTS[@]}" --entrypoint bash "$IMAGE" /mnt/ctp/builder_tester/vm_sim/setup_root.sh
echo "started $NAME; setup takes a few minutes: docker logs -f $NAME"
echo "then: curl http://$HOST_IP:$PORT/health   (add $HOST_IP:$PORT to vm_tester_nodes)"
