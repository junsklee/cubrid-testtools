# vm_sim: a local stand-in for a QAHome shell regression VM

A container that runs Builder-Tester's VM tester (`execution_mode=vm`, see `docs/VM_MODE.md`)
in the environment of a QAHome shell regression VM, so VM-mode results match what regression
sees. The reference is 192.168.2.152, a shell regression VM. The container:

- runs everything as user `shell`, as regression VMs do (QA's MariaDB dblink tarball hard-codes
  `user = shell` in its `my.cnf.org`);
- has the packages shell cases use there: the dblink database servers' libraries (`libaio`,
  `numactl-libs`, `ncurses-compat-libs`, `libnsl`, `perl-DBI`, `compat-openssl10`; the ncurses 5
  library also lets 11.0 builds run), `envsubst` (`gettext`), `ss` (`iproute`), `tcpdump`, `svn`
  (`subversion`), and every system locale (`glibc-all-langpacks`; the image's en_US-only
  `%_install_langs` is lifted);
- has no `jq` and no unversioned `python`, like the regression VMs: cases check for both with
  `command -v` and take another path when they are missing;
- has `~/dblink_mariadb`, `~/dblink_mysql`, `~/dblink_oracle` as copied from a regression VM,
  and the root-level Oracle setup the Oracle dblink cases check (`/etc/oratab` with
  `XE:/u01/app/oracle/product/11.2.0/xe:N`, `/u01` linked to
  `~/dblink_oracle/install/oracle_11.2/u01`);
- has its own copy of CTP (`~/CTP`), clones of the shell (`~/cubrid-testcases-private-ex`) and
  SQL (`~/cubrid-testcases`) testcase repositories, and the VM tester in `~/builder_tester`.

## Create

```bash
bin/compile.sh                              # the VM tester runs lib/builder-tester.jar
vm_sim/create_vm_sim.sh [name] [host port]  # default: bt_vm_sim, 18090
docker logs -f bt_vm_sim                    # setup takes a few minutes
curl http://<host>:18090/health             # "executionMode":"vm"
```

Then list `<host>:18090` in `vm_tester_nodes` in `conf/builder.conf`.

Inputs (environment variables):

| Variable | Default | |
|---|---|---|
| `TESTCASES` | `~/cubrid-testcases-private-ex` | shell testcases; the VM fetches requested commits from it |
| `SQL_TESTCASES` | `~/cubrid-testcases` | SQL testcases (optional); its clone fetches from GitHub |
| `DBLINK_DIR` | `~/vm_sim_assets` | holds `dblink_mariadb`, `dblink_mysql`, `dblink_oracle`, copied from a regression VM's home (the tarballs only); without them dblink cases cannot run |
| `HOST_IP` | first address of `hostname -I` | how the Builder reaches the VM (`tester_advertised_address`) |
| `IMAGE` | `cubridci/cubridci:test_shell` | Rocky Linux 8 CUBRID test image |

## Maintain

- After `bin/compile.sh`: `vm_sim/refresh_tester.sh [name]` copies the jars in and restarts the
  tester.
- VM (regression) mode tests: `vm_sim/start_consumer.sh tcp://<broker>:61616 [name]` makes the VM
  a consumer of the Builder-Tester queue on that broker.
- To compare with a regression VM again: `rpm -qa --qf '%{NAME}\n'` on both, then check which of
  the missing commands the shell cases really run; `setup_root.sh` installs only those.
