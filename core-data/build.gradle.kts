plugins {
    alias(libs.plugins.drivesync.android.library)
    alias(libs.plugins.drivesync.hilt)
    alias(libs.plugins.drivesync.coverage)
    alias(libs.plugins.room)
}

room {
    // Exported schemas are committed so future versions can test their migrations.
    schemaDirectory("$projectDir/schemas")
}

kover {
    reports {
        filters {
            excludes {
                packages("io.github.drivesync.core.data.di")
            }
        }
    }
}

dependencies {
    api(project(":core-sync"))
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    api(libs.androidx.datastore.preferences)

    testImplementation(testFixtures(project(":core-sync")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}
