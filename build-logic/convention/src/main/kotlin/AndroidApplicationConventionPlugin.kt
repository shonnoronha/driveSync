import com.android.build.api.dsl.ApplicationExtension
import io.github.drivesync.buildlogic.configureAndroidApplication
import io.github.drivesync.buildlogic.configureQuality
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> { configureAndroidApplication(this) }
        configureQuality()
    }
}
