# ADR 0007: Background sync (WorkManager, triggers, notifications)

Date: 2026-11-13 · Status: proposed (please review)

Build-order step 5b (`core-work`, plus the app's WorkManager setup).

## Shape
- `PairSyncRunner`: one pair, start to finish. It builds the stores, runs the engine, records the result and
  maps errors. A lock per pair means the periodic run and a "sync now" never overlap (PRD 7).
- `SyncCoordinator`: the job of the one global worker (PRD 6.1). It syncs the due pairs **one after another**,
  sends notifications, and prunes the activity log according to retention.
- `SyncStores`, `SyncEnvironment` and `SyncNotifier`: small interfaces, so all of the above runs in tests against
  a real in-memory database and fake stores.
- `android/`: the Android layer.
  - One `SyncWorker` with three modes: all pairs, one pair, or the MediaStore trigger.
  - `SyncScheduler` (WorkManager), the notification channels, and `SyncStartup`, which keeps WorkManager in line
    with the settings and pairs.

## Triggers (PRD 3.4)
| Trigger | How |
| --- | --- |
| Periodic | One unique periodic job (UPDATE policy) using the interval and the Wi-Fi and charging rules. It also requires storage and battery not to be low. |
| Photo or video saved | A one-time job with MediaStore image and video content-URI triggers; it re-arms itself after each run. It syncs only pairs in DCIM, Pictures or Movies, and is armed only while such a pair exists. |
| Sync now, app opened | A one-time expedited job per pair (or for all), needing only a connection. The user asked, so the Wi-Fi and charging rules don't apply. |
| Delete confirmed (FR-S6) | The same job with `confirmDeletes`. It *replaces* a queued run, so the confirmation is never dropped. |
| Network back, charging, reboot | WorkManager constraints and persistence. |
| Retry | Up to 5 WorkManager retries (with backoff) when a run hit a temporary error. |
| Signed out | Every job is cancelled. Nothing runs before sign-in. |

## Decisions
1. **Stability check (PRD 3.6):** for media, the content trigger waits until MediaStore has been quiet for 30 s
   (2 min at most). For everything else, rule 5 applies: an upload is abandoned if the file changes mid-transfer
   and retried next run. A file that pauses mid-write can still be uploaded early, then uploaded again once
   finished. This wastes bandwidth but never loses data.
2. **Errors per run:**
   - Revoked sign-in or a full Drive → the pair needs attention and the user is notified (FR-A4).
   - Other I/O errors (network, server) → logged and retried quietly.
   - Per-file failures inside a run → ERROR status plus a "will be retried" notification.
3. **Notifications only on change:** a pair that stays paused does not notify every run. There are four
   channels: progress (low), needs attention, conflicts, and errors (low). Nothing is posted without the
   Android 13+ permission; asking for it belongs to the onboarding UI (step 6).
4. **Foreground (PRD 6.1):** the worker tries to become a `dataSync` foreground service. If Android 12+ refuses,
   it carries on as normal work. Progress is saved after every file, so being stopped after 10 minutes, or by
   Doze, loses nothing: the next run continues.
5. **Device name** for conflict copies: the user's setting, otherwise `Build.MODEL`.
6. **App:** `DriveSyncApplication` provides the Hilt worker factory and disables WorkManager's default
   initializer. `core-drive` now declares `INTERNET`; it was missing.

## Known gaps
- **A full Drive during an upload:** the engine reports it as a per-file failure, so uploads continue to fail
  file by file instead of stopping at once. PRD 6.3 asks for an immediate stop; that needs the executor to carry
  a typed error.
- **SAF `ContentObserver`** (PRD 3.6, item 1) is not used. The PRD lists it only as a bonus, and no timing
  target depends on it.
- **The Changes API** is not used yet: each run scans all of Drive. It is correct, but slower on large pairs.
- **Nothing has run on a device yet:** timing, Doze, OEM battery killers, foreground limits.
