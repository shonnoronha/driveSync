// Pure Kotlin/JVM sync engine. Must never depend on Android (CLAUDE.md, PRD 7).
plugins {
    alias(libs.plugins.drivesync.jvm.library)
    `java-test-fixtures`
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // FakeLocalStore / FakeRemoteStore / InMemoryBaseStore, reusable by other modules' tests.
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
