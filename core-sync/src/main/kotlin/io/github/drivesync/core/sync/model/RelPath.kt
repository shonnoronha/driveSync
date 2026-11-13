package io.github.drivesync.core.sync.model

/**
 * A normalized path relative to a pair's root, e.g. `Notes/2026/todo.md`.
 * Segments are separated by `/`; empty, `.` and `..` segments are rejected. The pair root itself is not a RelPath.
 */
@JvmInline
value class RelPath private constructor(
    val value: String,
) : Comparable<RelPath> {
    val name: String get() = value.substringAfterLast('/')

    val parent: RelPath? get() = if ('/' in value) RelPath(value.substringBeforeLast('/')) else null

    val segments: List<String> get() = value.split('/')

    val depth: Int get() = value.count { it == '/' } + 1

    /** All ancestors, nearest first. */
    val ancestors: Sequence<RelPath> get() = generateSequence(parent) { it.parent }

    fun child(name: String): RelPath = of("$value/$name")

    fun sibling(name: String): RelPath = parent?.child(name) ?: of(name)

    fun isAncestorOf(other: RelPath): Boolean = other.value.startsWith("$value/")

    override fun compareTo(other: RelPath): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        fun of(raw: String): RelPath {
            val segments = raw.split('/').filter { it.isNotEmpty() }
            require(segments.isNotEmpty()) { "Relative path must not be empty: '$raw'" }
            require(segments.none { it == "." || it == ".." }) { "Relative path must not contain . or ..: '$raw'" }
            return RelPath(segments.joinToString("/"))
        }
    }
}
