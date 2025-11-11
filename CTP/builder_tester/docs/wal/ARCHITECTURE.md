# WAL Architecture & Design

## Overview

Production-grade WAL (Write-Ahead Log) architecture with crash-safety guarantees, bounded storage, and comprehensive validation. This replaces the naive unbounded WAL with a robust segmented design that includes:

- **Segmented WAL** with automatic rotation (time and size-based)
- **MANIFEST coordination** for crash-safe state management
- **Single-threaded snapshot coordinator** enforcing correct write order
- **Process-level locking** preventing multi-process corruption
- **Comprehensive validation** filtering invalid observations
- **Legacy compatibility** for smooth migration

## Key Improvements

### Previous Design Problems

1. **Unbounded Growth:** WAL file grew forever, never truncated
2. **O(history) Replay:** Read entire file on every startup
3. **No Crash Safety:** Truncation could lose data
4. **No Validation:** Stored invalid observations (metrics=-1)
5. **File Contention:** Multiple threads writing to same file
6. **Gzip Corruption:** Mid-stream corruption made entire file unreadable
7. **No Process Lock:** Multiple processes could corrupt WAL

### New Design Solutions

1. **Segmented WAL:** Files rotate every 5 min or 4 MB
2. **Bounded Replay:** Only replay segments newer than snapshot
3. **Crash-Safe:** Write-order discipline with MANIFEST + fsync + directory fsync
4. **Comprehensive Validation:** 10 categories of checks
5. **Single Writer Thread:** Bounded queue prevents contention
6. **Plaintext JSONL:** Crash-resilient (no gzip cascade failures)
7. **Process Lock:** Advisory file locking prevents multi-process writes

## Architecture Components

### 0. Request Journal & Latest Export (Human-Readable Exports)

**Files:** `RequestJournal.java` (125 lines), integrated into `TestStatsStore.java` and `Tester.java`

**Purpose:** Human-readable per-request tracking and cross-node statistics import

**Request Journal:**
- Format: `profiles/requests/req_YYYYMMDD_HHMMSS_xxxx.json`
- Records all test observations for a single tester run
- Thread-safe buffered writes, atomic flush on shutdown
- No config required (works by convention)

**Latest Export:**
- Format: `profiles/latest.json.gz`
- Written after each snapshot cycle (alongside MANIFEST update)
- Contains latest statistics for all tests (importable format)
- Enables fresh node bootstrap without full WAL replay

**Import Flow:**
- Copy `latest.json.gz` to new node's profiles directory
- On startup, if statsMap is empty, automatically imports
- No config flags needed (presence-based detection)

### 1. MANIFEST File (Single Source of Truth)

**File:** `WALManifest.java` (341 lines)

**Purpose:** Coordinates snapshot and WAL state for crash recovery

**Schema:**
```json
{
  "v": 1,
  "snapshot": {
    "path": "test_stats.snapshot.json.gz",
    "created_at": "2025-11-11T10:30:00Z",
    "sha256": "abc123...",
    "includes_up_to_wal": "test_stats-20251111-103000-000001.jl"
  },
  "open_wal": "test_stats-20251111-103500-000002.jl",
  "retained": [
    "test_stats-20251111-103500-000002.jl"
  ]
}
```

**Key Methods:**
- `load()` - Loads MANIFEST with backup fallback
- `updateAfterSnapshot()` - Crash-safe update (fsync + atomic rename + dir fsync)
- `getSegmentsToReplay()` - Returns segments newer than snapshot
- `getSegmentsToCleanup()` - Returns old segments safe to delete
- `removeSegments()` - Removes segments and persists changes

**Crash-Safety:**
1. Create backup: `MANIFEST.json.bak` ← current `MANIFEST.json`
2. Write to `MANIFEST.tmp`
3. Fsync temp file (FileChannel.force(true))
4. Atomic rename: `MANIFEST.tmp` → `MANIFEST.json`
5. **CRITICAL:** Fsync parent directory (ensures rename is durable)

**Backup & Recovery:**
- SHA-256 checksum stored for snapshot integrity
- Automatic fallback to `.bak` if primary corrupt
- Verifies checksum on load

