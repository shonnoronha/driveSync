package io.github.drivesync.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Minimum line coverage for modules with logic (PRD NFR-9: > 70%). */
private const val MIN_LINE_COVERAGE_PERCENT = 70

/** Generated code that no test can or should cover. */
private val GENERATED_CLASSES =
    arrayOf(
        "*.BuildConfig",
        "*_Factory",
        "*_Factory\$*",
        "*_MembersInjector",
        "*_Impl",
        "*_Impl\$*",
        "*_HiltModules*",
        "*_Provide*Factory*",
        "Hilt_*",
        "hilt_aggregated_deps.*",
        "dagger.hilt.internal.*",
    )

/**
 * Kover coverage; `check` fails when line coverage drops below the minimum. Android modules are measured on
 * the debug unit tests only.
 */
internal fun Project.configureCoverage() {
    pluginManager.apply(libs.pluginId("kover"))
    extensions.configure<KoverProjectExtension> {
        reports {
            filters {
                excludes {
                    classes(*GENERATED_CLASSES)
                }
            }
            verify {
                rule {
                    minBound(MIN_LINE_COVERAGE_PERCENT)
                }
            }
        }
    }
    val isAndroid = pluginManager.hasPlugin("com.android.library") || pluginManager.hasPlugin("com.android.application")
    val verifyTask = if (isAndroid) "koverVerifyDebug" else "koverVerify"
    tasks.named("check") { dependsOn(verifyTask) }
}
