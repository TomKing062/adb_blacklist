package com.qutick.adbblacklist.data

data class BlacklistEntry(
    val fingerprint: String,
    val host: String,
    val addedAt: Long,
)

data class HistoryEntry(
    val fingerprint: String,
    val host: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val requests: Int,
    val result: String,
    val blockedCount: Int,
)

data class PairedEntry(
    val fingerprint: String,
    val host: String,
    val connected: Boolean,
)

data class Settings(
    val notifyOnBlock: Boolean = true,
    val retentionDays: Int = 30,
)

object HistoryResult {
    const val PENDING = "pending"
    const val BLOCKED = "blocked"
    const val DENIED = "denied"
    const val ALLOWED_ONCE = "allowed_once"
    const val ALLOWED_ALWAYS = "allowed_always"
}

enum class AddBlacklistResult {
    OK,
    INVALID,
}
