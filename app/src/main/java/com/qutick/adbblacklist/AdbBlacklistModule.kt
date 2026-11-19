package com.qutick.adbblacklist

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qutick.adbblacklist.data.BlacklistStore
import com.qutick.adbblacklist.hook.AdbBlacklistHooks
import com.qutick.adbblacklist.hook.HookEnv
import com.qutick.adbblacklist.system.Notifier
import com.qutick.adbblacklist.system.SystemServerReceiver
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed entry point. The module is scoped to system_server only (scope.list
 * contains "system"); the app UI runs unhooked in its own process.
 */
class AdbBlacklistModule : XposedModule() {

    @Volatile private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        val process = runCatching { param.getProcessName() }.getOrDefault("?")
        log(Log.INFO, ModuleConstants.TAG, "[MODULE] loaded process=$process api=$apiVersion")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(
            Log.INFO,
            ModuleConstants.TAG,
            "[MODULE] onSystemServerStarting sdk=${Build.VERSION.SDK_INT} api=$apiVersion",
        )
        if (installed) {
            log(Log.INFO, ModuleConstants.TAG, "[MODULE] hooks already installed; skipping")
            return
        }
        installed = true

        val classLoader = runCatching { param.getClassLoader() }.getOrNull()
        if (classLoader == null) {
            log(Log.ERROR, ModuleConstants.TAG, "[MODULE] system_server class loader unavailable")
            return
        }

        val context = resolveSystemContext()?.let { it.applicationContext ?: it }
        val env = HookEnv(this, this, classLoader).apply { this.context = context }

        BlacklistStore.init()
        runCatching { AdbBlacklistHooks.install(env) }
            .onSuccess { env.info("[MODULE] ADB hooks installed") }
            .onFailure { env.error("[MODULE] ADB hook installation failed", it) }

        if (context != null) {
            startBridge(env, context)
        } else {
            env.warn("[MODULE] system context unavailable; scheduling retries")
            retryContext(env, 0)
        }
    }

    private fun startBridge(env: HookEnv, context: Context) {
        runCatching { Notifier.init(context) }
            .onFailure { env.warn("Cannot initialize the notification channel", it) }
        SystemServerReceiver.scheduleRegistration(env, context)
    }

    private fun retryContext(env: HookEnv, attempt: Int) {
        if (attempt >= 20) {
            env.error("system context never became available; UI bridge is disabled")
            return
        }
        val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull() ?: return
        handler.postDelayed({
            val context = resolveSystemContext()?.let { it.applicationContext ?: it }
            if (context != null) {
                env.context = context
                env.info("system context resolved on retry $attempt")
                startBridge(env, context)
            } else {
                retryContext(env, attempt + 1)
            }
        }, 500L)
    }

    /**
     * This intentionally goes through ActivityThread instead of waiting for
     * Application.attach: the latter is not guaranteed to fire early enough on
     * some Android 15-17 system_server builds.
     */
    private fun resolveSystemContext(): Context? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val current = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null) ?: return null

            fun byMethod(name: String): Context? = runCatching {
                activityThreadClass.getDeclaredMethod(name)
                    .apply { isAccessible = true }
                    .invoke(current) as? Context
            }.getOrNull()

            fun byField(name: String): Context? = runCatching {
                activityThreadClass.getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(current) as? Context
            }.getOrNull()

            byMethod("getSystemContext")
                ?: byMethod("getContext")
                ?: byField("mSystemContext")
                ?: byField("mInitialApplication")
        }.getOrNull()
    }
}
