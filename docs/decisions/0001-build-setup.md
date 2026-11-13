# ADR 0001: Build setup for the project skeleton

Date: 2026-11-13 · Status: accepted (review pending)

## Context
Build-order step 1 (CLAUDE.md): multi-module Gradle skeleton, version catalog, Hilt, Detekt/ktlint, CI.

## Decisions
- **Versions:** latest stable at the time of writing: Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21, KSP 2.3.13,
  Hilt 2.60.1, compileSdk/targetSdk 37, minSdk 26. All versions live in `gradle/libs.versions.toml`.
- **JDK 21 required** to run Gradle (Gradle 9 / AGP 9 do not run on JDK 11). Bytecode targets Java 17.
- **AGP 9 built-in Kotlin:** Android modules do not apply `org.jetbrains.kotlin.android`; AGP compiles Kotlin itself.
- **Convention plugins** in `build-logic/` (`drivesync.android.application|library|compose|feature`,
  `drivesync.jvm.library`, `drivesync.hilt`). Each one also applies Detekt + ktlint. Module namespaces are derived
  from the module name (`core-local-storage` -> `io.github.drivesync.core.localstorage`).
- **Package / applicationId `io.github.drivesync` is a placeholder.** PRD 15 gap 14 says the final name is not
  chosen. Change it before the first Play upload, when it becomes permanent.
- **Tests:** JUnit 6 (Jupiter) on the JUnit Platform for every JVM and Android local unit test, plus MockK,
  Turbine, coroutines-test. Robolectric is in the catalog for later (needs the vintage engine when used).
- **Detekt 1.23.8 (stable)** rather than 2.0 alpha. Its own Kotlin dependencies are pinned to 2.0.21 so the
  project's Kotlin 2.4 does not leak into detekt's classpath. The detekt-formatting plugin is not added;
  ktlint runs through `org.jlleitschuh.gradle.ktlint` (ktlint 1.8.0) instead.
- **core-sync** uses the pure `drivesync.jvm.library` plugin: no Android, no Hilt.
