import io.github.drivesync.buildlogic.configureCoverage
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Kover coverage gate for Android library modules that hold testable logic (JVM libraries get it built in). */
class CoverageConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = target.configureCoverage()
}
