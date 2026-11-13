# ADR 0004: Drive client, auth and the Drive Sync folders

Date: 2026-11-13 · Status: proposed (please review)

Build-order step 3 (`core-drive`). This ADR covers what the PRD leaves open, and one engine fix the work turned up.

## Shape
- `api/`: `DriveApi` (Retrofit, Drive v3) and `DriveClient`. `DriveClient` adds typed errors, pagination and
  resumable uploads, and knows nothing about pairs.
- `store/DriveRemoteStore`: the `RemoteStore` for one pair, rooted at the pair folder's id.
  `DriveRemoteStoreFactory` creates one per pair.
- `folders/DriveSyncFolders`: the "Drive Sync" root and the pair subfolders (PRD 3.8, 3.10).
- `auth/AuthProvider`: the pure interface background syncs use (`accessToken`, `invalidate`).
  `auth/google/GoogleAuth` implements it with Credential Manager and `AuthorizationClient`, and also has the
  interactive `signIn` / `authorize` / `signOut` methods that the UI will call.
- `http/`: the auth interceptor, the 401 authenticator, the retry interceptor and backoff.

## Decisions
1. **Tokens:** `AuthorizationClient.authorize()` is called for every token. Play services caches and refreshes
   tokens itself, so the app never stores one.
   - On a `401`, the rejected token goes to `clearToken` and the request is retried once.
   - A second `401`, or any need for a consent screen, throws `AuthRequiredException`. Pairs then pause and the
     user is asked to re-authenticate (FR-A4). It is an `IOException`, so it travels safely through OkHttp.
2. **Retries (PRD 6.3):** exponential backoff from 1 s, capped at 32 s, with jitter; at most 5 retries; a longer
   `Retry-After` wins.
   - `429` and `403 rateLimitExceeded` / `userRateLimitExceeded`: always retried, because Drive rejected the
     request without running it.
   - `5xx` and network errors: retried only for idempotent methods. A `POST` that loses its reply is not
     resent, because it could create a second folder. The executor's next run reuses or finds what exists.
   - `storageQuotaExceeded`: becomes `DriveException.QuotaExceeded`, so step 5 can stop uploads and notify.
3. **Uploads always use the resumable protocol,** in 4 MiB chunks. The **last chunk is held back** until the
   whole file has been read and `verifyBeforeCommit` has passed, so a file that changed mid-upload never
   creates a Drive revision (rule 5). An abandoned upload cancels its session.
   - Chunk failures are recovered by asking the session how far it got, then resending the rest.
   - Not done: resuming a session after the process is killed. The next run uploads from the start.
     Persisting session URIs is a later optimisation.
4. **Paths are rebuilt from Drive on every call; nothing is cached.** `stat` walks the parent chain up to the
   pair folder, so the rule-4 re-check sees renames and moves made elsewhere. A file that has left the pair, or
   is under a trashed folder, reads as gone.
5. **Scan:** a breadth-first listing that batches up to 40 folders per `files.list` query. The PRD's Changes API
   (3.7) is an optimisation for step 5, when the coordinator worker owns the change token.
6. **Unavailable is not empty:** a pair folder that is missing, trashed (directly or through a trashed root), or
   not a folder gives `Snapshot.Unavailable`, and the engine pauses (rule 1).
7. **Duplicate folder names on Drive:** new items go into the duplicate with the lowest id, the same tie-break
   the planner uses for files.
8. **Drive names that cannot be path segments:** `/` becomes `_`, and `.` / `..` get a leading `_`. This is an
   interim mapping. The real name-mapping layer, with a stored mapping, is part of PRD 6.2 in step 4.
9. **Root:** found by `appProperties role=root`, never by name. A folder the user renamed or moved still counts,
   and a folder they made called "Drive Sync" does not.
   - `findOrCreateRoot` is for first sign-in only. Afterwards the app checks the stored id with `status()` and
     never silently re-creates a trashed root (PRD 3.8).
   - If two phones raced and made two roots, every phone picks the oldest.
10. **Pair folders:** tagged `role=pair` and `pairId=<uuid>`. Name clashes with anything in the root get
    " (2)", " (3)" and so on. A blank local name becomes "Folder".
11. **OAuth client id:** `drivesync.googleServerClientId` in `local.properties`, which is git-ignored, read into
    `BuildConfig.GOOGLE_SERVER_CLIENT_ID`.
    - It is the **Web** client id that Credential Manager needs. The Android client is matched by package name
      and signing certificate, so it needs no id in the app.
    - The build and the tests work without it; only sign-in fails, with a clear message.

## Engine fix found here
Drive bumps a file's `version` on a rename or move, but the in-memory fake did not. After a phone applied a
Drive-side move locally (`MoveLocal`), the base kept the old Drive version. The result was an extra bookkeeping
`Link` action on the next run. It was never data loss, but every move cost one wasted action.

The engine test over HTTP (`DriveSyncEngineTest`) caught it. The fix:
- `MoveLocal` now carries the moved Drive entry, and the executor stores its version.
- The core-sync fake now bumps the version on moves, like Drive.

## Coverage
Kover now also gates `core-drive` (debug unit tests; 70% minimum). Two parts are excluded until device testing
(step 7), because they cannot run on the JVM:
- `auth/google`, a thin wrapper over Play services.
- `di`, the Hilt module.

## Not verified yet
- Nothing has run against real Drive. The PRD's Phase 0 question is still open: can `drive.file` see files that
  this app uploaded from another phone? That needs a device and a real OAuth client.
- The exact `401` and consent behaviour of `AuthorizationClient` on a device.
