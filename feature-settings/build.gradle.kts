plugins {
    alias(libs.plugins.drivesync.android.feature)
}

dependencies {
    implementation(project(":core-work"))
    implementation(project(":core-drive"))
    implementation(project(":core-local-storage"))
}
