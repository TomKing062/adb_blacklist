package com.qutick.adbblacklist.data

import android.os.Build
import android.os.Environment
import android.util.Log
import com.qutick.adbblacklist.ModuleConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Authoritative blacklist/history store. It lives inside system_server so the
 * policy survives app-process death and reboots, and so the authorization hook
 * never has to wake the module app before deciding a request.
 *
 * Public keys themselves are deliberately not persisted: the blacklist is
 * keyed by the MD5 fingerprint the framework already uses, and revocation looks
 * the key up in the framework keystore by fingerprint. This keeps the bridge
 * payload small and avoids copying key material into another file.
 *
 * The file is private to system_server (0600). The app reads and edits it only
 * through the signature-protected ordered-broadcast bridge.
 */
object BlacklistStore {
    private val lock = Any()
    private var storeFile: File? = null
    private var loaded = false

    private val blacklist = LinkedHashMap<String, BlacklistEntry>()
    private val history = LinkedHashMap<String, HistoryEntry>()
    private var settings = Settings()

    fun init() {
        synchronized(lock) {
            if (storeFile == null) {
                storeFile = runCatching {
                    val directory = File(Environment.getDataDirectory(), ModuleConstants.STORE_DIRECTORY)
                    if (!directory.exists()) directory.mkdirs()
                    File(directory, ModuleConstants.STORE_FILENAME)
                }.onFailure {
                    Log.w(ModuleConstants.TAG, "Cannot resolve the blacklist store path", it)
                }.getOrNull()
            }
            if (!loaded) loadLocked()
        }
    }

    fun snapshot(paired: List<PairedEntry>, managerReady: Boolean): String = synchronized(lock) {
        ensureLoadedLocked()
        pruneLocked(System.currentTimeMillis())
        JSONObject().apply {
            put("version", 1)
            put("sdk", Build.VERSION.SDK_INT)
            put("managerReady", managerReady)
            put("settings", settings.toJson())
            put("blacklist", JSONArray().apply {
                blacklist.values.sortedByDescending { it.addedAt }.forEach { put(it.toJson()) }
            })
            put("history", JSONArray().apply {
                history.values.sortedByDescending { it.lastSeen }.forEach { put(it.toJson()) }
            })
            put("paired", JSONArray().apply {
                paired.forEach { put(it.toJson()) }
            })
        }.toString()
    }

    fun isBlacklisted(fingerprint: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val normalized = Fingerprint.normalize(fingerprint)
        normalized != null && blacklist.containsKey(normalized)
    }

    fun recordRequest(fingerprint: String, host: String, blocked: Boolean) {
        synchronized(lock) {
            ensureLoadedLocked()
            val normalized = Fingerprint.normalize(fingerprint) ?: return
            val now = System.currentTimeMillis()
            val cleanHost = sanitizeHost(host)
            val old = history[normalized]
            val entry = if (old == null) {
                HistoryEntry(
                    fingerprint = normalized,
                    host = cleanHost,
                    firstSeen = now,
                    lastSeen = now,
                    requests = 1,
                    result = if (blocked) HistoryResult.BLOCKED else HistoryResult.PENDING,
                    blockedCount = if (blocked) 1 else 0,
                )
            } else {
                old.copy(
                    host = cleanHost.ifBlank { old.host },
                    lastSeen = now,
                    requests = old.requests + 1,
                    result = if (blocked) HistoryResult.BLOCKED else HistoryResult.PENDING,
                    blockedCount = old.blockedCount + if (blocked) 1 else 0,
                )
            }
            history[normalized] = entry
            trimHistoryLocked()
            persistLocked()
        }
    }

    fun recordAllowed(key: String, alwaysAllow: Boolean) {
        synchronized(lock) {
            ensureLoadedLocked()
            val normalized = Fingerprint.of(key) ?: return
            val now = System.currentTimeMillis()
            val cleanHost = sanitizeHost(Fingerprint.host(key))
            val old = history[normalized]
            val result = if (alwaysAllow) HistoryResult.ALLOWED_ALWAYS else HistoryResult.ALLOWED_ONCE
            history[normalized] = if (old == null) {
                HistoryEntry(
                    fingerprint = normalized,
                    host = cleanHost,
                    firstSeen = now,
                    lastSeen = now,
                    requests = 1,
                    result = result,
                    blockedCount = 0,
                )
            } else {
                old.copy(
                    host = cleanHost.ifBlank { old.host },
                    lastSeen = now,
                    result = result,
                )
            }
            trimHistoryLocked()
            persistLocked()
        }
    }

