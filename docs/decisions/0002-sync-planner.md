# ADR 0002: Sync planner behaviour where the PRD is silent

Date: 2026-11-13 · Status: proposed (please review)

The planner (`core-sync`, `SyncPlanner.plan`) is a pure function: (local now, remote now, base, settings) →
`Paused` | `NeedsDeleteConfirmation` | `Ready(plan)`. It implements PRD 3.3 and the planner-owned rules of 3.9.
These are the choices I made where the PRD leaves room. Each one leans toward "never lose data".

## Scope split of build-order step 2
- **2a (this change):** models, planner, ignore rules, conflict naming, hash requirements, tests.
- **2b (next):** `RemoteStore` / `LocalStore` interfaces + `AccessMode`, `FakeRemoteStore`, `FakeLocalStore`, the
  executor and full-run scenario tests. Rules 2 (echo suppression), 4 (re-check before write), 5 (abort upload
  when the file changes) and 6 (temp file → verify → rename) are executor duties. Actions already carry the
  state the planner saw, so the executor can re-check rule 4.

## Decisions
1. **Keep-both (two-way):** the **Drive** version is renamed to the conflict copy. Then the local file is uploaded
   to the original name and the conflict copy is downloaded. The local file is never renamed, because another
   app (an Obsidian vault, for example) may have it open.
2. **Modify vs delete:** the modified version is always kept and copied back (PRD 3.3 default), whatever the
   policy is. The only exception is ASK, which leaves the conflict pending.
3. **One-way pairs whose destination diverged** (for example, upload-only and someone edited the file on
   Drive): the destination version is kept under a conflict name **on the destination side only**. The source
   version then goes to the original path. The conflict policy is ignored here except ASK.
4. **One-way restore:** in upload-only, a file deleted on Drive is uploaded again, because a backup restores
   itself. Download-only does the same in the other direction. Files that appear only on the destination
   side are ignored.
5. **Suddenly empty:** the pair pauses whenever the base has at least one file and a side now has zero files
   (ignored and Google-native files don't count). This is strict on purpose: deleting the last file of a
   one-file pair pauses it, and the user resumes from "needs attention".
6. **Unknown hash = modified.** If a local file's size or mtime changed and no MD5 was supplied, the planner
   treats it as modified. The engine hashes only what `HashRequirements` asks for. A missing hash can turn a
   link into a keep-both conflict, but it can never cause a lost edit.
7. **Remote change detection:** MD5 when both MD5s are known. Otherwise file id + version. A Drive file
   replaced by a new file id with the same content counts as unchanged, and the base is refreshed.
8. **Drive duplicates (FR-S13):** the primary file is the one whose id matches the base, otherwise the lowest
   id. Every extra file is renamed on Drive to a conflict-copy name and downloaded, so every path is unique
   afterwards. Upload-only pairs skip the extras instead of touching Drive. A skipped extra still keeps its
   Drive folder alive, so it is never trashed by a mirrored folder delete. If Drive has a file **and** a folder
   with the same name, the folder wins and the file is renamed, but only when the base and the local side
   don't say the path is a file. Otherwise the path is a pending type mismatch.
9. **Type mismatch** (a file on one side and a folder on the other, or a different type from the base): the
   conflict is left pending with no actions for that path or anything under it.
10. **Folders are existence-only** and are decided deepest-first. A folder delete is propagated only when no
    file survives under it on that side after the plan. Ignored and Google-native files count as survivors,
    so trashing a Drive folder can never take a Google Doc with it.
11. **Move detection** needs a unique 1:1 match: same size + MD5 locally, or same Drive file id remotely, with
    unchanged content. Anything ambiguous falls back to delete + create, which is safe because deletes go to
    trash or recycle.
12. **Action order:** folder creates → moves/renames → uploads → downloads → base bookkeeping → deletes
    (deepest first). If a run is interrupted, the copies have been made before anything was deleted.

## Not handled yet (later steps)
- Case-insensitive name collisions and Drive names that are illegal locally (PRD 6.2). These belong to the
  name-mapping layer in `core-local-storage` / `core-data`.
- Folder renames are detected per file (each file moves). Moving whole folders is a later optimisation.
