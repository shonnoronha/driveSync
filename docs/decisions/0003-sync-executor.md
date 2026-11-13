# ADR 0003: Sync executor, store interfaces and fakes

Date: 2026-11-13 · Status: proposed (please review)

Build-order step 2b. `SyncEngine` runs one pair: load base → scan both sides → delete leftover temp files →
lazy hashing → `SyncPlanner.plan` → `SyncExecutor.execute`. A per-pair mutex allows one run at a time.

## Store interfaces (core-sync/store)
- `RemoteStore`, `LocalStore`, `BaseStore` are **per pair** and use paths relative to the pair root.
  The Drive client (core-drive), the SAF store (core-local-storage) and Room (core-data) implement them later.
- `AccessMode { APP_FOLDER, FULL_DRIVE }` sits on `RemoteStore` (PRD 3.8, 6.4). Only APP_FOLDER is built in v1.
- Streams are `java.io` `InputStream`/`OutputStream`, which keeps core-sync free of OkHttp/okio and Android.
- `RemoteStore.upload` takes a `verifyBeforeCommit` callback. The real Drive client calls it before sending the
  final resumable-upload chunk, so a file that changed mid-upload is never finalized (rule 5).

## Executor rules
- **Re-check (rule 4):** before every write, the executor fetches the current state of the file it is about to
  touch and compares it with the state the planner saw. Drive is compared by id + md5 (or version) + path;
  local files by size + mtime. If anything differs the result is `Replan`, nothing is written, and the next
  run re-plans the file (usually into a keep-both conflict).
- **Checkpointing / echo suppression (rule 2):** each completed action commits its base update immediately.
  The update uses the post-transfer state: the Drive md5/version that was returned and the new local
  size/mtime.
- **Downloads (rule 6):** stream into `.<name>.drivesync-part` → check size and MD5 → re-check the local
  target → rename. On failure the temp file is deleted, and the next run also deletes leftovers.
- **Overwriting an unsynced local edit** (REMOTE_WINS or NEWEST_WINS) first moves the old local file to the
  recycle folder.
- **Error isolation (FR-S9):** any exception fails only that action. Later actions whose paths overlap a
  failed path (same path, ancestor or descendant) are `Skipped`. A half-done keep-both therefore never
  overwrites anything, and a folder is never deleted after one of its children failed.
- **First run:** after a completed (not paused) run, an empty commit marks the base as existing. A partial
  base is safe: paths missing from it are treated as new, which never produces deletes.

## Known gaps
- Upload with LOCAL_WINS / NEWEST_WINS overwrites the Drive file. The old content survives only as a Drive
  revision, and Drive keeps those for about 30 days unless `keepForever` is set. Decide in core-drive.
- The local recycle folder grows without limit; retention is a settings feature (PRD 4.5).
