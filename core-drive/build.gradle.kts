plugins {
    alias(libs.plugins.drivesync.android.library)
    alias(libs.plugins.drivesync.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core-sync"))
    implementation(libs.kotlinx.coroutines.android)
}
