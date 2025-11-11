# WAL Operations & Deployment

## Overview

This document covers operational aspects of the WAL implementation: performance characteristics, deployment procedures, monitoring, testing, and configuration.

**See also:** [ARCHITECTURE.md](ARCHITECTURE.md) for design and implementation details.

## Performance Characteristics

### Storage

**Old (Unbounded):**
```
1,000 tests/day × 365 days × 200 bytes = 73 MB/year
```

**New (Segmented):**
```
Steady-state: 1-2 active segments × 4 MB max = 4-8 MB
After cleanup: Only current segment (~1-2 MB)
```

**Reduction:** 10-100× smaller

### Startup Replay Time

**Old:**
```
Read entire WAL (all history)
100,000 entries → ~1 second
1,000,000 entries → ~10 seconds
```

**New:**
```
Read only segments newer than snapshot
Typical: 5 minutes of observations = 3-20 entries
Replay time: ~10-50 milliseconds
```

**Speedup:** 20-1000× faster

### Write Throughput

**Old (Direct Write):**
```
Every test thread → GzipOutputStream → file I/O
Contention on file lock
Throughput: ~1,000 obs/sec
```

**New (Bounded Queue):**
```
Every test thread → queue.offer() (fast)
Single writer thread → batched writes
No contention
Throughput: ~10,000 obs/sec
```

**Speedup:** 10× higher throughput

### Memory

**Per Observation in Queue:** ~400 bytes (object overhead)  
**Max Queue:** 10,000 × 400 bytes = 4 MB  
**Total Overhead:** ~5 MB (negligible)

## Validation Impact

**Extrapolated:**
```
1,000 tests/day × 30 days × 75% invalid = 22,500 rejected
Storage saved: ~4.5 MB/month
Replay time saved: ~450 ms/month
```

## Migration Strategy

### Phase 1: Deploy New Code

1. Add new classes (MANIFEST, WALSegmentWriter, ObservationValidator, WALUtils)
2. Update TestStatsStore to use new architecture
3. Update Tester.java to create and wire components
4. Keep old WAL files for safety

### Phase 2: Test Environment Validation

1. Deploy to test testers
2. Monitor metrics:
   - Validation rejection rate
   - WAL segment rotation frequency
   - Queue drop rate (should be 0)
3. Verify startup performance improvement
4. Verify crash recovery scenarios

### Phase 3: Cleanup Old WAL

After 1 week of stable operation:
```bash
# Backup old WAL
cp profiles/test_stats.jl.gz profiles/backup/test_stats.jl.gz.$(date +%Y%m%d)

# Delete old WAL (new segments in profiles/wal/)
rm profiles/test_stats.jl.gz
```

### Phase 4: Production Rollout

1. Deploy to production testers
2. Monitor for 24 hours
3. Verify no data loss or performance degradation

## Monitoring Metrics

### WAL Writer Metrics

```java
WALMetrics metrics = walWriter.getMetrics();
// written=12,456, dropped=0, rotated=24, queue=12, segment=test_stats-20251111-153000-000001.jl
```

**Alerts:**
- `dropped > 0` → Queue capacity exceeded (increase queue size or snapshot frequency)
- `queueSize > 8000` → Approaching capacity (pre-warning)
- `rotated > 1000/day` → Too frequent rotation (increase rotation interval)

### Validation Metrics

```java
ValidationMetrics metrics = validator.getMetrics();
// accepted=9,500, rejected=500 (5.0%), reasons: incomplete=450, cpu=30, mem=20
```

**Alerts:**
- `rejectionRate > 10%` → Data quality issue (check DockerStatsCollector)
- `rejectedMetricsIncomplete > 1000` → Collector regression
- `rejectedInvalidCpu > 100` → Hardware detection issue

### Coordinator Metrics

**Logs:**
```
[Coordinator] Snapshot complete: 1234 tests, closed=test_stats-20251111-153000-000001.jl, new=test_stats-20251111-153500-000002.jl
[Coordinator] Cleanup: 5 deleted, 0 failed
```

**Alerts:**
- Coordinator failures → Check logs for root cause
- Cleanup failures → Check disk space and permissions

### Storage Metrics

```bash
# Check WAL segment sizes
ls -lh profiles/wal/

# Check total WAL storage
du -sh profiles/wal/

# Check MANIFEST
cat profiles/MANIFEST.json | jq '.'
```

## Configuration

**In tester.conf:**
```properties
# WAL rotation settings
wal_rotation_interval_minutes=5
wal_rotation_size_mb=4
wal_queue_capacity=10000

# Snapshot settings
stats.snapshot_interval_seconds=300

# Validation settings (hardware limits)
node_cpu_cores=8
node_memory_mb=16384
```

### Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `wal_rotation_interval_minutes` | 5 | Maximum time between WAL segment rotations |
| `wal_rotation_size_mb` | 4 | Maximum size (MB) before WAL segment rotation |
| `wal_queue_capacity` | 10000 | Maximum observations in write queue |
| `stats.snapshot_interval_seconds` | 300 | Interval between snapshots (5 minutes) |
| `node_cpu_cores` | 8 | CPU cores for validation limits |
| `node_memory_mb` | 16384 | Total memory (MB) for validation limits |

