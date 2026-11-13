plugins {
    alias(libs.plugins.drivesync.android.library)
    alias(libs.plugins.drivesync.hilt)
}

dependencies {
    implementation(project(":core-sync"))
    implementation(project(":core-data"))
    implementation(project(":core-drive"))
    implementation(project(":core-local-storage"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
