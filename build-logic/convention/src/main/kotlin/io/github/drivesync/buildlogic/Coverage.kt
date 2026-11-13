package io.github.drivesync.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Minimum line coverage for the sync engine (PRD NFR-9: > 70%). */
private const val MIN_LINE_COVERAGE_PERCENT = 70

/** Kover coverage; `check` fails when line coverage drops below the minimum. */
internal fun Project.configureCoverage() {
    pluginManager.apply(libs.pluginId("kover"))
    extensions.configure<KoverProjectExtension> {
        reports {
            verify {
                rule {
                    minBound(MIN_LINE_COVERAGE_PERCENT)
                }
            }
        }
    }
    tasks.named("check") { dependsOn("koverVerify") }
}