### 2. WAL Segment Writer (Bounded Queue + Rotation)

**File:** `WALSegmentWriter.java` (395 lines)

**Purpose:** Single-threaded writer with automatic rotation and crash-safety

**Features:**
- **Bounded Queue:** 10,000 observations capacity
- **Drop Policy:** Drop oldest on full (prevents test blocking)
- **Process Lock:** Exclusive advisory lock prevents multi-process writes
- **Plaintext JSONL:** No gzip (crash-resilient)
- **Monotonic Sequence:** 64-bit counter prevents clock skew collisions
- **Rotation Triggers:**
  - Time: Every 5 minutes
  - Size: Every 4 MB (whichever first)
- **Fsync Before Close:** Ensures durability
- **Metrics:** Tracks written/dropped/rotated counts

**Snapshot Coordinator:**
- Single-threaded executor enforces atomic execution order
- Execution sequence (all in one thread):
  1. Write snapshot (fsync file → rename → fsync dir)
  2. Rotate WAL (get closed segment name - guaranteed durable)
  3. Update MANIFEST (fsync file → rename → fsync dir)
  4. Cleanup old segments (only after MANIFEST update succeeds)
- Error handling: If any step fails, cleanup is skipped (retry next cycle)
- Shutdown: Coordinator finishes before writer stops, preventing races

**Replay Logic:**
- Iterates segments from `manifest.getSegmentsToReplay()` (newer than `includes_up_to_wal`)
- Partial-line-safe: ignores parse failures only on final line (tolerates truncated writes)
- No re-persist: `recordObservation()` only updates in-memory stats during replay
- Legacy compatibility: Detects and replays old gzipped WAL files, renames to `.legacy` after replay

**Lifecycle:**
```java
// Start
writer.start();  // Acquires lock, opens initial segment

// Runtime
writer.append(obs);  // Non-blocking (queued)

// Background thread
while (running) {
    obs = queue.poll(1, SECONDS);
    if (obs != null) writeObservation(obs);
    if (shouldRotate()) rotateSegment();
}

// Stop
writer.stop();  // Flushes queue, closes segment, releases lock
```

**Segment Naming:**
- Format: `test_stats-YYYYMMDD-HHMMSS-SEQ.jl`
- Example: `test_stats-20251111-103500-000001.jl`
- Sequence: 64-bit counter (last 6 digits shown for readability)
- Uniqueness: Timestamp + sequence ensures no collisions

**Rotation Logic:**
```java
private synchronized String rotateSegment() {
    // 1. Capture current segment name (before close)
    String closedSegment = currentSegmentName;
    
    // 2. Close current segment (flush + fsync + close)
    closeCurrentSegment();
    
    // 3. Add closed segment to manifest's retained list
    manifest.addRetainedSegment(closedSegment);
    
    // 4. Generate and open new segment
    String newSegment = generateSegmentName();
    openSegment(newSegment);
    
    // 5. Update manifest with new open segment
    manifest.setOpenWalSegment(newSegment);
    
    // 6. Return closed segment name (guaranteed durable)
    return closedSegment;
}
```

**Flush Order (Critical):**
```java
private void closeCurrentSegment() {
    currentWriter.flush();                    // 1. Flush buffered data
    currentFileStream.getChannel().force(true); // 2. Fsync to disk
    currentWriter.close();                    // 3. Close streams
}
```

### 3. Observation Validator (10 Categories)

**File:** `ObservationValidator.java` (254 lines)

**Purpose:** Comprehensive validation before persistence

**Validation Checks:**

1. **Metrics Complete:** `metrics_complete == true`
2. **Schema Version:** `v == 1`
3. **Duration:** `0 < duration_ms <= 4 hours`
4. **CPU Mean:** `0 <= cpu_pct_mean <= cores×100×1.25`
5. **CPU Peak:** `0 < cpu_pct_peak <= cores×100×2.0` (spike tolerance)
6. **Memory Mean:** `0 <= mem_mb_mean <= total_mem×1.05`
7. **Memory Peak:** `0 < mem_mb_peak <= total_mem×1.05`
8. **I/O Mean:** `0 <= io_mb_s_mean <= 10000 MB/s`
9. **IOPS:** `0 <= iops_mean <= 1M`
10. **Network Mean:** `0 <= net_mb_s_mean <= 10000 MB/s`

