#!/bin/bash
# Container entrypoint (root): turns the CUBRID test image into a stand-in for a QAHome shell
# regression VM (compared with 192.168.2.152), then starts the VM tester as user `shell`.
set -e
SIM=/mnt/ctp/builder_tester/vm_sim

# Regression VMs run everything as `shell`; QA's dblink assets depend on it (the MariaDB
# tarball's my.cnf.org has user = shell). The host uid keeps the mounts readable.
groupadd -g "$HOST_GID" shell 2>/dev/null || true
id shell >/dev/null 2>&1 || useradd -u "$HOST_UID" -g "$HOST_GID" -m -d /home/shell -s /bin/bash shell

# Packages shell cases use on regression VMs: dblink DB servers (libaio numactl-libs
# ncurses-compat-libs libnsl perl-DBI compat-openssl10; ncurses-compat-libs also runs 11.0
# builds), envsubst (gettext), ss (iproute), tcpdump, svn (subversion), and every system locale
# (glibc-all-langpacks), which needs the image's en_US-only %_install_langs lifted first.
mv /etc/rpm/macros.image-language-conf /etc/rpm/disabled-image-language-conf.bak 2>/dev/null || true
(dnf install -y -q --setopt=install_weak_deps=False glibc-gconv-extra python3 valgrind make bzip2 unzip \
    libaio numactl-libs ncurses-compat-libs libnsl perl-DBI compat-openssl10 \
    gettext iproute tcpdump subversion glibc-all-langpacks >/tmp/dnf.log 2>&1 && echo "dnf ok") \
  || echo "dnf failed (see /tmp/dnf.log)"
# Regression VMs have no jq and no unversioned python; cases check for both (command -v) and
# behave differently when they are there.
dnf remove -y -q jq >/dev/null 2>&1 || true
alternatives --auto python >/dev/null 2>&1 || true

# Oracle dblink cases need what a root user sets up once on a regression VM.
echo 'XE:/u01/app/oracle/product/11.2.0/xe:N' > /etc/oratab
ln -sfn /home/shell/dblink_oracle/install/oracle_11.2/u01 /u01

su - shell -c "BT_ADVERTISED=$BT_ADVERTISED bash $SIM/setup_user.sh"
su - shell -c 'bash ~/builder_tester/start_vm_tester.sh'
sleep 8; tail -3 /home/shell/builder_tester/tester_output.log
tail -f /dev/null
