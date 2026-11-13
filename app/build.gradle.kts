plugins {
    alias(libs.plugins.drivesync.android.application)
    alias(libs.plugins.drivesync.android.compose)
    alias(libs.plugins.drivesync.hilt)
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
}
