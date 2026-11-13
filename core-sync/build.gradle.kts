// Pure Kotlin/JVM sync engine. Must never depend on Android (CLAUDE.md, PRD 7).
plugins {
    alias(libs.plugins.drivesync.jvm.library)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
