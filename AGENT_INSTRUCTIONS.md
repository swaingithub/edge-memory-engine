# Edge Memory Engine - Coding Agent Specification

## 1. Project Goal & Scope
Build a privacy-preserving, on-device contextual memory engine for mobile devices.
- Ingestion is strictly text-only (Accessibility events, notifications, user notes).
- No image, video, camera, or gallery processing (Zero-media permission posture).
- All operations run fully offline: no network API calls for storage or vector search.

---

## 2. Core Architecture & Stack
- Storage: SQLCipher (SQLite with AES-256-CBC) configured in WAL mode (`PRAGMA journal_mode = WAL;`).
- Fast Search Filter: Cascaded 1-bit binary embeddings (512 dimensions packed into 64-byte BLOBs).
- Keyword Filter: SQLite FTS5 virtual table for exact token matches (PNR, codes, proper nouns).
- Native Kernel: C/C++ compiled via Android NDK with ARM NEON SIMD (`EOR` + `CNT` via `__builtin_popcountll`).
- Local SLM: 1B–2B parameter model (e.g. Llama-3.2-1B / Qwen2.5-1.5B via llama.cpp / MediaPipe).

---

## 3. Storage Principles & Hard Invariants
1. Append-Only Event Sourcing:
   - NEVER overwrite or execute `UPDATE` on existing event rows.
   - All state shifts (reschedules, cancellations, changes) MUST be appended as new rows with updated timestamps.
2. Two-Tier Retrieval (Cascade):
   - Stage 1 (Fast Filter): Use 1-bit Hamming distance (`XOR` + `POPCNT`) to extract Top-50 candidates in < 2 ms.
   - Stage 2 (Fine Resolution): Join with SQLite FTS5 BM25 matches; pass the resulting top 10–20 plain-text rows directly to the SLM prompt. Do NOT maintain massive FP32 vector tables in RAM.
3. Entity Linking:
   - Every event must belong to an `entity_urn` (e.g., `travel:train:kanpur_trip`).
   - Chronological queries MUST fetch all events for that `entity_urn` ordered by `timestamp ASC`.

---

## 4. SQLite Schema Reference

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

-- Append-Only Event Store
CREATE TABLE IF NOT EXISTS event_log (
    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_urn TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    action TEXT NOT NULL,
    source_app TEXT NOT NULL,
    raw_text TEXT NOT NULL,
    binary_embedding BLOB NOT NULL -- 64 bytes (512 bits)
);

CREATE INDEX IF NOT EXISTS idx_entity_time ON event_log(entity_urn, timestamp ASC);
CREATE INDEX IF NOT EXISTS idx_time ON event_log(timestamp DESC);

-- Exact Keyword Matching Table
CREATE VIRTUAL TABLE IF NOT EXISTS event_fts USING fts5(
    raw_text,
    content='event_log',
    content_rowid='event_id',
    tokenize='porter unicode61'
);

-- Triggers for FTS sync
CREATE TRIGGER IF NOT EXISTS trg_event_ai AFTER INSERT ON event_log BEGIN
    INSERT INTO event_fts(rowid, raw_text) VALUES (new.event_id, new.raw_text);
END;

-- L1 Daily Digest Table
CREATE TABLE IF NOT EXISTS daily_summaries (
    day_date TEXT PRIMARY KEY,
    summary_text TEXT NOT NULL,
    summary_embedding BLOB NOT NULL
);

```

---

## 5. Coding Conventions & Implementation Rules

### Native C/C++ (`cpp/hamming.cpp`)

* Must use ARM NEON intrinsics (`arm_neon.h`) for 64-byte chunks.
* Avoid dynamic allocations (`malloc`/`new`) inside the distance evaluation loop.

### Kotlin / Android Layer

* Target SDK: Modern Android with Kotlin Coroutines / Flow.
* Background Scheduling: Use `WorkManager` with `setRequiresCharging(true)` and `setRequiresDeviceIdle(true)` for SLM compaction jobs.
* Privacy & PII:
* Do NOT request `READ_MEDIA_IMAGES` or `READ_MEDIA_VIDEO`.
* Discard any `AccessibilityNodeInfo` where `isPassword == true` or class name matches `ImageView`/`VideoView`.
* Exclude camera and gallery package names via blocklist.



### Vector Quantization Rule

* Quantization logic: `float > 0.0f ? 1 : 0`.
* 512 dimensions pack into exactly 64 bytes (`ByteArray(64)`).
* Little-endian/Big-endian bit-order must remain consistent between Kotlin `BitPacker` and C++ SIMD loaders.

---

## 6. SLM Timeline Prompt Pattern

When prompting the local SLM, always structure context chronologically so it explains state changes accurately:

```text
<|im_start|>system
You are an on-device personal memory assistant. Use ONLY the historical event timeline below.
If an event was modified, rescheduled, or cancelled, state the complete progression (both original and final states).

TIMELINE:
- [2026-09-10 10:15] BOOKED: Train Hyd-Kanpur on 2026-09-23
- [2026-09-13 16:40] RESCHEDULED: Train Hyd-Kanpur to 2026-09-30
<|im_end|>
<|im_start|>user
When is my train to Kanpur?
<|im_end|>

```
