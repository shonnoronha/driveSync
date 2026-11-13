package io.github.drivesync.core.drive.api

/** Builders for Drive `q` search strings. Values are escaped, so names with quotes cannot break the query. */
object DriveQuery {
    fun literal(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    fun childrenOf(parentIds: Collection<String>): String {
        require(parentIds.isNotEmpty()) { "parentIds must not be empty" }
        val parents = parentIds.joinToString(" or ") { "${literal(it)} in parents" }
        return "($parents) and trashed = false"
    }

    fun childNamed(
        parentIds: Collection<String>,
        name: String,
    ): String = "${childrenOf(parentIds)} and name = ${literal(name)}"

    fun foldersTagged(
        key: String,
        value: String,
    ): String =
        "mimeType = ${literal(DriveFile.FOLDER_MIME_TYPE)} and trashed = false and " +
            "appProperties has { key=${literal(key)} and value=${literal(value)} }"
}
