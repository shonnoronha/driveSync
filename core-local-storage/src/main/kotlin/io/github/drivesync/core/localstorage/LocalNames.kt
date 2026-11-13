package io.github.drivesync.core.localstorage

/**
 * Names Android shared storage (FAT/exFAT/sdcardfs rules) cannot hold. Writes with such names fail per file
 * (FR-S9) instead of letting the provider silently rename them. The stored name-mapping layer (PRD 6.2) replaces
 * this check later.
 */
object LocalNames {
    private const val ILLEGAL = "\"*/:<>?\\|"
    private const val MAX_BYTES = 255
    private const val FIRST_PRINTABLE = 0x20

    fun problem(name: String): String? =
        when {
            name.isEmpty() -> "empty name"
            name.any { it in ILLEGAL || it.code < FIRST_PRINTABLE } -> "characters not allowed on the phone"
            name.endsWith('.') || name.endsWith(' ') -> "names ending in a dot or space are not allowed on the phone"
            name.toByteArray().size > MAX_BYTES -> "name longer than $MAX_BYTES bytes"
            else -> null
        }
}
