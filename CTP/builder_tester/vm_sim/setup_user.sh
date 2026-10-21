#!/bin/bash
# Runs as `shell` inside the container: the user-level layout of a regression VM.
set -e
SIM=/mnt/ctp/builder_tester/vm_sim
cd ~

# CTP (the VM's own copy, as regression VMs have) and the testcase checkouts
rm -rf ~/CTP && cp -r /mnt/ctp ~/CTP && rm -rf ~/CTP/builder_tester ~/CTP/.git
git clone -q --shared --no-checkout /mnt/tc ~/cubrid-testcases-private-ex
git -C ~/cubrid-testcases-private-ex checkout -q develop 2>/dev/null \
  || git -C ~/cubrid-testcases-private-ex checkout -q -b develop origin/develop
if [ -d /mnt/sqltc/.git ]; then
  git clone -q --shared --no-checkout /mnt/sqltc ~/cubrid-testcases
  git -C ~/cubrid-testcases remote set-url origin https://github.com/CUBRID/cubrid-testcases.git
  git -C ~/cubrid-testcases checkout -q develop
fi

# dblink assets copied from a regression VM (~/dblink_{mariadb,mysql,oracle} there)
for d in dblink_mariadb dblink_mysql dblink_oracle; do
  if [ -d "/mnt/dblink/$d" ]; then rm -rf ~/$d && cp -r "/mnt/dblink/$d" ~/$d; else echo "WARNING: /mnt/dblink/$d missing"; fi
done

# Builder-Tester VM tester
mkdir -p ~/builder_tester/conf ~/builder_tester/lib ~/CUBRID ~/tmp
cp /mnt/ctp/builder_tester/lib/*.jar ~/builder_tester/lib/
sed "s#@ADVERTISED@#${BT_ADVERTISED:-}#" $SIM/tester.conf > ~/builder_tester/conf/tester.conf
cp $SIM/start_vm_tester.sh ~/builder_tester/start_vm_tester.sh

cat > ~/.cubrid.sh <<'EOS'
CUBRID=$HOME/CUBRID
CUBRID_DATABASES=$CUBRID/databases
LD_LIBRARY_PATH=$CUBRID/lib:$CUBRID/cci/lib:$LD_LIBRARY_PATH
SHLIB_PATH=$LD_LIBRARY_PATH
LIBPATH=$LD_LIBRARY_PATH
PATH=$CUBRID/bin:$PATH
export CUBRID CUBRID_DATABASES LD_LIBRARY_PATH SHLIB_PATH LIBPATH PATH
EOS
grep -q cubrid.sh ~/.bash_profile 2>/dev/null || echo '. ~/.cubrid.sh' >> ~/.bash_profile
echo "user setup done: $(git -C ~/cubrid-testcases-private-ex log --oneline -1 | cut -c1-60)"
