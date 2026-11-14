# Project Requirements Document: Open-Source Android Folder Sync for Google Drive

*Working title: **DriveSync** · Version 0.3 (draft) · Platform: Android · License: open source*

## 1. Overview

Google Drive on Android lets users upload and browse files but has no built-in way to keep a chosen phone folder continuously in sync with a Drive folder. This project is a free, open-source, Material Design Android app that does exactly that.

Users pick any number of local folders on their phone, and the app mirrors each one into its own subfolder inside a single Drive folder called Drive Sync, keeping both sides in sync automatically in the background, in both directions.

### 1.1 Goals

- Two-way, automatic sync between user-selected local folders and user-selected Drive folders.
- Multiple independent folder pairs, each with its own settings.
- Never lose data: safe conflict handling, Drive trash instead of permanent delete, protection against mass deletion.
- Battery- and data-friendly, and resilient to Android background restrictions.
- Modern, polished Material 3 UI using current Android tooling.
- Fully open source, privacy-first: no backend, no analytics, no ads. Data goes only between the phone and the user's own Google Drive.

### 1.2 Non-goals (v1)

- Syncing with other cloud providers (Dropbox, OneDrive). The architecture should allow it later.
- Real-time collaborative editing or version history UI.
- Sharing files or managing Drive permissions.
- iOS or desktop clients.
- End-to-end encrypted storage (candidate for v2).

### 1.3 Target users

- People who want phone folders (Camera, Documents, Obsidian vaults, Downloads, app exports, notes) mirrored to Drive without manual uploads.
- Privacy-conscious and open-source-minded users who want a transparent sync tool.
- Users who want a Drive folder available offline and updated on the phone.

## 2. Recommended technology stack

I am assuming you are new to the Android ecosystem, so here is the modern default and the reason for each choice.

| Area | Choice | Why |
| --- | --- | --- |
| Language | **Kotlin** | Google's official language for Android; coroutines and Flow fit sync work well |
| UI | **Jetpack Compose + Material 3** | Modern declarative UI; dynamic color (Material You), dark mode, adaptive layouts. Evaluate Material 3 Expressive components as they stabilize |
| Architecture | MVVM with unidirectional data flow (ViewModel + StateFlow), repository pattern | Google's recommended architecture; testable |
| Dependency injection | **Hilt** | Standard; integrates with WorkManager and ViewModels |
| Local database | **Room** | Stores sync state, file index, activity log |
| Settings | **Jetpack DataStore** | Replaces SharedPreferences |
| Background work | **WorkManager** | Guaranteed, constraint-aware, survives reboot and process death |
| Concurrency | Kotlin Coroutines + Flow | Structured concurrency and cancellation |
| Networking | **Drive REST API v3** via OkHttp + Retrofit with kotlinx.serialization (decided) | Lighter and more controllable than the heavy Google API Java client; full control of resumable uploads |
| Auth | **Google Identity Services**: Credential Manager (sign-in) + `AuthorizationClient` (OAuth scopes) | The older `GoogleSignIn` API is deprecated |
| Local file access | **Storage Access Framework (SAF)** with `ACTION_OPEN_DOCUMENT_TREE` | Required by scoped storage; no broad storage permission needed |
| Navigation | Navigation Compose (type-safe routes) | Standard |
| Build | Gradle Kotlin DSL, version catalog (`libs.versions.toml`), R8 | Standard modern setup |
| Quality | Detekt, ktlint, JUnit5/Kotest, MockK, Turbine, Compose UI tests, Robolectric | Test culture for an OSS project |
| CI/CD | GitHub Actions, Dependabot or Renovate, Fastlane metadata | Automated builds and F-Droid/Play friendly |

**SDK targets:** `minSdk` 26 (Android 8.0) is a reasonable floor; set `targetSdk` and `compileSdk` to the latest stable Android release at the start of development and keep them current, since Play Store requires it.

**Open-source distribution note:** Google Identity Services depends on Google Play Services, which F-Droid-style builds usually avoid. Decision for v1: option (a), distributed through GitHub Releases and Google Play; an AppAuth-based F-Droid flavor comes later. The options were: (a) Play Services-based auth (simplest, Play Store friendly), or (b) a Play-Services-free OAuth flow using the **AppAuth** library, which makes the app F-Droid compatible. A pluggable `AuthProvider` interface supports both.

## 3. Core concept: how sync works

### 3.1 Folder pair

A **sync pair** links one local folder (a SAF tree URI the user picked) to one Drive folder (a Drive folder ID: by default a subfolder the app creates inside its root Drive Sync folder, see 3.8). Each pair has its own settings and its own sync state.

