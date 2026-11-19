package com.qutick.adbblacklist.hook

import android.os.Message
import com.qutick.adbblacklist.data.BlacklistStore
import com.qutick.adbblacklist.data.Fingerprint
import com.qutick.adbblacklist.system.Notifier
import io.github.libxposed.api.XposedInterface

/**
 * system_server hooks for the AOSP ADB authorization prompt.
 *
 * Android 15/16/17 (AOSP main) route an unknown USB key through
 * AdbDebuggingHandler.startConfirmationForKey(key, fingerprint). Hook that
 * method:
 *   - record the request in the connection history (allow/deny both count),
 *   - if the key's fingerprint is blacklisted, do not start the system
 *     UsbDebuggingActivity at all, revoke a stale authorization if one exists,
 *     and send the framework's normal "NO" response to adbd.
 *
 * allowDebugging()/denyDebugging() are hooked to update the history with the
 * user's eventual choice.
 */
internal object AdbBlacklistHooks {
    private lateinit var env: HookEnv
    private var installed = false

    @Volatile private var pendingFingerprint: String? = null
    @Volatile private var pendingHost: String? = null

    /** Set while we call denyDebugging() ourselves, so it is not recorded as a user denial. */
    private val internalDeny = ThreadLocal.withInitial { false }

    fun install(env: HookEnv) {
        if (installed) return
        this.env = env
        installed = true

        val loader = env.classLoader
        val managerClass = loader.findClassOrNull("com.android.server.adb.AdbDebuggingManager")
        val handlerClass = loader.findClassOrNull("com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler")
        if (managerClass == null || handlerClass == null) {
            env.error("AdbDebuggingManager classes not found; hooks are disabled")
            return
        }
        env.info("Resolved ${managerClass.name} and ${handlerClass.name}; sdk=${android.os.Build.VERSION.SDK_INT}")

        hookManagerConstructor(managerClass)
        hookAdbServiceConstructor(loader)
        hookResultCallbacks(managerClass)

        val promptHooked = hookPrompt(handlerClass)
        if (!promptHooked) {
            env.warn("startConfirmationForKey unavailable; falling back to handleMessage")
            hookHandleMessageFallback(handlerClass)
        }
        env.info("Hook installation finished; promptHook=$promptHooked")
    }

