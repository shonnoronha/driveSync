import io.github.drivesync.buildlogic.JAVA_VERSION
import io.github.drivesync.buildlogic.configureKotlinJvmTarget
import io.github.drivesync.buildlogic.configureQuality
import io.github.drivesync.buildlogic.configureUnitTests
import io.github.drivesync.buildlogic.libs
import io.github.drivesync.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/** Pure Kotlin/JVM module (no Android). Used by core-sync. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(libs.pluginId("kotlin-jvm"))
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }
        configureKotlinJvmTarget()
        configureUnitTests()
        configureQuality()
    }
}
