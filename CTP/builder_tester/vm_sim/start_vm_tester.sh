#!/bin/bash
# Starts (or restarts) the VM tester in this VM, detached
cd ~/builder_tester || exit 1
pkill -u "$USER" -f "builder.Teste[r]" && sleep 2
setsid nohup java -Dfile.encoding=UTF-8 -Dtester.project.root=$HOME/builder_tester \
  -cp lib/json.jar:lib/jsch-0.1.55.jar:lib/builder-tester.jar com.navercorp.cubridqa.builder.Tester \
  $HOME/builder_tester/conf/tester.conf >> $HOME/builder_tester/tester_output.log 2>&1 < /dev/null &
echo "VM tester started (pid $!)"
