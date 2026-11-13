package io.github.drivesync.core.sync.fakes

import java.security.MessageDigest

/** Lower-case hex MD5, the same format as Drive's `md5Checksum`. */
fun md5Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("MD5")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun md5Hex(text: String): String = md5Hex(text.toByteArray())
