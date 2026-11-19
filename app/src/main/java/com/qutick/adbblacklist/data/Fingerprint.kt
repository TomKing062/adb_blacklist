package com.qutick.adbblacklist.data

import android.util.Base64
import java.security.MessageDigest
import java.util.Locale

/**
 * ADB public keys are stored by the framework as "<base64 key> <user@host>".
 * The fingerprint is the MD5 of the decoded key, formatted like
 * "AA:BB:CC:..." exactly as AdbDebuggingManager#getFingerprints does it.
 */
object Fingerprint {
    private val PATTERN = Regex("(?:[0-9A-Fa-f]{2}:){15}[0-9A-Fa-f]{2}")

    fun normalize(value: String?): String? {
        val text = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        return text.takeIf { PATTERN.matches(it) }
    }

    fun of(key: String?): String? {
        val first = key?.trim()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
        if (first.isEmpty()) return null
        val decoded = runCatching { Base64.decode(first, Base64.DEFAULT) }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        val digest = runCatching { MessageDigest.getInstance("MD5").digest(decoded) }.getOrNull()
            ?: return null
        return digest.joinToString(":") { byte -> String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF) }
    }

    /** Returns the "user@host" comment from a stored public key, or an empty string. */
    fun host(key: String?): String {
        val parts = key?.trim()?.split(Regex("\\s+")).orEmpty()
        val raw = parts.getOrNull(1).orEmpty()
        if (raw == "nouser@nohostname") return ""
        return raw.filterNot { it.isISOControl() }.trim().take(200)
    }
}
