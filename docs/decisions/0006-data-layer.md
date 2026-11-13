# ADR 0006: Data layer (Room and DataStore)

Date: 2026-11-13 · Status: proposed (please review)

Build-order step 5a (`core-data`). The CLAUDE.md build order has no separate step for `core-data`, but background
sync (step 5) cannot run without stored pairs and base snapshots, so the data layer was built first, as 5a.

## Schema (Room, version 1; schema exported to `core-data/schemas`)
- `pairs`:
  - the pair's settings and its user pause flag;
  - its status (IDLE, NEEDS_ATTENTION, WAITING_FOR_CONFIRMATION, ERROR) with a detail text;
  - `lastSyncAt`;
  - `hasBase`, which tells "no base" (rule 7: merge only) apart from "empty base".
- `base_entries`: the base snapshot, one row per path.
- `activity`: the activity log (FR-M2), indexed by pair and time.
- `conflicts` and `skipped`: the latest run's conflicts and left-alone files (Google Docs, FR-S12). Both are
  **replaced on every run**, so they always show the current state instead of a growing history.

Removing a pair cascades to its rows; files on either side are never touched (PRD 3.8).

## Decisions
1. **Rule 2 (echo suppression):** `RoomBaseStore.commit` applies an action's base updates and sets `hasBase` in
   **one Room transaction**. Within one commit, a later update to the same path wins.
2. **Relink drops the base** (PRD 3.8 "recreate"): when a pair is pointed at another Drive folder, its next run
   merges and can never delete anything.
3. **Activity log noise:**
   - Every done transfer, move, folder create, trash, recycle, conflict and failure is logged.
   - Re-planned and blocked actions are not, because the next run retries them.
   - A pause or a "waiting for confirmation" is logged **once, when the status changes**, so a paused pair does
     not add a row every 30 minutes.
4. **Status after a run:** IDLE, or ERROR when any file failed (those files are retried next run). Run-level
   failures, such as no network, are logged; ones the user must fix, such as revoked auth or a full Drive, set
   NEEDS_ATTENTION (FR-A4).
5. **Settings (DataStore):**
   - account e-mail and the Drive Sync root id;
   - sync interval: default 30 min, clamped to 15 min – 24 h (WorkManager's minimum is 15);
   - Wi-Fi only and charging only;
   - device name, used in conflict-copy names;
   - log retention: default 30 days.
   - Sign-out clears them all (FR-A5).
6. **Tests:** Room is tested with Robolectric (JUnit 4 through the vintage engine), and DataStore on the plain JVM.
   - The Robolectric JVM is capped at 1 GB because of this machine's RAM.
   - It needs `--add-opens java.io` and `--add-exports jdk.internal.access` on JDK 21.

## Deferred
- **The stored name-mapping layer (PRD 6.2)** gets its own step. It changes how a phone path is matched to a
  Drive path, so it deserves a focused review. Until then, unsafe names fail per file (ADR 0005).
- **The Drive Changes API token** (PRD 3.7), together with the Changes API work.
