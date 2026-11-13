plugins {
    alias(libs.plugins.drivesync.android.library)
    alias(libs.plugins.drivesync.hilt)
}

dependencies {
    api(project(":core-sync"))
    implementation(libs.kotlinx.coroutines.android)
}