    private fun hookManagerConstructor(managerClass: Class<*>) {
        managerClass.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            runCatching {
                env.xposed.hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val instance = chain.getThisObject()
                            if (instance != null) {
                                val wasReady = AdbManager.isReady()
                                AdbManager.bind(instance)
                                if (!wasReady) env.info("Captured AdbDebuggingManager instance")
                            }
                        }
                        result
                    }
                env.info("Hooked AdbDebuggingManager constructor: $constructor")
            }.onFailure { env.error("Failed to hook AdbDebuggingManager constructor", it) }
        }
    }

    private fun hookAdbServiceConstructor(loader: ClassLoader) {
        val serviceClass = loader.findClassOrNull("com.android.server.adb.AdbService") ?: return
        serviceClass.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            runCatching {
                env.xposed.hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val service = chain.getThisObject() ?: return@runCatching
                            val manager = service.javaClass.fieldOrNull("mDebuggingManager")?.get(service)
                            if (manager != null) {
                                val wasReady = AdbManager.isReady()
                                AdbManager.bind(manager)
                                if (!wasReady) env.info("Captured AdbDebuggingManager via AdbService")
                            }
                        }
                        result
                    }
            }.onFailure { env.error("Failed to hook AdbService constructor", it) }
        }
    }

    private fun hookResultCallbacks(managerClass: Class<*>) {
        val allow = managerClass.methodOrNull(
            "allowDebugging",
            Boolean::class.javaPrimitiveType!!,
            String::class.java,
        )
        if (allow != null) {
            hookMethod(allow, "allowDebugging") { chain ->
                val always = chain.getArg(0) as? Boolean ?: false
                val key = chain.getArg(1) as? String
                val fingerprint = key?.let { Fingerprint.of(it) }
                if (fingerprint != null && BlacklistStore.isBlacklisted(fingerprint)) {
                    // The blacklist was added while this prompt was already on
                    // screen (or the framework retried an old prompt). Enforce
                    // it instead of letting the stale "allow" through.
                    runCatching {
                        BlacklistStore.markBlocked(
                            fingerprint,
                            key?.let { Fingerprint.host(it) }.orEmpty(),
                        )
                    }
                    clearPending()
                    val manager = chain.getThisObject()
                    if (manager != null) reject(manager)
                    null
                } else {
                    if (key != null) {
                        runCatching { BlacklistStore.recordAllowed(key, always) }
                            .onFailure { env.warn("Failed to record allowed request", it) }
                    }
                    clearPending()
                    chain.proceed()
                }
            }
        } else {
            env.warn("allowDebugging not found")
        }

        val deny = managerClass.methodOrNull("denyDebugging")
        if (deny != null) {
            hookMethod(deny, "denyDebugging") { chain ->
                if (!internalDeny.get()) {
                    runCatching {
                        BlacklistStore.recordDenied(pendingFingerprint, pendingHost)
                    }.onFailure { env.warn("Failed to record denied request", it) }
                    clearPending()
                }
                chain.proceed()
            }
        } else {
            env.warn("denyDebugging not found")
        }
    }

    private fun hookPrompt(handlerClass: Class<*>): Boolean {
        val method = handlerClass.methodOrNull(
            "startConfirmationForKey",
            String::class.java,
            String::class.java,
        ) ?: return false

        runCatching {
            env.xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val key = chain.getArg(0) as? String
                    val fingerprint = chain.getArg(1) as? String
                    val handler = chain.getThisObject()
                    if (key == null || fingerprint == null || handler == null) {
                        return@intercept chain.proceed()
                    }
                    if (handleRequest(handler, key, fingerprint)) null else chain.proceed()
                }
            // handleMessage is the only caller of the private prompt method and
            // may have inlined it on some AOT-compiled ROMs.
            handlerClass.methodOrNull("handleMessage", Message::class.java)?.let { handleMessage ->
                runCatching { env.xposed.deoptimize(handleMessage) }
            }
            env.info("Hooked AdbDebuggingHandler.startConfirmationForKey")
            return true
        }.onFailure { env.error("Failed to hook startConfirmationForKey", it) }
        return false
    }

    /**
     * Fallback for ROMs where startConfirmationForKey is renamed or removed.
     * MESSAGE_ADB_CONFIRM carries the public key in msg.obj.
     */
    private fun hookHandleMessageFallback(handlerClass: Class<*>) {
        val method = handlerClass.methodOrNull("handleMessage", Message::class.java) ?: run {
            env.error("handleMessage also unavailable; no prompt hook installed")
            return
        }
        val confirmMessage = handlerClass.staticIntFieldOrNull("MESSAGE_ADB_CONFIRM") ?: 5
        runCatching {
            env.xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val message = chain.getArg(0) as? Message
                    if (message == null || message.what != confirmMessage) {
                        return@intercept chain.proceed()
                    }
                    val key = message.obj as? String
                    val fingerprint = Fingerprint.of(key)
                    val handler = chain.getThisObject()
                    if (key == null || fingerprint == null || handler == null) {
                        return@intercept chain.proceed()
                    }
                    if (handleRequest(handler, key, fingerprint)) null else chain.proceed()
                }
            env.info("Hooked handleMessage fallback; MESSAGE_ADB_CONFIRM=$confirmMessage")
        }.onFailure { env.error("Failed to hook handleMessage fallback", it) }
    }

    /** Returns true when the request was blocked and the original prompt must be skipped. */
    private fun handleRequest(handler: Any, key: String, fingerprint: String): Boolean {
        val normalized = Fingerprint.normalize(fingerprint)
        if (normalized == null) {
            env.warn("Framework supplied an invalid fingerprint: $fingerprint")
            return false
        }
        val host = Fingerprint.host(key)
        val blocked = BlacklistStore.isBlacklisted(normalized)

        runCatching {
            BlacklistStore.recordRequest(normalized, host, blocked)
        }.onFailure { env.warn("Failed to record ADB request", it) }

        pendingFingerprint = normalized
        pendingHost = host

        if (!blocked) return false

        env.info("Blocked ADB request from ${host.ifBlank { normalized }} ($normalized)")
        val manager = AdbManager.currentManager()
            ?: handler.javaClass.fieldOrNull("this\$0")?.get(handler)

        // A key that was already granted must be removed as well; otherwise the
        // framework would keep treating it as authorized.
        if (manager != null) {
            val keyStore = handler.javaClass.fieldOrNull("mAdbKeyStore")?.get(handler)
            val storedKey = keyStore?.let { store ->
                runCatching {
                    store.javaClass.methodOrNull("findKeyFromFingerprint", String::class.java)
                        ?.invoke(store, normalized) as? String
                }.getOrNull()
            }
            if (!storedKey.isNullOrBlank()) {
                AdbManager.revokeLocked(manager, handler, storedKey)
            } else {
                val connectedKey = AdbManager.findConnectedKey(manager, normalized)
                if (!connectedKey.isNullOrBlank()) {
                    AdbManager.revokeLocked(manager, handler, connectedKey)
                }
            }
            reject(manager)
        } else {
            env.warn("AdbDebuggingManager instance unavailable; cannot send NO to adbd")
        }

        if (BlacklistStore.shouldNotify()) {
            Notifier.notifyBlocked(env.context, host, normalized)
        }
        clearPending()
        return true
    }

    private fun reject(manager: Any) {
        val deny = manager.javaClass.methodOrNull("denyDebugging")
        if (deny != null) {
            internalDeny.set(true)
            try {
                deny.invoke(manager)
                return
            } catch (t: Throwable) {
                env.warn("denyDebugging failed; falling back to the raw response", t)
            } finally {
                internalDeny.set(false)
            }
        }
        val thread = manager.javaClass.fieldOrNull("mThread")?.get(manager)
        runCatching {
            thread?.javaClass?.methodOrNull("sendResponse", String::class.java)
                ?.invoke(thread, "NO")
        }.onFailure { env.warn("Failed to send NO to adbd", it) }
    }

    private fun hookMethod(
        method: java.lang.reflect.Method,
        label: String,
        block: (XposedInterface.Chain) -> Any?,
    ) {
        runCatching {
            env.xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> block(chain) }
            env.info("Hooked $label")
        }.onFailure { env.error("Failed to hook $label", it) }
    }

    private fun clearPending() {
        pendingFingerprint = null
        pendingHost = null
    }
}
