package com.qutick.adbblacklist.hook

import android.os.Handler
import android.util.Log
import com.qutick.adbblacklist.ModuleConstants
import com.qutick.adbblacklist.data.Fingerprint
import com.qutick.adbblacklist.data.PairedEntry

/**
 * Reflection bridge to the live AdbDebuggingManager instance.
 *
 * All mutation of the ADB key store happens on the manager's own Handler
 * thread. This matters because AdbDebuggingManager keeps mConnectedKeys and
 * mAdbKeyStore on that thread; revoking a connected key from another thread can
 * race with the disconnect callback and resurrect the key.
 */
internal object AdbManager {
    @Volatile private var managerInstance: Any? = null

    fun bind(instance: Any?) {
        if (instance != null) managerInstance = instance
    }

    fun isReady(): Boolean = managerInstance != null

    fun currentManager(): Any? = managerInstance

    fun pairedDevices(): List<PairedEntry> {
        val manager = managerInstance ?: return emptyList()
        val paired = runCatching {
            manager.javaClass.methodOrNull("getPairedDevices")?.invoke(manager) as? Map<*, *>
        }.getOrNull() ?: return emptyList()

        return paired.entries.mapNotNull { entry ->
            val key = entry.key as? String ?: return@mapNotNull null
            val value = entry.value ?: return@mapNotNull null
            val fingerprint = Fingerprint.normalize(
                readString(value, "guid", "getGuid") ?: Fingerprint.of(key)
            ) ?: return@mapNotNull null
            val host = (readString(value, "name", "getDeviceName") ?: Fingerprint.host(key))
                .filterNot { it.isISOControl() }
                .trim()
                .take(ModuleConstants.MAX_HOST_LENGTH)
            PairedEntry(
                fingerprint = fingerprint,
                host = host,
                connected = readBoolean(value, "connected", "isConnected") ?: false,
            )
        }.distinctBy { it.fingerprint }.sortedBy { it.host.lowercase() }
    }

    /**
     * Revokes [fingerprint] and asks adbd to drop its current connection.
     * Safe to call from any thread; the actual work is posted to the manager
     * handler. The public key is looked up in the framework keystore, so the
     * app never has to hold key material.
     */
    fun revokeAndDisconnect(fingerprint: String) {
        val manager = managerInstance ?: return
        val handler = handler(manager) ?: return
        handler.post {
            runCatching {
                val keyStore = handler.javaClass.fieldOrNull("mAdbKeyStore")?.get(handler)
                val storedKey = keyStore?.let { store ->
                    runCatching {
                        store.javaClass.methodOrNull("findKeyFromFingerprint", String::class.java)
                            ?.invoke(store, fingerprint) as? String
                    }.getOrNull()
                }
                if (!storedKey.isNullOrBlank()) {
                    revokeLocked(manager, handler, storedKey)
                } else {
                    val connectedKey = findConnectedKey(manager, fingerprint)
                    if (!connectedKey.isNullOrBlank()) {
                        revokeLocked(manager, handler, connectedKey)
                    } else {
                        Log.i(ModuleConstants.TAG, "No active authorization for $fingerprint; nothing to revoke")
                    }
                }
            }.onFailure { Log.w(ModuleConstants.TAG, "Failed to revoke $fingerprint", it) }
        }
    }

    /** Must run on the manager Handler thread. */
    fun revokeLocked(manager: Any, handler: Any, publicKey: String) {
        // Remove the connection bookkeeping first. Otherwise adbd's disconnect
        // notification calls setLastConnectionTime() on a key we just removed,
        // and AdbKeyStore re-inserts it.
        val connected = runCatching {
            manager.javaClass.fieldOrNull("mConnectedKeys")?.get(manager) as? MutableMap<Any?, Any?>
        }.getOrNull()
        runCatching { connected?.remove(publicKey) }

        val thread = runCatching {
            manager.javaClass.fieldOrNull("mThread")?.get(manager)
        }.getOrNull()
        runCatching {
            thread?.javaClass?.methodOrNull("sendResponse", String::class.java)
                ?.invoke(thread, "DD$publicKey")
        }.onFailure { Log.w(ModuleConstants.TAG, "Failed to ask adbd to disconnect", it) }

        val keyStore = runCatching {
            handler.javaClass.fieldOrNull("mAdbKeyStore")?.get(handler)
        }.getOrNull()
        runCatching {
            keyStore?.javaClass?.methodOrNull("removeKey", String::class.java)
                ?.invoke(keyStore, publicKey)
        }.onFailure { Log.w(ModuleConstants.TAG, "Failed to remove the adb key", it) }

        Log.i(ModuleConstants.TAG, "Revoked and disconnected adb key ${Fingerprint.of(publicKey)}")
    }

    fun handler(manager: Any): Handler? =
        runCatching {
            manager.javaClass.fieldOrNull("mHandler")?.get(manager) as? Handler
        }.getOrNull()

    /**
     * Finds a currently connected key by fingerprint. This also covers keys
     * that were allowed only once and therefore never entered the keystore.
     * Must run on the manager Handler thread.
     */
    fun findConnectedKey(manager: Any, fingerprint: String): String? {
        val connected = runCatching {
            manager.javaClass.fieldOrNull("mConnectedKeys")?.get(manager) as? Map<*, *>
        }.getOrNull() ?: return null
        return connected.keys.asSequence()
            .filterIsInstance<String>()
            .firstOrNull { Fingerprint.of(it) == fingerprint }
    }

    private fun readString(value: Any, field: String, getter: String): String? {
        val fromField = runCatching {
            value.javaClass.fieldOrNull(field)?.get(value) as? String
        }.getOrNull()
        if (!fromField.isNullOrBlank()) return fromField
        return runCatching {
            value.javaClass.methodOrNull(getter)?.invoke(value) as? String
        }.getOrNull()
    }

    private fun readBoolean(value: Any, field: String, getter: String): Boolean? {
        val fromField = runCatching {
            value.javaClass.fieldOrNull(field)?.get(value) as? Boolean
        }.getOrNull()
        if (fromField != null) return fromField
        return runCatching {
            value.javaClass.methodOrNull(getter)?.invoke(value) as? Boolean
        }.getOrNull()
    }
}
