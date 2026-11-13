plugins {
    alias(libs.plugins.drivesync.android.library)
    alias(libs.plugins.drivesync.hilt)
    alias(libs.plugins.drivesync.coverage)
}

kover {
    reports {
        filters {
            excludes {
                // Thin ContentResolver/DocumentsContract adapter: verified on a device (build step 7).
                packages("io.github.drivesync.core.localstorage.saf")
            }
        }
    }
}

dependencies {
    api(project(":core-sync"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":core-sync")))
}
