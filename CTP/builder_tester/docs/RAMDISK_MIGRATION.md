# Tester Ramdisk Migration Guide

This guide walks through enabling ramdisk **only for the tester process**. Builder (including ccache) stays on disk.

## 1) Prepare a ramdisk
- Create/mount a tmpfs (example: 32G). Adjust size to your memory headroom and expected test payloads.
```bash
sudo mkdir -p /mnt/ramdisk
sudo mount -t tmpfs -o size=32G tmpfs /mnt/ramdisk
df -h /mnt/ramdisk
```
- Optional: make it persistent across reboot (e.g., add an fstab entry).

## 2) Update tester.conf
- Add or adjust these keys:
```properties
# Enable tester ramdisk (builder untouched)
ramdisk.enabled=true
ramdisk.root=/mnt/ramdisk
ramdisk.min_free_gb=4

# Targets (tester only)
ramdisk.tester_work=true     # move tester work_dir to ramdisk
ramdisk.logs=true            # move tester logs to ramdisk
ramdisk.profiles=false       # keep WAL/profiles on disk by default

# Optional fallback for profiles/logs if ramdisk is unavailable or disabled
# ramdisk.fallback_root=/home/you/cubrid-testtools/CTP/builder_tester/persist
```
- Keep `ccache` settings as-is (builder-side, not moved).

## 3) Start tester with ramdisk
```bash
./bin/start_tester.sh
```
- The tester will resolve paths at startup:
  - `work_dir` → `<ramdisk.root>/tester_work` when enabled and healthy
  - Logs → `<ramdisk.root>/tester_logs` when enabled
  - Profiles/WAL → stay on configured `tester_profiles_dir` unless `ramdisk.profiles=true`
  - Health endpoint publishes current ramdisk status (free/total, active flag, reason)

## 4) Smoke test
- Run a single test request.
- Verify paths land on tmpfs:
```bash
df -h | grep ramdisk
find /mnt/ramdisk -maxdepth 2 -type d -print
```
- Check health data:
```bash
curl -s http://localhost:8090/health | jq '.ramdisk'
```

## 5) Fallback & safeguards
- If tmpfs is missing, not tmpfs, or free space < `ramdisk.min_free_gb`, tester falls back to disk paths and reports the reason in `/health`.
- Clean old work dirs/logs to recover free space, then restart tester to re-enable ramdisk.

## 6) Optional persistence for profiles/logs
- If you need WAL/profiles or logs to survive reboot, set `ramdisk.profiles=true` only with a `ramdisk.fallback_root` and run a periodic sync to disk:
```bash
rsync -a --delete /mnt/ramdisk/tester_logs/ /home/you/ramdisk-backup/tester_logs/
rsync -a --delete /mnt/ramdisk/tester_profiles/ /home/you/ramdisk-backup/tester_profiles/
```

## 7) Rollback
- Set `ramdisk.enabled=false` (or unmount tmpfs) and restart tester; it will return to disk paths automatically.
