import java.util.Properties

plugins {
    alias(libs.plugins.drivesync.android.application)
    alias(libs.plugins.drivesync.android.compose)
    alias(libs.plugins.drivesync.hilt)
    alias(libs.plugins.roborazzi)
}

/**
 * Release signing, from environment variables (CI, GitHub Secrets) or a git-ignored keystore.properties
 * (storeFile, storePassword, keyAlias, keyPassword). Without either, the release build stays unsigned.
 */
val signing: Map<String, String> =
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.reader().use(::load) }.let { p ->
            p.stringPropertyNames().associateWith(p::getProperty)
        }
    } ?: listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .mapNotNull { key -> System.getenv("DRIVESYNC_SIGNING_" + key.uppercase())?.let { key to it } }
        .toMap()

android {
    defaultConfig {
        applicationId = "io.github.drivesync"
        // CI passes the tag (v0.2.0 -> 0.2.0) and the run number; local builds use the defaults.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
    }
    signingConfigs {
        if (signing.size == 4) {
            create("release") {
                storeFile = rootProject.file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
