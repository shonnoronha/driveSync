# ADR 0005: Phone-side storage (SAF scanner and writer)

Date: 2026-11-13 · Status: proposed (please review)

Build-order step 4 (`core-local-storage`). This ADR covers what the PRD leaves open.

## Shape
- `tree/DocumentTree`: the ten blocking document operations a pair needs (root, query, children, open,
  create, rename, move, delete). Kept this small so that no sync logic needs Android.
- `saf/SafDocumentTree`: the production `DocumentTree`. It queries `DocumentsContract` columns directly, one call
  per directory listing, not `DocumentFile` (PRD 3.6).
- `DocumentTreeLocalStore`: the `LocalStore` the sync engine uses. All logic lives here, tested on the JVM against
  a real-directory `DocumentTree` that behaves like Android's ExternalStorageProvider:
  - document ids are paths, so they change on rename;
  - a clashing name gets " (1)";
  - illegal characters become `_`;
  - it can optionally be case-insensitive or lack move support.
- `saf/SafTrees`: persisted folder grants (take, release, count, check) and the per-pair `LocalStore` factory.

## Decisions
1. **Unavailable is not empty (rule 1):** a `SecurityException` (grant revoked) or a missing or non-folder root
   becomes `TreeUnavailableException`, and the scan reports `Unavailable`, so the pair pauses. A subfolder that
   vanishes during a scan lists as empty, because its files really are gone.
2. **Finding a path:** document ids remembered from the last scan or write are tried first.
   - A cached id is used only if the document still has the expected name; otherwise the parent folder is
     listed again.
   - Moving a folder drops the cached ids below it.
   - This is reliable for ExternalStorageProvider, the only provider v1 allows (PRD 6.2); other providers get a
     warning.
3. **Downloads (rule 6):** stream into the hidden temp file **next to the target**, which the engine verifies by
   size and MD5, then rename it.
   - PRD 6.2 suggests downloading to app-private storage and then copying. A rename in the same folder gives the
     same "no half-written file" guarantee with one write instead of two.
   - A leftover temp from a killed run is replaced. `deleteTemp` refuses any name without the temp suffix, so
     cleanup can never delete a user's file.
4. **Never trust the provider's name:** every create or rename checks the name that was actually stored.
   - A provider may store a different name: a case-insensitive clash ("A.md" next to "a.md"), illegal
     characters, or a " (1)" suffix. When that happens the action fails for that one file (FR-S9). It never
     silently writes under another name, which would sync back as a new file.
   - Names the phone cannot hold (`"*/:<>?\|`, control characters, a trailing dot or space, over 255 bytes) are
     rejected before anything is written.
   - The stored name-mapping layer from PRD 6.2 still has to come, and should then rename such files instead
     of failing. Its natural home is `core-data`, next to the base snapshot.
5. **Moves:** a rename happens in whichever folder has the new name free; if both clash, the move fails and
   nothing changes. Providers without move support get a verified copy, then the source is deleted.
6. **Recycle:** moves into `.drivesync-recycle/<original path>`, adding " (2)", " (3)" and so on on clashes. It is
   never a delete (rule 10). There is still no retention limit (see ADR 0003).
7. **Threads:** every provider call runs on `Dispatchers.IO`; tests pass in their own dispatcher.

## Coverage
Kover gates this module too: 70% minimum, currently 100% of the logic. `saf/` is excluded because it is a thin
Android adapter; it is verified on a device in step 7, like `core-drive`'s auth.

## Not done yet
- Change detection: `ContentObserver`, MediaStore triggers and the stability debounce. These belong to step 5
  (`core-work`).
- Folder picking, explaining blocked roots, and the grant-limit warning in the UI belong to step 6.
- Nothing has run on a real device yet.