### 3.2 Why a plain "last modified wins" approach is not enough

Two-way sync needs to know what changed **since the last successful sync** on each side. So the app stores a **snapshot** (the "base") of every file as it was at last sync, and performs a **three-way comparison**: local now vs. remote now vs. base.

For each file, the stored record includes:

- Relative path, local document URI, Drive file ID
- Local size, local last-modified time, local content hash (computed lazily, only when needed)
- Drive `md5Checksum`, `modifiedTime`, `version`, `size`, parent ID
- Last-synced state and timestamp, per-file error/retry info

### 3.3 Decision matrix (per file)

| Local vs. base | Remote vs. base | Action |
| --- | --- | --- |
| Unchanged | Unchanged | Nothing |
| New / modified | Unchanged | Upload |
| Unchanged | New / modified | Download |
| Deleted | Unchanged | Trash on Drive (subject to delete policy) |
| Unchanged | Deleted / trashed | Delete locally (subject to delete policy) |
| Modified | Modified (different content) | **Conflict** (see 3.5) |
| Modified | Deleted | Conflict: default is to keep the modified file and re-upload |
| Deleted | Modified | Conflict: default is to keep the remote file and re-download |
| Both new, same name, same hash |  | Link them, no transfer |
| Both new, same name, different hash |  | Conflict |

Content equality is decided by comparing the local MD5 with Drive's `md5Checksum` (Drive provides it for binary files), avoiding needless transfers when only timestamps differ.

### 3.4 Triggers: when should a sync run?

The user's example (a change inside the folder) is trigger #1. Full list:

**A. Local change triggers**

1. **File created, modified, renamed, moved or deleted in a watched folder.** See 3.6 for how to detect this on modern Android.
2. **Initial sync** when a pair is first created (full scan, then reconcile).
3. **Folder permission regained or changed** (user re-grants SAF access after it was revoked).

**B. Remote change triggers**

4. **Changes on Drive** (file added, edited, moved, trashed from the web, another device, or someone else). Detected via the Drive **Changes API** (`changes.getStartPageToken` then `changes.list`), polled periodically, since push notifications need a backend server (see 3.7).
5. **Target Drive folder deleted, renamed, moved or access revoked** (detect and surface as pair "needs attention").

**C. Schedule and system triggers**