    fun markBlocked(fingerprint: String, host: String) {
        synchronized(lock) {
            ensureLoadedLocked()
            val normalized = Fingerprint.normalize(fingerprint) ?: return
            val now = System.currentTimeMillis()
            val cleanHost = sanitizeHost(host)
            val old = history[normalized]
            history[normalized] = if (old == null) {
                HistoryEntry(
                    fingerprint = normalized,
                    host = cleanHost,
                    firstSeen = now,
                    lastSeen = now,
                    requests = 1,
                    result = HistoryResult.BLOCKED,
                    blockedCount = 1,
                )
            } else {
                old.copy(
                    host = cleanHost.ifBlank { old.host },
                    lastSeen = now,
                    result = HistoryResult.BLOCKED,
                    blockedCount = old.blockedCount + 1,
                )
            }
            trimHistoryLocked()
            persistLocked()
        }
    }

    fun recordDenied(fingerprint: String?, host: String?) {
        synchronized(lock) {
            ensureLoadedLocked()
            val normalized = Fingerprint.normalize(fingerprint) ?: return
            val now = System.currentTimeMillis()
            val cleanHost = sanitizeHost(host.orEmpty())
            val old = history[normalized]
            history[normalized] = if (old == null) {
                HistoryEntry(
                    fingerprint = normalized,
                    host = cleanHost,
                    firstSeen = now,
                    lastSeen = now,
                    requests = 1,
                    result = HistoryResult.DENIED,
                    blockedCount = 0,
                )
            } else {
                old.copy(
                    host = cleanHost.ifBlank { old.host },
                    lastSeen = now,
                    result = HistoryResult.DENIED,
                )
            }
            trimHistoryLocked()
            persistLocked()
        }
    }

    fun addBlacklist(fingerprint: String, host: String): AddBlacklistResult {
        synchronized(lock) {
            ensureLoadedLocked()
            val normalized = Fingerprint.normalize(fingerprint) ?: return AddBlacklistResult.INVALID
            val cleanHost = sanitizeHost(host)
            val now = System.currentTimeMillis()
            val old = blacklist[normalized]
            blacklist[normalized] = BlacklistEntry(
                fingerprint = normalized,
                host = cleanHost.ifBlank { old?.host.orEmpty() },
                addedAt = old?.addedAt ?: now,
            )
            trimBlacklistLocked()
            persistLocked()
            return AddBlacklistResult.OK
        }
    }

    fun removeBlacklist(fingerprint: String): Boolean {
        synchronized(lock) {
            ensureLoadedLocked()
            val normalized = Fingerprint.normalize(fingerprint) ?: return false
            val removed = blacklist.remove(normalized) != null
            if (removed) persistLocked()
            return removed
        }
    }

    fun updateSettings(notifyOnBlock: Boolean?, retentionDays: Int?) {
        synchronized(lock) {
            ensureLoadedLocked()
            settings = settings.copy(
                notifyOnBlock = notifyOnBlock ?: settings.notifyOnBlock,
                retentionDays = (retentionDays ?: settings.retentionDays)
                    .coerceIn(0, ModuleConstants.MAX_RETENTION_DAYS),
            )
            pruneLocked(System.currentTimeMillis())
            persistLocked()
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            ensureLoadedLocked()
            history.clear()
            persistLocked()
        }
    }