**Rejection Tracking:**
```java
ValidationMetrics metrics = validator.getMetrics();
// accepted=950, rejected=50 (5.0%),
// reasons: incomplete=40, cpu=5, mem=3, io=2
```

**Example Usage:**
```java
ObservationValidator validator = new ObservationValidator(8, 16384);

if (validator.isValid(observation)) {
    walWriter.append(observation);  // Persist
} else {
    // Rejected - logged with reason
}
```

### 4. WAL Utilities (Crash-Safe Operations)

**File:** `WALUtils.java` (267 lines)

**Purpose:** Core crash-safe utility methods

**Key Methods:**

- **`fsyncDirectory(Path dir)`** - Ensures directory metadata durability
  - Opens directory as FileChannel
  - Calls `channel.force(true)`
  - Catches `UnsupportedOperationException | IOException` for portability
  - Graceful degradation on unsupported filesystems

- **`computeSHA256(Path path)`** - File integrity verification
  - Computes SHA-256 checksum
  - Used for snapshot integrity checks

- **`acquireExclusiveLock(Path lockFile)`** - Advisory file locking
  - Returns FileChannel with exclusive lock
  - Lock held for process lifetime
  - Automatically released on process exit

- **`atomicWrite(Path target, String content)`** - Atomic write with fsync
  - Write to temp file
  - Fsync temp file (FileChannel.force(true))
  - Atomic rename
  - Fsync parent directory

- **`generateSegmentName(Instant timestamp, long sequence)`** - Monotonic naming
  - Format: `test_stats-YYYYMMDD-HHMMSS-SEQ.jl`
  - Uses full 64-bit sequence internally
  - Last 6 digits shown for readability

### 5. TestStatsStore (Orchestration & Coordinator)

**File:** `TestStatsStore.java` (504 lines)

**Purpose:** Orchestrates snapshot and WAL persistence with single-threaded coordinator

**Key Changes:**

**Constructor:**
```java
public TestStatsStore(Path profilesDir, long snapshotIntervalSeconds,
                     WALSegmentWriter walWriter, WALManifest manifest, Path walDir)
```

**Single-Threaded Snapshot Coordinator:**
```java
private void coordinatedSnapshot() {
    // 1) Write snapshot (fsync file → rename → fsync dir)
    Path snapshotPath = writeSnapshotInternal();
    if (snapshotPath == null) {
        return; // Skip if snapshot write failed
    }

    // 2) Rotate WAL; get closed segment (durable)
    String closedSegment = walWriter.rotateNow();
    String newOpenWal = walWriter.getCurrentSegmentName();

    // 3) Update MANIFEST (fsync file → rename → fsync dir)
    manifest.updateAfterSnapshot(
            snapshotPath.getFileName().toString(),
            closedSegment,
            newOpenWal
    );

    // 4) Cleanup old segments (post-manifest)
    List<String> toCleanup = manifest.getSegmentsToCleanup();
    for (String segmentName : toCleanup) {
        Files.deleteIfExists(walDir.resolve(segmentName));
    }
    manifest.removeSegments(toCleanup); // Persists changes
}
```

**Execution Order (Enforced):**
1. Write snapshot (fsync file → rename → fsync dir)
2. Rotate WAL (get closed segment name - guaranteed durable)
3. Update MANIFEST (fsync file → rename → fsync dir)
4. Write latest.json.gz (importable statistics export)
5. Cleanup old segments (only after all above succeed)

**Replay via MANIFEST:**
```java
private void replayWAL() {
    List<String> segments = manifest.getSegmentsToReplay();
    
    if (segments == null || segments.isEmpty()) {
        // Legacy fallback: replay old single WAL file once
        if (Files.exists(walPath)) {
            replaySegment(walPath);
            // Rename to .legacy to prevent double-replay
            Files.move(walPath, walPath.resolveSibling(walPath.getFileName() + ".legacy"));
        }
        return;
    }

    // Replay each segment from MANIFEST
    for (String segmentName : segments) {
        Path segmentPath = walDir.resolve(segmentName);
        replaySegment(segmentPath);
    }
}
```

