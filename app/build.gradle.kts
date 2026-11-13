plugins {
    alias(libs.plugins.drivesync.android.application)
    alias(libs.plugins.drivesync.android.compose)
    alias(libs.plugins.drivesync.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    defaultConfig {
        applicationId = "io.github.drivesync"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        // Screenshots show clock times: render them in one timezone and locale on every machine.
        it.systemProperty("user.timezone", "UTC")
        it.systemProperty("user.language", "en")
        it.systemProperty("user.country", "US")
    }
}

roborazzi {
    // Screen images live in the repo: `./gradlew :app:recordRoborazziDebug` updates them,
    // `./gradlew :app:verifyRoborazziDebug` fails when a screen changed unexpectedly.
    outputDir.set(file("screenshots"))
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-work"))
    implementation(project(":feature-pairs"))
    implementation(project(":feature-conflicts"))
    implementation(project(":feature-activity"))
    implementation(project(":feature-settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(project(":core-drive"))
    testImplementation(project(":feature-activity"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