    fun shouldNotify(): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        settings.notifyOnBlock
    }

    private fun ensureLoadedLocked() {
        if (!loaded) loadLocked()
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val file = storeFile ?: return
        if (!file.isFile) return
        try {
            val root = JSONObject(file.readText())
            root.optJSONObject("settings")?.let { value ->
                settings = Settings(
                    notifyOnBlock = value.optBoolean("notifyOnBlock", ModuleConstants.DEFAULT_NOTIFY_ON_BLOCK),
                    retentionDays = value.optInt("retentionDays", ModuleConstants.DEFAULT_RETENTION_DAYS)
                        .coerceIn(0, ModuleConstants.MAX_RETENTION_DAYS),
                )
            }
            root.optJSONArray("blacklist")?.let { array ->
                for (index in 0 until array.length()) {
                    val value = array.optJSONObject(index) ?: continue
                    val fingerprint = Fingerprint.normalize(value.optString("fingerprint")) ?: continue
                    blacklist[fingerprint] = BlacklistEntry(
                        fingerprint = fingerprint,
                        host = sanitizeHost(value.optString("host")),
                        addedAt = value.optLong("addedAt", System.currentTimeMillis()),
                    )
                }
            }
            root.optJSONArray("history")?.let { array ->
                val allowedResults = setOf(
                    HistoryResult.PENDING,
                    HistoryResult.BLOCKED,
                    HistoryResult.DENIED,
                    HistoryResult.ALLOWED_ONCE,
                    HistoryResult.ALLOWED_ALWAYS,
                )
                for (index in 0 until array.length()) {
                    val value = array.optJSONObject(index) ?: continue
                    val fingerprint = Fingerprint.normalize(value.optString("fingerprint")) ?: continue
                    val result = value.optString("result", HistoryResult.PENDING)
                        .takeIf { it in allowedResults } ?: HistoryResult.PENDING
                    history[fingerprint] = HistoryEntry(
                        fingerprint = fingerprint,
                        host = sanitizeHost(value.optString("host")),
                        firstSeen = value.optLong("firstSeen", System.currentTimeMillis()),
                        lastSeen = value.optLong("lastSeen", System.currentTimeMillis()),
                        requests = value.optInt("requests", 1).coerceAtLeast(1),
                        result = result,
                        blockedCount = value.optInt("blockedCount", 0).coerceAtLeast(0),
                    )
                }
            }
            trimBlacklistLocked()
            trimHistoryLocked()
            pruneLocked(System.currentTimeMillis())
        } catch (t: Throwable) {
            Log.w(ModuleConstants.TAG, "Blacklist store is unreadable; keeping an empty in-memory copy", t)
            runCatching { file.renameTo(File(file.path + ".corrupt")) }
            blacklist.clear()
            history.clear()
        }
    }

    private fun persistLocked() {
        val file = storeFile ?: return
        try {
            val root = JSONObject().apply {
                put("version", 1)
                put("settings", settings.toJson())
                put("blacklist", JSONArray().apply {
                    blacklist.values.forEach { put(it.toJson()) }
                })
                put("history", JSONArray().apply {
                    history.values.forEach { put(it.toJson()) }
                })
            }
            val text = root.toString()
            val temporary = File(file.path + ".tmp")
            temporary.writeText(text)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            temporary.setExecutable(false, false)
            if (!temporary.renameTo(file)) {
                file.writeText(text)
                temporary.delete()
            }
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.setExecutable(false, false)
        } catch (t: Throwable) {
            Log.w(ModuleConstants.TAG, "Failed to persist the blacklist store", t)
        }
    }

    private fun pruneLocked(now: Long) {
        val days = settings.retentionDays
        if (days <= 0) return
        val cutoff = now - days.toLong() * 86_400_000L
        val iterator = history.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.lastSeen < cutoff) iterator.remove()
        }
    }

    private fun trimHistoryLocked() {
        while (history.size > ModuleConstants.MAX_HISTORY) {
            val oldest = history.minByOrNull { it.value.lastSeen }?.key ?: return
            history.remove(oldest)
        }
    }

    private fun trimBlacklistLocked() {
        while (blacklist.size > ModuleConstants.MAX_BLACKLIST) {
            val oldest = blacklist.minByOrNull { it.value.addedAt }?.key ?: return
            blacklist.remove(oldest)
        }
    }

    private fun sanitizeHost(value: String): String =
        value.filterNot { it.isISOControl() }.trim().take(ModuleConstants.MAX_HOST_LENGTH)

    private fun BlacklistEntry.toJson(): JSONObject = JSONObject().apply {
        put("fingerprint", fingerprint)
        put("host", host)
        put("addedAt", addedAt)
    }

    private fun HistoryEntry.toJson(): JSONObject = JSONObject().apply {
        put("fingerprint", fingerprint)
        put("host", host)
        put("firstSeen", firstSeen)
        put("lastSeen", lastSeen)
        put("requests", requests)
        put("result", result)
        put("blockedCount", blockedCount)
    }

    private fun PairedEntry.toJson(): JSONObject = JSONObject().apply {
        put("fingerprint", fingerprint)
        put("host", host)
        put("connected", connected)
    }

    private fun Settings.toJson(): JSONObject = JSONObject().apply {
        put("notifyOnBlock", notifyOnBlock)
        put("retentionDays", retentionDays)
    }
}