**Partial-Line-Safe Replay:**
```java
private ReplayResult replaySegment(Path segmentPath) {
    // Detect gzip (legacy format)
    boolean isGzipped = segmentPath.getFileName().toString().endsWith(".gz");
    
    try (InputStream in = Files.newInputStream(segmentPath);
         InputStream wrapped = isGzipped ? new GZIPInputStream(in) : in;
         BufferedReader reader = new BufferedReader(new InputStreamReader(wrapped, UTF_8))) {
        
        List<String> lines = new ArrayList<>();
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }

        for (int i = 0; i < lines.size(); i++) {
            boolean isLastLine = (i == lines.size() - 1);
            try {
                // Parse and replay observation
                recordObservation(obs); // In-memory only, no re-persist
            } catch (Exception e) {
                // CRITICAL: Only ignore parse failures on final line (truncated)
                if (isLastLine) {
                    break; // Stop at partial line
                }
                // Non-final lines: log and continue
            }
        }
    }
}
```

**Shutdown Ordering:**
```java
public void stop() {
    snapshotCoordinator.shutdown();
    
    try {
        // Force final snapshot before shutdown
        coordinatedSnapshot();
        
        // Wait for coordinator to finish
        if (!snapshotCoordinator.awaitTermination(10, TimeUnit.SECONDS)) {
            snapshotCoordinator.shutdownNow();
        }
    } catch (InterruptedException e) {
        snapshotCoordinator.shutdownNow();
        Thread.currentThread().interrupt();
    }
    
    // Coordinator is now stopped - safe to stop writer
}
```

**lastSnapshotTime Management:**
- **On Startup:** Set from loaded snapshot timestamp, or EPOCH if no snapshot
- **On Write:** Set to `Instant.now()` after successful rename+dir fsync
- **Prevents:** Reprocessing records on next restart

## Crash-Safety Guarantees

### Scenario 1: Crash During Snapshot Write

**State:** Snapshot.tmp exists, MANIFEST not updated

**Recovery:**
1. Load old MANIFEST (points to old snapshot + WAL)
2. Load old snapshot
3. Replay WAL (includes recent observations)
4. **Result:** No data loss

### Scenario 2: Crash After Snapshot, Before MANIFEST Update

**State:** New snapshot.json.gz exists, MANIFEST.tmp exists

**Recovery:**
1. Load old MANIFEST (MANIFEST.tmp ignored - not atomic)
2. Load old snapshot
3. Replay WAL
4. **Result:** No data loss (duplicate WAL replay is safe)

### Scenario 3: Crash After MANIFEST Update

**State:** New MANIFEST.json points to new snapshot

**Recovery:**
1. Load new MANIFEST
2. Load new snapshot
3. Replay only new WAL segments (newer than `includes_up_to_wal`)
4. **Result:** Correct state

### Scenario 4: Crash During WAL Write

**State:** WAL segment partially written (plaintext, not gzip)

**Recovery:**
1. Load MANIFEST + snapshot
2. Replay WAL segments
3. Parse error on final line → skip line, continue
4. **Result:** Lose at most one observation (best-effort tail)

**Mitigation:** Fsync before segment close minimizes risk

### Scenario 5: Corrupt MANIFEST

**State:** MANIFEST.json is corrupted or missing

**Recovery:**
1. Attempt to load MANIFEST.json
2. If corrupt, fall back to MANIFEST.json.bak
3. If both missing, create fresh MANIFEST
4. **Result:** Node not bricked

### Scenario 6: Power Loss During Rename

**State:** Atomic rename in progress

**Recovery:**
- **Without directory fsync:** Rename may not be durable (data loss risk)
- **With directory fsync:** Rename is guaranteed durable
- **Result:** No data loss (directory fsync ensures rename survives power loss)

## Production Hardening Status

### All Critical Fixes Implemented (7/7)

1. **Directory fsync after atomic renames**
   - Implemented in `WALUtils.fsyncDirectory()`
   - Called after all atomic renames (MANIFEST, snapshot)
   - Ensures rename operations survive power loss