6. **Periodic reconciliation** (default every 15 to 60 minutes; Android's minimum for periodic WorkManager is 15 min). This is the safety net that catches anything the live triggers missed.
7. **Network restored** or switched to an allowed network type (for example Wi-Fi only).
8. **Device charging** (for users who restrict sync to charging).
9. **Device reboot** (WorkManager reschedules; observers re-registered on boot).
10. **App update or app restart** (re-register observers, resume interrupted jobs).
11. **Retry after failure** (exponential backoff for transient errors, quota freed, auth restored).

**D. User-initiated triggers**

12. **App opened** (quick delta check).
13. **Sync now** button, pull-to-refresh, Quick Settings tile, home-screen widget.
14. **User edits pair settings** (for example changes direction, include/exclude rules).

**What "automatic" means in practice (v1 targets).** Android does not let an app reliably watch a SAF folder in the background, so the UI and README must set honest expectations:

| Change | Typical delay before sync starts |
| --- | --- |
| Photo or video saved to a media folder (DCIM, Pictures) | About 1 minute (MediaStore trigger); longer in Doze |
| Any other local change | Next periodic run (user setting, 15 to 60 min) or when the app is opened |
| Change on Drive made by the app on another phone | Next periodic run or when the app is opened |
| Sync now, pull-to-refresh | Immediately |

The SAF ContentObserver (3.6, item 1) is a bonus when it works; no timing target depends on it.

### 3.5 Conflict handling

Default policy: **keep both**. The losing version is saved next to the original with a suffix such as `report (conflict 2026-11-12 14-30 Pixel).docx`, then both are synced so nothing is lost. Per-pair alternatives: newest wins, local wins, Drive wins, or ask me. Pending conflicts appear in a dedicated Conflicts screen and a notification.

### 3.6 Detecting local changes on modern Android (the hard part)

Scoped storage (Android 10+, enforced fully on 11+) means apps do not get free file-path access, so no single API solves this. The plan is a **layered detection strategy**:

1. **`ContentObserver` on the SAF tree's children URI** (`DocumentsContract.buildChildDocumentsUriUsingTree`) while the app has a live process or foreground service. Works on many storage providers but is **not guaranteed**, so it is treated as an optimization only.
2. **WorkManager content-URI triggers** (`Constraints.Builder().addContentUriTrigger(...)`) for **MediaStore** URIs. Excellent for media folders (DCIM, Pictures) because Android wakes the app when MediaStore changes, with no running service.
3. **`FileObserver` (inotify)** only if the user opts into full file-path access (see below). It is not recursive by default (must register each subdirectory) and cannot observe SAF URIs.
4. **Periodic scan** via WorkManager. A cheap directory listing using `ContentResolver.query()` against `DocumentsContract` columns (document ID, display name, size, last modified, MIME type) and comparing against the stored index. Querying columns directly is much faster than the convenience `DocumentFile` class, which makes one binder call per property.
5. **On-open and manual scans** as above.

**Debounce and stability check:** when changes are detected, wait (for example 5 to 30 seconds) and confirm size and modified time have stopped changing before uploading, so half-written files (downloads, camera video, database writes) are not uploaded. Ignore known temp patterns (`.tmp`, `.crdownload`, `.part`, `~$*`, `.thumbnails`, `.trashed-*`).

**Optional file-path mode:** `MANAGE_EXTERNAL_STORAGE` ("All files access") gives real paths and `FileObserver`, but Google Play restricts it to a narrow set of app categories, so it must be an **opt-in power-user mode** that is likely only available in the open-source or F-Droid builds, not the Play build.

### 3.7 Detecting Drive changes

- Use the **Changes API**: store a `startPageToken` per Drive account; on each run call `changes.list`, filter to files under the paired Drive folders, then advance the token only after changes are successfully applied.
- Drive push notifications (`changes.watch`) require a public HTTPS endpoint, which conflicts with the "no backend" principle. v1 uses polling. A self-hostable relay could be an optional v2 idea.
- Drive folders can be nested; because the Changes API reports parent IDs, maintain a local folder-ID tree per pair to decide whether a changed file belongs to a synced subtree.

### 3.8 Drive folder model (v1)

In v1 every synced Drive folder lives inside one root folder that the app creates, named **Drive Sync**.

- **Root folder:** created in My Drive on first sign-in. The app tracks its Drive file ID, not its name, so the user may rename or move it in Drive.
- **One subfolder per pair:** adding a local folder creates a matching subfolder inside Drive Sync, named after the local folder. Drive allows duplicate names, so the app always tracks folders by ID and appends " (2)" on a name clash.
- **Nested folders:** local subfolders become nested Drive subfolders under the pair's subfolder.
- **Removing a pair:** stops syncing and never deletes data. The user explicitly chooses to keep the Drive subfolder or move it to trash.
- **Recovery:** if the root or a pair's subfolder is trashed or deleted in Drive, the pair shows "needs attention" with the choices recreate and re-upload, or unlink. The app never silently re-creates it.
- **Visibility limit (to verify):** with the narrow drive.file scope the app can see only files it created. Files uploaded by this app, including from the user's other devices running it, should be visible. Files added to the folder from the Drive website or other apps may not be. Phase 0 must confirm this behavior (section 11).
- **Future Advanced mode:** the sync engine talks to Drive only through a RemoteStore interface, and access is chosen by an AccessMode setting: APP\_FOLDER (v1, drive.file scope) or FULL\_DRIVE (future: full Drive scope, any existing Drive folder, user's own OAuth client ID). v1 ships only APP\_FOLDER, with no Drive folder browser in the UI.

### 3.9 Sync safety rules

The sync engine must enforce all of these rules; each closes a data-loss gap found in the v0.3 review (section 15).

- **Unavailable is not deleted.** If the local folder can't be read (SD card removed, permission revoked, folder moved) or the Drive folder is missing or trashed, the pair pauses with "needs attention". "Folder unreachable" or "folder suddenly empty" never turns into deletes.
- **Echo suppression.** After every upload or download, the resulting size, modified time and MD5 go into the base snapshot in the same database transaction, so the next scan does not see the engine's own write as a new change and send it back.
- **Never compare clocks across sides.** Phone and Drive clocks differ, and SAF cannot set a local file's last-modified time. Each side is compared only with its own values in the base snapshot. Only the optional "newest wins" conflict policy uses timestamps, and the UI says so.
- **Re-check before writing.** Just before overwriting a Drive file or replacing a local file, fetch its current state again. If it changed since the plan was made, re-plan that file (usually a conflict) instead of overwriting.
- **File changed during upload.** If the local size or modified time changes while uploading, abandon the upload and retry after the stability check.
- **No half-written files.** Downloads stream into a hidden temp name in the target folder (for example `.photo.jpg.drivesync-part`), are verified by size and MD5, then renamed with `DocumentsContract.renameDocument`. Scans ignore temp files and the next run deletes leftovers.
- **Missing base (first sync, reinstall, cleared app data).** With no base snapshot the engine merges: same path and MD5 → link; file on one side only → copy it over; same path, different content → keep-both conflict. A run without a base never deletes anything.
- **Direction semantics.** Upload-only is a backup: local deletes do not delete on Drive unless the user turns on "mirror deletions". Download-only is the reverse. Two-way propagates deletes through Drive trash and the local recycle folder.
- **Quick check, lazy hashing.** A local file counts as unchanged if size and modified time match the base; MD5 is computed only when they differ, so large folders scan quickly.
- **Rename and move detection.** Local SAF document IDs change on rename, so a rename looks like delete plus create. Within one scan, a deleted and a created file with the same size and MD5 are treated as a move, and the Drive file is renamed or re-parented instead of re-uploaded.

### 3.10 Reinstall, new phone and multiple devices

- **Find, don't duplicate.** The app tags its Drive folders with private `appProperties` (root: role=root; subfolders: role=pair plus the pair's UUID). On sign-in it searches for an existing tagged root before creating one, so a reinstall or a second phone reuses the same Drive Sync folder.
- **Reconnect existing pairs.** If Drive Sync already has subfolders, the add-pair flow offers "link to an existing Drive Sync subfolder" next to "create new". Linking runs the missing-base merge from 3.9.
- **Multiple phones.** Two phones can sync the same subfolder. Edits flow phone A → Drive → phone B on each phone's next run; simultaneous edits become keep-both conflicts.
- **Same OAuth client required.** Under drive.file, Drive files are visible only to the OAuth client that created them. Builds signed with a different key (forks, self-builds) need their own OAuth client and cannot see folders made by the official build. The README must say so.

## 4. Functional requirements

Priority: **M** = must (v1), **S** = should (v1 if time), **C** = could (later).

### 4.1 Account and authorization

| ID | Requirement | Pri |
| --- | --- | --- |
| FR-A1 | Sign in with a Google account using Credential Manager | M |
| FR-A2 | Request only the narrow drive.file scope via `AuthorizationClient`; handle consent UI and token refresh transparently | M |
| FR-A3 | Support **multiple Google accounts**, each pair bound to one account | C |
| FR-A4 | Detect revoked or expired authorization and show a clear re-authenticate prompt; pause affected pairs | M |
| FR-A5 | Sign out and delete all app data and cached tokens | M |
| FR-A6 | Not in v1: keep the code ready for a future Advanced mode (full Drive scope, user's own OAuth client ID; see 3.8 and 6.4) | C |

### 4.2 Folder pair management

| ID | Requirement | Pri |
| --- | --- | --- |
| FR-P1 | Create a pair: pick local folder (SAF picker), name the pair; the app creates its Drive subfolder inside Drive Sync automatically | M |
| FR-P2 | Unlimited pairs (practically bounded by Android's persisted-URI limit, see 6.2) | M |
| FR-P3 | Per-pair direction: **two-way (default)**, upload-only, download-only | M |
| FR-P4 | Per-pair options: include subfolders, Wi-Fi only, charging only, sync interval, delete policy, conflict policy | M |
| FR-P5 | Include/exclude rules (glob patterns, file types, max file size, skip hidden files) | S |
| FR-P6 | Pause, resume, edit and remove pairs. Removing a pair never deletes data on either side | M |
| FR-P7 | Prevent overlapping or nested pairs that would create sync loops (validate on creation) | M |
| FR-P8 | Detect pair problems (folder deleted or moved, permission lost, Drive folder trashed) and surface actions to fix | M |

### 4.3 Sync engine

| ID | Requirement | Pri |
| --- | --- | --- |
| FR-S1 | Three-way reconciliation per 3.2 and 3.3 | M |
| FR-S2 | Recursive sync of subfolders, creating folders on the opposite side | M |
| FR-S3 | **Resumable uploads** for files above a size threshold (Drive resumable protocol, chunk sizes in multiples of 256 KiB); persist the session URI so interrupted uploads resume | M |
| FR-S4 | Downloads to a temp file with **HTTP Range** resume, then atomic rename on success | M |
| FR-S5 | Rename and move detection using Drive file IDs and local content hash and size, to avoid delete-plus-reupload | S |
| FR-S6 | Deletion safety: deletes go to **Drive trash** and an optional local "recycle" folder; **mass-delete guard** (default: pause and ask if one run would delete more than 20 files and more than 10% of the pair; see also the unavailable-folder rule in 3.9) | M |
| FR-S7 | Conflict handling per 3.5 | M |
| FR-S8 | Parallel transfers with a configurable limit (default 2 to 3), fair across pairs | S |
| FR-S9 | Per-file error isolation: one failing file does not block the rest; retry with exponential backoff and jitter | M |
| FR-S10 | Idempotency: re-running after a crash or kill must not duplicate files or corrupt state | M |
| FR-S11 | Set Drive `modifiedTime` from the local file on upload. SAF cannot set a local file's last-modified time, so sync decisions never compare timestamps across sides (3.9) | S |
| FR-S12 | Skip Google-native files (Docs, Sheets, Slides) in v1 and list them in the activity log. Later: per-pair option to **skip**, **export** (docx/xlsx/pptx/pdf) one way, or keep a link file. They have no byte content or MD5 | M |
| FR-S13 | Handle Drive quirks: **duplicate file names in the same folder**, shortcuts, files shared with me, files with no parent | M |

### 4.4 Monitoring and feedback

| ID | Requirement | Pri |
| --- | --- | --- |
| FR-M1 | Home screen shows each pair with status (idle, syncing with progress, paused, error, needs attention), last sync time, pending counts | M |
| FR-M2 | Activity log per pair (uploaded, downloaded, deleted, conflicts, errors) with filtering, exportable for bug reports (paths can be redacted) | M |
| FR-M3 | Ongoing notification while a long sync runs; separate notification channels for errors, conflicts and completion summaries | M |
| FR-M4 | Quick Settings tile and home-screen widget (Glance) for status and sync-now | C |
| FR-M5 | Storage view: Drive quota usage and warning when Drive is nearly full | C |

### 4.5 Settings

Theme (system, light, dark, dynamic color toggle), default sync interval, network and battery rules, notification preferences, max concurrent transfers, log retention, battery-optimization help screen, and about, licenses and source-code link.

## 5. Non-functional requirements

| ID | Requirement |
| --- | --- |
| NFR-1 | **Reliability:** no data loss under crash, kill, network drop, reboot or full storage; all state changes are transactional (Room) and transfers are resumable |
| NFR-2 | **Battery:** no wakelocks held idle; prefer WorkManager and batched work; observers active only when useful; respect Doze and App Standby |
| NFR-3 | **Data usage:** Wi-Fi-only option; no re-uploading unchanged files; metadata-only checks where possible |
| NFR-4 | **Performance:** a pair with 50,000 files should index with a bounded memory footprint (streaming cursor, batched DB writes); UI stays at 60 fps |
| NFR-5 | **Privacy and security:** no analytics or tracking; no third-party servers; tokens managed by the OS-level authorization API (or Android Keystore-encrypted storage with AppAuth); logs never contain file contents; screen-reader and backup-exclusion rules for the DB; HTTPS only |
| NFR-6 | **Accessibility:** TalkBack labels, minimum 48 dp targets, dynamic font scaling, sufficient contrast, reduced-motion respect |
| NFR-7 | **Localization:** all strings externalized; translations via Weblate or similar community platform |
| NFR-8 | **Compatibility:** Android 8.0+; phones, tablets and foldables (adaptive layouts with window size classes); test on at least one aggressive-battery OEM (Xiaomi, Samsung, OnePlus) |
| NFR-9 | **Maintainability:** modular code, >70% unit-test coverage on the sync engine, documented architecture |

## 6. Android platform considerations and nuances

### 6.1 Background execution

- **WorkManager** is the backbone: one global periodic coordinator worker that syncs due pairs in turn (decided: simpler scheduling, fair across pairs) plus on-demand one-time workers with constraints (network type, charging, storage not low).
- Long transfers: use `setForeground()` in a `CoroutineWorker` to show a notification. Foreground service types are mandatory on Android 14+, and `dataSync` foreground services have a **time limit on Android 15**, so for user-initiated large transfers evaluate **User-Initiated Data Transfer jobs** (Android 14+) and design workers to be short, checkpointed and re-queueable rather than one huge job.
- **Android 13+:** request the `POST_NOTIFICATIONS` runtime permission.
- **OEM battery killers** (aggressive task killing on some manufacturers) can stop background work; include an in-app guide that deep-links to the system battery settings. Play policy may limit apps that request the exemption directly, so only non-Play builds offer the direct request.
- **Doze and App Standby buckets** delay background work for rarely opened apps. Communicate that periodic sync is best-effort, and that opening the app triggers an immediate catch-up.

### 6.2 Storage Access Framework limits

- Use `ACTION_OPEN_DOCUMENT_TREE`, then call `takePersistableUriPermission()` with read and write flags so access survives reboots.
- Android 11+ **blocks selecting** the root of internal storage, the Download root folder, and `Android/data` and `Android/obb`. Users must choose a subfolder. The UI should explain this clearly.
- Android caps persisted URI permissions per app (128 on older versions, 512 on newer). Track usage and warn near the limit.
- Permission can be lost if the user deletes or renames the folder, or removes the grant; detect via query failures and mark the pair "needs attention".
- SAF providers vary (internal storage, SD card, USB, cloud providers). Only allow `ExternalStorageProvider` and documented-safe providers in v1; warn on others.
- Writes through SAF are not atomic: download to an app-private temp file, then copy into the target, and verify size and hash.
- Filename differences: Drive allows characters and case-variants that Android file systems may not (`/` is allowed in Drive names; case-insensitive duplicates). Implement a name-sanitizing and collision-resolving layer and store the mapping in the DB. Also handle very long paths.

### 6.3 Network and API

- Handle `401` (refresh token and retry once), `403` with `userRateLimitExceeded` or `rateLimitExceeded` and `429` (exponential backoff with jitter), `5xx` (retry), and `storageQuotaExceeded` (stop uploads, notify).
- Use `fields` partial responses and batch requests to reduce quota usage and latency. Use `files.list` with `'<folderId>' in parents and trashed=false` queries and pagination for initial indexing.
- Prefer `supportsAllDrives=true` only if shared drives are in scope (out of scope for v1; document it).
- Detect metered networks via `ConnectivityManager` and `NetworkCapabilities`.

### 6.4 Drive authorization scope and Google verification (decided for v1)

**Decision for v1:** the app requests only the narrow `drive.file` scope and keeps all synced folders inside a root Drive folder it creates, called **Drive Sync** (section 3.8). This is a non-sensitive scope, so it avoids Google's restricted-scope verification and the annual third-party security assessment (CASA), and users get a normal one-tap sign-in.

The trade-off: `drive.file` only reaches files the app created or the user explicitly granted it. That has three consequences:

- Existing Drive folders and files outside Drive Sync cannot be synced in v1.
- Files added inside a Drive Sync subfolder from the Drive website or other apps may be invisible to the app. This is my reading of the scope definition and must be confirmed in the Phase 0 spike. If confirmed, v1 documents it clearly and the Advanced mode removes it.
- Files the app uploaded from the user's other devices should remain visible, which keeps multi-device sync working.

**Planned Advanced mode (not built in v1):** the full `drive` scope with the user's own OAuth client ID, which allows choosing any existing Drive folder. It is a restricted scope, so an official public client would need Google verification and the annual assessment; a bring-your-own-client-ID setup avoids that for personal use. The code stays ready for it through the RemoteStore interface and the AccessMode setting, so adding it later needs a Drive folder picker and an auth provider, not a new sync engine.

## 7. High-level architecture

```
app (UI, navigation, DI wiring)
 ├─ feature-pairs      (home, add/edit pair, pair detail)
 ├─ feature-conflicts  (conflict resolution UI)
 ├─ feature-activity   (log)
 ├─ feature-settings
 ├─ core-ui            (theme, components)
 ├─ core-sync          (pure Kotlin sync engine: diff, planner, executor)
 ├─ core-data          (repositories, Room, DataStore)
 ├─ core-local-storage (SAF access, scanner, observers, hashing)
 ├─ core-drive         (Drive REST client, auth provider interface)
 └─ core-work          (WorkManager workers, scheduling, notifications)
```

**Sync engine pipeline (per pair):**

1. **Scan** local tree and fetch remote delta, producing current snapshots.
2. **Plan** by diffing against the base snapshot, producing a list of actions (upload, download, delete, mkdir, conflict).
3. **Safety check** (mass-delete guard, quota, permissions, validity).
4. **Execute** actions with bounded parallelism, checkpointing each result to the DB immediately.
5. **Commit** the new base snapshot and advance the Drive change token only after success.
6. **Report** to UI, notifications and the activity log.

A per-pair mutex ensures only one sync runs per pair at a time. The planner is a pure function, which makes it exhaustively unit-testable.

## 8. UI and UX requirements

The look should follow the **Material 3** guidelines: dynamic color from the wallpaper, large expressive typography, rounded shapes, edge-to-edge layout, predictive back gesture, and motion that supports (not distracts from) status changes.

**Screens:**

1. **Onboarding:** short value explanation, Google sign-in and creation of the Drive Sync root folder, permissions (notifications, battery guidance).
2. **Home:** list of pairs as cards (name, local path, Drive path, status chip, last sync, progress), FAB "Add folder pair", global sync-now action.
3. **Add/Edit pair wizard:** choose local folder, name the pair (its Drive subfolder is created automatically inside Drive Sync), choose direction and options, review and confirm (with initial-sync preview: "X files to upload, Y to download").
4. **Pair detail:** status, activity, pending changes, conflicts, per-pair settings, pause and delete.
5. **Conflicts:** side-by-side metadata (size, date, device), actions keep local, keep remote or keep both.
6. **Activity log:** filterable timeline.
7. **Settings** and **About** (version, licenses, source, report issue).

Adaptive layout: list-detail on tablets and foldables; navigation bar on phones, navigation rail on larger screens. Support empty, loading and error states for every screen.

## 9. Testing and quality plan

- **Unit tests:** planner and decision matrix (every row of 3.3, plus rename, case-collision, duplicate-name cases), conflict naming, name sanitizing, backoff logic.
- **Fake backends:** an in-memory fake Drive and fake local file system to run full sync scenarios deterministically, including crash injection mid-transfer.
- **Integration tests:** Room migrations, WorkManager test utilities (`TestListenableWorkerBuilder`), MockWebServer for Drive API errors (401, 403, 429, 5xx).
- **UI tests:** Compose tests and screenshot tests for light, dark and large-font variants.
- **Manual device matrix:** Android 8 through latest; Pixel, Samsung, Xiaomi; SD card and USB storage; Doze test with `adb shell dumpsys deviceidle`.
- **Edge-case checklist:** empty folder, 100k files, 5 GB file, airplane mode mid-upload, folder renamed during sync, same file edited on two devices, Drive storage full, account revoked, clock skew between phone and Drive, low phone storage while downloading.

## 10. Open-source project setup

- **License:** Apache-2.0 (permissive) or GPL-3.0 (copyleft, common in F-Droid apps). Pick before the first public commit.
- Repository hygiene: `README`, `CONTRIBUTING`, `CODE_OF_CONDUCT`, `SECURITY.md`, issue and PR templates, architecture docs, "good first issue" labels.
- Never commit secrets: OAuth client IDs for personal use go in `local.properties` or CI secrets; document a keystore-free debug workflow.
- Reproducible builds and signed releases; distribute through GitHub Releases, F-Droid and optionally Google Play.
- Privacy policy (required for Play and for Google OAuth verification) stating no data collection.

## 11. Milestones (suggested)

| Phase | Scope | Rough effort |
| --- | --- | --- |
| 0. Spikes | drive.file visibility of Drive-side additions, SAF observers on several devices, resumable upload, Changes API | 1 to 2 weeks |
| 1. Foundations | Project setup, theme, navigation, auth, Drive client, Room schema | 2 to 3 weeks |
| 2. Single-pair sync | SAF picking, scanner, upload-only then download-only, folder browser | 3 to 4 weeks |
| 3. Two-way engine | Three-way planner, conflicts, deletes with safety guards, resumable transfers | 4 to 5 weeks |
| 4. Automation | WorkManager scheduling, triggers from 3.4, constraints, notifications | 2 to 3 weeks |
| 5. Multi-pair and polish | Multiple pairs, activity log, settings, accessibility, adaptive UI | 3 weeks |
| 6. Hardening and beta | Edge cases, OEM testing, performance, docs, beta release | 3 to 4 weeks |

These are estimates for one experienced developer and will vary with team size.

## 12. Risks

| ID | Risk | Mitigation |
| --- | --- | --- |
| R1 | drive.file cannot see Drive files the app did not create (for example files added from the Drive website) | Early spike to confirm the behavior; Drive access behind a RemoteStore interface so an Advanced mode (full Drive, own client ID) can be added later; `drive.file` is the v1 scope; document the limitation clearly |
| R2 | No reliable change notifications for SAF folders | Layered detection plus periodic reconciliation |
| R3 | OEM battery optimization kills background work | In-app guidance, battery exemption flow, catch-up on app open |
| R4 | Data loss from sync bugs (especially deletes and conflicts) | Trash instead of delete, mass-delete guard, keep-both default, extensive tests |
| R5 | Two-way sync loops or ping-pong between pairs | Pair overlap validation, three-way base snapshot, change-origin tagging |
| R6 | Foreground service time limits and background restrictions on new Android versions | Short checkpointed jobs, UIDT jobs, resumable transfers |
| R7 | Large libraries (100k files) are slow to index through SAF | Direct `ContentResolver` queries, incremental scans, batched DB writes |
| R8 | Drive API quota and rate limits | Batching, partial fields, backoff, Changes API instead of full listing |

## 13. Open questions

1. Which distribution channels matter: Google Play, F-Droid, GitHub only? (Determines auth approach and the use of All-files-access.)
2. Later (Advanced mode): is a personal-use "bring your own OAuth client" model, or a verified public client, the right path?
3. License: Apache-2.0 or GPL-3.0?
4. Should shared drives and "Shared with me" folders be supported in v1?
5. How should Google-native files (Docs, Sheets) be treated by default: skip, or export?
6. Is the SD card and external storage in scope for v1?

## 14. Acceptance criteria for v1

- A user can sign in, add at least three folder pairs, and see changes made on either side propagate automatically within the configured interval, and within about a minute for media folders while the app is active.
- Editing the same file on both sides produces no data loss and results in both versions being preserved.
- Deleting files on one side moves them to Drive trash or the local recycle location, and a mass deletion triggers a confirmation instead of executing silently.
- Interrupted transfers (network loss, process kill, reboot) resume without duplicating or corrupting files.
- The app passes the edge-case checklist in section 9 and has no known data-loss bugs.
- The app is published as open source with documentation sufficient for others to build and contribute.

## 15. Design review (v0.3)

The v0.3 review found 14 gaps; each is now fixed in this document, and building can start from the MVP order below.

| # | Gap or critique | Fix (where) |
| --- | --- | --- |
| 1 | "Two-way" was oversold: under drive.file, Drive-side changes probably arrive only from the app on other phones, not from the Drive website | Stated plainly in 6.4 and 3.10; Phase 0 spike confirms; Advanced mode becomes the first post-v1 release (v1.1) |
| 2 | "Automatic" had no defined timing, and SAF change notifications are unreliable | Realistic latency targets added to 3.4 |
| 3 | A removed SD card or lost permission would look like "all files deleted" and wipe the Drive copy | Unavailable-is-not-deleted rule (3.9) |
| 4 | The app's own downloads could be re-detected as local changes and bounce back | Echo suppression (3.9) |
| 5 | The plan leaned on timestamps, but SAF can't set local modified times and clocks differ | Clocks never compared across sides; FR-S11 rewritten (3.9) |
| 6 | Reinstall, cleared data or a second phone would create a duplicate Drive Sync folder or risk deletes | appProperties tagging, reconnect flow, missing-base merge (3.9, 3.10) |
| 7 | Other apps could see half-written downloads | Temp name, verify, then rename (3.9) |
| 8 | Upload-only and download-only had undefined delete behavior | Direction semantics (3.9) |
| 9 | Mass-delete guard had no numbers | Defaults set in FR-S6 |
| 10 | Tech choices left open (Ktor vs Retrofit, Hilt vs Koin, worker per pair vs global, F-Droid auth) | Decided in sections 2 and 6.1 |
| 11 | Directly requesting a battery-optimization exemption may conflict with Play policy | Play builds deep-link to settings instead (6.1) |
| 12 | v1 scope too large for a first build (multi-account, quota view, Google Docs export) | Downgraded; MVP order below |
| 13 | Forks and self-builds can't see the official build's Drive folders under drive.file | Documented in 3.10 |
| 14 | The name "DriveSync" may clash with Google's branding rules and existing Play Store apps | Pick a final app name before publishing |

### MVP build order

The MVP is: one Google account, the Drive Sync root with multiple pairs, two-way sync with every 3.9 safety rule, keep-both conflicts, trash-based deletes, resumable transfers, periodic + MediaStore + manual triggers, and the home, add-pair, pair detail, conflicts and activity screens. Everything else waits.

1. Phase 0 spikes: drive.file visibility, MediaStore trigger timing, SAF rename and temp-file behavior
2. Pure-Kotlin sync planner with the fake Drive and fake local store, tested against every row of 3.3 and every rule in 3.9
3. Auth, Drive client, and Drive Sync root and pair folder creation
4. SAF scanner and local writer
5. WorkManager scheduling and notifications
6. Compose UI screens
7. Beta on real devices (Pixel, Samsung, Xiaomi)

The planner and its tests come before any UI on purpose: it is where a bug loses data, and it can be built and verified without a phone.
