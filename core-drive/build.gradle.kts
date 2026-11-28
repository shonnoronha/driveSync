import java.util.Properties

plugins {
    alias(libs.plugins.drivesync.android.library)
    alias(libs.plugins.drivesync.hilt)
    alias(libs.plugins.drivesync.coverage)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * OAuth "Web application" client id used by Credential Manager: git-ignored local.properties locally, the
 * DRIVESYNC_GOOGLE_SERVER_CLIENT_ID environment variable on CI.
 */
val localProperties =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.reader()
            ?.use(::load)
    }

val googleServerClientId: String =
    localProperties.getProperty("drivesync.googleServerClientId")
        ?: System.getenv("DRIVESYNC_GOOGLE_SERVER_CLIENT_ID")
        ?: ""

/**
 * Set to true once the OAuth client's Cloud project has the restricted `drive` scope on its consent screen
 * (README, "Full Drive access"). Without it the Settings switch is shown but disabled.
 */
val fullDriveScope: Boolean =
    (localProperties.getProperty("drivesync.fullDriveScope") ?: System.getenv("DRIVESYNC_FULL_DRIVE_SCOPE"))
        .toBoolean()

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
        buildConfigField("boolean", "FULL_DRIVE_SCOPE_CONFIGURED", fullDriveScope.toString())
    }
}

kover {
    reports {
        filters {
            excludes {
                // Thin wrappers over Google Play services and Hilt wiring: verified on a device (build step 7).
                packages("io.github.drivesync.core.drive.auth.google", "io.github.drivesync.core.drive.di")
            }
        }
    }
}

dependencies {
    api(project(":core-sync"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id)
    implementation(libs.play.services.auth)

    testImplementation(testFixtures(project(":core-sync")))
    testImplementation(libs.okhttp.mockwebserver)
}
