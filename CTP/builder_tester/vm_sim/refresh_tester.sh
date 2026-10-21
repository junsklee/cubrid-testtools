#!/bin/bash
# After bin/compile.sh: copies the Builder-Tester jars into the VM and restarts its tester.
set -e
NAME=${1:-bt_vm_sim}
LIB=$(cd "$(dirname "$(readlink -f "$0")")/../lib" && pwd)
for jar in "$LIB"/*.jar; do docker cp "$jar" "$NAME:/home/shell/builder_tester/lib/"; done
docker exec "$NAME" chown -R shell: /home/shell/builder_tester/lib
docker exec -u shell "$NAME" bash -lc '~/builder_tester/start_vm_tester.sh'