2. **MANIFEST backup + SHA-256 checksums**
   - Backup created before every update
   - SHA-256 checksum stored for snapshot
   - Automatic fallback to `.bak` if primary corrupt

3. **Plaintext WAL (not gzipped)**
   - Switched from gzipped JSONL to plaintext JSONL
   - Prevents cascade failures from mid-stream corruption
   - Single torn write only affects one observation

4. **Process lock for single-writer**
   - Exclusive advisory lock acquired on startup
   - Lock held for process lifetime
   - Prevents multi-process WAL corruption

5. **Monotonic sequence in segment names**
   - 64-bit counter prevents clock skew collisions
   - Format: `test_stats-YYYYMMDD-HHMMSS-SEQ.jl`
   - Timestamp + sequence ensures uniqueness

6. **Single-thread snapshot coordinator**
   - Enforces correct order: snapshot → rotate → MANIFEST → cleanup
   - All steps in one synchronized flow
   - Error handling: cleanup skipped if any step fails

7. **MANIFEST-based replay**
   - Replays only segments newer than snapshot
   - Iterates `manifest.getSegmentsToReplay()`
   - Legacy file support with gzip detection

### Final Production Fixes Applied

1. **Legacy WAL gzip support**
   - Detects `.gz` extension in `replaySegment()`
   - Handles both plaintext and gzipped files
   - Legacy file renamed to `.legacy` after replay

2. **MANIFEST persistence after cleanup**
   - `removeSegments()` calls `persist()` internally
   - Changes durably persisted with FileChannel+force+dir fsync

3. **Configured interval usage**
   - Coordinator uses `snapshotIntervalSeconds` for both initial delay and period
   - No hard-coded values

4. **lastSnapshotTime correctness**
   - Set from snapshot timestamp on load
   - Set to EPOCH if no snapshot
   - Set to `Instant.now()` after successful write
   - Prevents reprocessing on restart

5. **Shutdown ordering**
   - Coordinator finishes before writer stops
   - Proper `awaitTermination()` with timeout
   - Final snapshot attempted before shutdown

6. **fsyncDirectory robustness**
   - Catches both `UnsupportedOperationException` and `IOException`
   - Graceful degradation on unsupported filesystems
   - Logs at FINE level, doesn't fail write paths

7. **Replay memory footprint (optional)**
   - Current: Reads all lines into memory
   - Acceptable for 4 MB rotation limit
   - Future: Streaming approach for very large segments

## Implementation Files

### New Files Created

1. **WALManifest.java** (341 lines) - MANIFEST coordination
2. **WALSegmentWriter.java** (395 lines) - Segmented WAL writer
3. **ObservationValidator.java** (254 lines) - Validation logic
4. **WALUtils.java** (267 lines) - Crash-safe utilities

### Files Updated

1. **TestStatsStore.java** (580+ lines) - Coordinator, replay logic, latest export/import
2. **Tester.java** - Component creation, lifecycle, request journal integration
3. **TestStats.java** - Added toLatestJSON() and fromLatestJSON() for import/export
4. **TestObservation.java** - Added getTsIso() helper method

## Integration

### Tester.java Integration

**Component Creation:**
```java
Path walDir = profilesDir.resolve("wal");
WALManifest manifest = new WALManifest(profilesDir);
WALSegmentWriter walWriter = new WALSegmentWriter(profilesDir, manifest);
TestStatsStore store = new TestStatsStore(profilesDir, snapshotIntervalSeconds, 
                                          walWriter, manifest, walDir);
```

**Start Order:**
```java
public void start() {
    // 1. Start WAL writer first (acquires lock, opens segment)
    walWriter.start();
    
    // 2. Start TestStatsStore (loads snapshot, replays WAL, starts coordinator)
    testStatsStore.start();
    
    // 3. Start HTTP server
    server.start();
}
```

**Stop Order:**
```java
public void stop() {
    // 1. Stop TestStatsStore (writes final snapshot, stops coordinator)
    testStatsStore.stop();
    
    // 2. Stop WAL writer (releases lock, closes segment)
    walWriter.stop();
    
    // 3. Stop HTTP server
    server.stop(0);
}
```

---

**See also:** [OPERATIONS.md](OPERATIONS.md) for deployment, monitoring, and operations guidance.

