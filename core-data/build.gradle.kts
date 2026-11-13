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

android {
    testOptions {
        unitTests.all {
            // Robolectric runs here; cap it so a build never pushes this low-RAM machine into the OOM killer.
            it.maxHeapSize = "1g"
            // Robolectric reaches into FileDescriptor internals, which JDK 17+ closes by default.
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            )
        }
    }
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
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(testFixtures(project(":core-sync")))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}
