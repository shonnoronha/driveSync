package io.github.drivesync.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal val JAVA_VERSION = JavaVersion.VERSION_17
internal val JVM_TARGET = JvmTarget.JVM_17

internal fun Project.configureAndroidApplication(extension: ApplicationExtension) {
    extension.apply {
        namespace = BASE_PACKAGE
        compileSdk = libs.versionInt("compileSdk")
        defaultConfig.minSdk = libs.versionInt("minSdk")
        defaultConfig.targetSdk = libs.versionInt("targetSdk")
        compileOptions.sourceCompatibility = JAVA_VERSION
        compileOptions.targetCompatibility = JAVA_VERSION
    }
    configureKotlinJvmTarget()
    configureUnitTests()
}

internal fun Project.configureAndroidLibrary(extension: LibraryExtension) {
    extension.apply {
        namespace = defaultNamespace
        compileSdk = libs.versionInt("compileSdk")
        defaultConfig.minSdk = libs.versionInt("minSdk")
        compileOptions.sourceCompatibility = JAVA_VERSION
        compileOptions.targetCompatibility = JAVA_VERSION
        testOptions.unitTests.all {
            // Robolectric may run here: cap the test JVM so builds stay clear of the OOM killer on low-RAM machines.
            it.maxHeapSize = "1g"
            // Robolectric reaches into FileDescriptor internals, which JDK 17+ closes by default.
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
    configureKotlinJvmTarget()
    configureUnitTests()
}

internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JVM_TARGET)
    }
}

/** JUnit Platform (Jupiter) for every local/JVM unit test. */
internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    dependencies {
        add("testImplementation", platform(libs.lib("junit-bom")))
        add("testImplementation", libs.lib("junit-jupiter"))
        add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
        add("testImplementation", libs.lib("mockk"))
        add("testImplementation", libs.lib("turbine"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
    }
}
