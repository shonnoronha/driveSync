# CLAUDE.md — Drive Sync (Android)

Open-source Android app that keeps user-selected phone folders in two-way sync with Google Drive.
The full requirements are in `docs/PRD.md` (v0.3). Read it before starting any task; section numbers below refer to it.

## Product in one paragraph
The user picks any number of local folders (Storage Access Framework). On first sign-in the app creates a root
folder called **Drive Sync** in My Drive; each local folder gets its own subfolder inside it (PRD 3.8).
v1 requests only the `drive.file` OAuth scope. Full-Drive "Advanced mode" is a future feature, so Drive access
must stay behind the `RemoteStore` interface and an `AccessMode` setting (PRD 6.4).

## Tech stack (decided, PRD 2)
- Kotlin, Jetpack Compose, Material 3 (dynamic color, dark theme, edge-to-edge). Plain Material 3, no third-party design reference.
- MVVM + unidirectional data flow (ViewModel + StateFlow), Hilt, Room, DataStore, Coroutines/Flow
- WorkManager: ONE global periodic coordinator worker + on-demand one-time workers (PRD 6.1)
- Drive REST API v3 via OkHttp + Retrofit + kotlinx.serialization (no Google API Java client)
- Auth: Credential Manager + `AuthorizationClient` (Google Identity Services). Hide it behind an `AuthProvider` interface.
- Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`), minSdk 26, target/compile = latest stable
- Tests: JUnit5 or Kotest, MockK, Turbine, MockWebServer, Robolectric where needed
- Lint: Detekt + ktlint

## Modules (PRD 7)
`app`, `feature-pairs`, `feature-conflicts`, `feature-activity`, `feature-settings`, `core-ui`,
`core-sync` (PURE Kotlin/JVM, no Android imports), `core-data`, `core-local-storage`, `core-drive`, `core-work`.

## Non-negotiable sync safety rules (PRD 3.9) — the engine must enforce all
1. Unreachable or suddenly empty folder (either side) => pause pair, NEVER generate deletes.
2. Echo suppression: write post-transfer state into the base snapshot in the same DB transaction.
3. Never compare timestamps across sides; compare each side only with its own base values.
4. Re-check remote/local state right before overwriting; if changed, re-plan (usually a conflict).
5. Abort an upload if the local file changes during transfer.
6. Downloads: hidden temp name -> verify size + MD5 -> rename. Scans ignore temp files.
7. No base snapshot => merge only (link / copy / keep-both). Never delete on a run without a base.
8. Upload-only does not propagate local deletes unless "mirror deletions" is on.
9. Mass-delete guard: pause and ask if a run deletes > 20 files AND > 10% of the pair.
10. Deletes go to Drive trash / local recycle folder, never permanent.
Default conflict policy is keep-both (PRD 3.5).

## Build order (PRD 15, MVP)
1. Project skeleton: modules, version catalog, Hilt, Detekt/ktlint, GitHub Actions running build + unit tests
2. `core-sync`: data models, three-way planner (PRD 3.3 matrix + 3.9 rules), FakeRemoteStore, FakeLocalStore,
   exhaustive unit tests. **Finish and test this before any UI or real Drive code.**
3. `core-drive`: auth, Drive client, Drive Sync root + pair folder creation with `appProperties` tags (PRD 3.10)
4. `core-local-storage`: SAF scanner (direct `ContentResolver` queries, not `DocumentFile`), local writer
5. `core-work`: WorkManager scheduling, MediaStore content-URI triggers, notifications
6. Compose screens: onboarding, home, add pair, pair detail, conflicts, activity, settings
7. Device testing

## Working rules
- Small, reviewable steps. After each step: build, run tests, summarize what changed, then stop for review.
- Never commit secrets. OAuth client IDs live in `local.properties` (git-ignored) and are read via BuildConfig.
- When a PRD detail is ambiguous or conflicts with platform reality, ask instead of guessing, and note the
  decision in `docs/decisions/` as a short ADR.
- Commands: `./gradlew build`, `./gradlew test`, `./gradlew :core-sync:test`, `./gradlew detekt ktlintCheck`