## Testing

### Unit Tests

1. **WALManifest:**
   - Crash-safe updates
   - Segment replay logic
   - Cleanup logic
   - Backup fallback

2. **WALSegmentWriter:**
   - Rotation by time
   - Rotation by size
   - Queue overflow (drop policy)
   - Fsync verification
   - Process lock acquisition

3. **ObservationValidator:**
   - Each rejection category
   - Edge cases (0, negative, huge values)
   - Metrics tracking

4. **WALUtils:**
   - Directory fsync (with exception handling)
   - SHA-256 computation
   - Lock acquisition
   - Atomic writes

### Integration Tests

1. **End-to-End Flow:**
   - Write 10,000 observations
   - Trigger snapshot
   - Verify WAL rotated
   - Verify old segments deleted
   - Restart (crash simulation)
   - Verify replay only recent segments

2. **Crash Recovery:**
   - Crash during snapshot write
   - Crash after snapshot, before MANIFEST
   - Crash during WAL write
   - Corrupt MANIFEST recovery
   - Verify no data loss in all cases

3. **Legacy Compatibility:**
   - Replay old gzipped WAL file
   - Verify rename to `.legacy`
   - Verify no double-replay

### Performance Tests

1. **Throughput:** 10,000 observations in < 1 second
2. **Startup:** Replay 1,000 segments in < 100 ms
3. **Memory:** Queue overhead < 10 MB

## Success Criteria

 **Immediate:**
- WAL bounded to < 10 MB
- Startup replay < 100 ms
- No invalid observations stored
- Zero queue drops under normal load
- All crash scenarios handled correctly

 **Long-term:**
- Stable WAL size over months
- No data loss incidents
- Rejection rate < 5%
- Startup time independent of deployment age
- Production-ready crash-safety

## Troubleshooting

### Queue Drops

**Symptom:** `dropped > 0` in metrics

**Causes:**
- High test throughput exceeding queue capacity
- Snapshot interval too long
- Writer thread blocked

**Solutions:**
- Increase `wal_queue_capacity`
- Decrease `stats.snapshot_interval_seconds`
- Check disk I/O performance

### High Rejection Rate

**Symptom:** `rejectionRate > 10%`

**Causes:**
- DockerStatsCollector not collecting metrics
- Hardware detection issues
- Test execution failures

**Solutions:**
- Check DockerStatsCollector logs
- Verify hardware detection
- Review test execution logs

### Coordinator Failures

**Symptom:** Coordinator errors in logs

**Causes:**
- Disk full
- Permission issues
- Corrupt MANIFEST

**Solutions:**
- Check disk space: `df -h profiles/`
- Check permissions: `ls -la profiles/`
- Check MANIFEST: `cat profiles/MANIFEST.json | jq '.'`
- Restore from backup if needed: `cp profiles/MANIFEST.json.bak profiles/MANIFEST.json`

### Slow Startup

**Symptom:** Startup replay takes > 100 ms

**Causes:**
- Too many WAL segments to replay
- Large segment files
- Slow disk I/O

**Solutions:**
- Check segment count: `ls profiles/wal/ | wc -l`
- Check segment sizes: `ls -lh profiles/wal/`
- Verify snapshot is being written regularly
- Check disk I/O: `iostat -x 1`

### Lock File Issues

**Symptom:** Cannot start WAL writer (lock held)

**Causes:**
- Previous process didn't release lock
- Stale lock file from crashed process

**Solutions:**
- Check for running processes: `ps aux | grep tester`
- Remove stale lock (if safe): `rm profiles/wal/.lock`
- **Warning:** Only remove lock if you're certain no other process is using it

## Maintenance

### Regular Checks

**Daily:**
- Monitor queue drop rate
- Check rejection rate
- Verify coordinator is running

**Weekly:**
- Review WAL segment count
- Check storage usage
- Verify snapshot frequency

**Monthly:**
- Review performance metrics
- Check for disk space trends
- Validate crash recovery procedures

### Backup Procedures

**MANIFEST Backup:**
- Automatic backup created before every update
- Location: `profiles/MANIFEST.json.bak`
- Manual backup: `cp profiles/MANIFEST.json profiles/MANIFEST.json.backup`

**Snapshot Backup:**
- Snapshot includes SHA-256 checksum
- Manual backup: `cp profiles/test_stats.snapshot.json.gz profiles/backup/`

**WAL Segments:**
- Segments are retained until snapshot includes them
- Manual backup: `tar -czf wal-backup-$(date +%Y%m%d).tar.gz profiles/wal/`

### Cleanup Procedures

**Old Segments:**
- Automatically cleaned up after snapshot
- Manual cleanup (if needed): Check `manifest.getSegmentsToCleanup()`

**Legacy Files:**
- Legacy WAL files renamed to `.legacy` after replay
- Safe to delete after verification: `rm profiles/test_stats.jl.gz.legacy`

---

**See also:** [ARCHITECTURE.md](ARCHITECTURE.md) for design and implementation details.

