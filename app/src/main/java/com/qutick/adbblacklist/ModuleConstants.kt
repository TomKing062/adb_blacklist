package com.qutick.adbblacklist

/** Shared constants between the module UI process and the system_server hooks. */
object ModuleConstants {
    const val MODULE_PACKAGE = "com.qutick.adbblacklist"
    const val SYSTEM_PACKAGE = "android"
    const val TAG = "AdbBlacklist"

    const val ACTION_MANAGE = "$MODULE_PACKAGE.action.MANAGE"
    const val PERMISSION_MANAGE = "$MODULE_PACKAGE.permission.MANAGE"

    const val EXTRA_OP = "op"
    const val EXTRA_PAYLOAD = "payload"
    const val EXTRA_FINGERPRINT = "fingerprint"
    const val EXTRA_HOST = "host"
    const val EXTRA_NOTIFY = "notify"
    const val EXTRA_RETENTION_DAYS = "retention_days"

    const val OP_PING = "ping"
    const val OP_SNAPSHOT = "snapshot"
    const val OP_BLACKLIST = "blacklist"
    const val OP_UNBLACKLIST = "unblacklist"
    const val OP_SETTINGS = "settings"
    const val OP_CLEAR_HISTORY = "clear_history"

    const val CODE_OK = 1
    const val CODE_UNSUPPORTED = 2
    const val CODE_FAILED = 3
    const val CODE_INVALID = 4

    const val STORE_DIRECTORY = "system"
    const val STORE_FILENAME = "adb_blacklist.json"

    const val DEFAULT_NOTIFY_ON_BLOCK = true
    const val DEFAULT_RETENTION_DAYS = 30
    const val MAX_RETENTION_DAYS = 3650
    const val MAX_HISTORY = 200
    const val MAX_BLACKLIST = 200
    const val MAX_HOST_LENGTH = 200

    const val CHANNEL_ID = "adb_blacklist_alerts"
    const val NOTIFY_COOLDOWN_MS = 60_000L
}
