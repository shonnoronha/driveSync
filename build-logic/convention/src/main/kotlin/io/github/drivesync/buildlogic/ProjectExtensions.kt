package io.github.drivesync.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

internal fun VersionCatalog.versionInt(alias: String): Int = findVersion(alias).get().requiredVersion.toInt()

internal fun VersionCatalog.pluginId(alias: String): String = findPlugin(alias).get().get().pluginId

/** Base package for every module. Placeholder until the final app name is chosen (PRD 15, gap 14). */
internal const val BASE_PACKAGE = "io.github.drivesync"

/** `core-local-storage` -> `io.github.drivesync.core.localstorage` */
internal val Project.defaultNamespace: String
    get() {
        val parts = name.split('-', limit = 2)
        return listOfNotNull(BASE_PACKAGE, parts[0], parts.getOrNull(1)?.replace("-", ""))
            .joinToString(".")
    }
