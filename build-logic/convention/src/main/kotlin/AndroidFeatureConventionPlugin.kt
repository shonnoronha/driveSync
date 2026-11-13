import io.github.drivesync.buildlogic.lib
import io.github.drivesync.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A feature module: Android library + Compose + Hilt + ViewModel/navigation, depending on core-ui and core-data. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("drivesync.android.library")
        pluginManager.apply("drivesync.android.compose")
        pluginManager.apply("drivesync.hilt")
        dependencies {
            add("implementation", project(":core-ui"))
            add("implementation", project(":core-data"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-navigation-compose"))
            add("implementation", libs.lib("androidx-hilt-navigation-compose"))
        }
    }
}
