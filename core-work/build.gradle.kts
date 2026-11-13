plugins {
    alias(libs.plugins.drivesync.android.library)
    alias(libs.plugins.drivesync.hilt)
    alias(libs.plugins.drivesync.coverage)
}

android {
    // Notification texts come from resources, so Robolectric tests need them merged.
    testOptions.unitTests.isIncludeAndroidResources = true
}

kover {
    reports {
        filters {
            excludes {
                // WorkManager/notification glue: thin, partly tested here, fully verified on a device (step 7).
                packages("io.github.drivesync.core.work.android")
            }
        }
    }
}

dependencies {
    api(project(":core-sync"))
    api(project(":core-data"))
    implementation(project(":core-drive"))
    implementation(project(":core-local-storage"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(testFixtures(project(":core-sync")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}
