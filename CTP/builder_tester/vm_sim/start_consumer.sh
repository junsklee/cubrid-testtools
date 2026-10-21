#!/bin/bash
# Optional, for VM (regression) mode: makes the VM a QAHome consumer of the Builder-Tester queue
# on the given ActiveMQ broker (start_consumer.sh with run_builder_tester), as a regression VM
# listening on that queue would.
# usage: start_consumer.sh <broker url, e.g. tcp://host:61616> [name] [queue]
set -e
BROKER=${1:?usage: start_consumer.sh tcp://host:61616 [name] [queue]}
NAME=${2:-bt_vm_sim}
QUEUE=${3:-QUEUE_CUBRID_QA_BUILDER_TESTER_LINUX}
docker exec -u shell -i "$NAME" bash -lc "cat > ~/CTP/conf/consumer.conf" <<EOC
activemq_url=$BROKER
activemq_user=
activemq_pwd=
EOC
docker exec -u shell -i "$NAME" bash -lc 'cat > ~/launch_consumer.sh && chmod +x ~/launch_consumer.sh' <<EOC
#!/bin/bash
cd ~/CTP/common/script
exec nohup bash start_consumer.sh -q $QUEUE -exec run_builder_tester --without-sync > ~/consumer.out 2>&1
EOC
docker exec -u shell -d "$NAME" bash -lc '~/launch_consumer.sh'
echo "consumer of $QUEUE on $BROKER started in $NAME (log: ~/consumer.out)"
