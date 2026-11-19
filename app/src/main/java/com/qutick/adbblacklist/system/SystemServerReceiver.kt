package com.qutick.adbblacklist.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qutick.adbblacklist.ModuleConstants
import com.qutick.adbblacklist.data.AddBlacklistResult
import com.qutick.adbblacklist.data.BlacklistStore
import com.qutick.adbblacklist.data.Fingerprint
import com.qutick.adbblacklist.hook.AdbManager
import com.qutick.adbblacklist.hook.HookEnv
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Signature-protected ordered-broadcast API used by the module app.
 *
 * system_server is the source of truth, so the app never needs direct file
 * access, root, or a running provider. All operations return a fresh snapshot
 * in the result extras, which keeps the UI simple and consistent.
 */
internal class SystemServerReceiver(private val env: HookEnv) : BroadcastReceiver() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adb-blacklist-ipc").apply { isDaemon = true }
    }

    fun register(context: Context) {
        val filter = IntentFilter(ModuleConstants.ACTION_MANAGE)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                this,
                filter,
                ModuleConstants.PERMISSION_MANAGE,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            context.registerReceiver(
                this,
                filter,
                ModuleConstants.PERMISSION_MANAGE,
                null,
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModuleConstants.ACTION_MANAGE || !isOrderedBroadcast) return

        val pending = goAsync()
        val operation = intent.getStringExtra(ModuleConstants.EXTRA_OP).orEmpty()
        executor.execute {
            val reply = runCatching { handle(intent, operation) }.getOrElse { failure ->
                Log.w(ModuleConstants.TAG, "Management request failed: $operation", failure)
                error(failure.message ?: failure.javaClass.simpleName)
            }
            try {
                pending.setResult(
                    reply.code,
                    null,
                    Bundle().apply { putString(ModuleConstants.EXTRA_PAYLOAD, reply.payload) },
                )
            } catch (t: Throwable) {
                Log.w(ModuleConstants.TAG, "Cannot deliver management reply", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(intent: Intent, operation: String): Reply = when (operation) {
        ModuleConstants.OP_PING -> Reply(
            ModuleConstants.CODE_OK,
            JSONObject().put("version", 1).put("sdk", Build.VERSION.SDK_INT).toString(),
        )

        ModuleConstants.OP_SNAPSHOT -> snapshot()

        ModuleConstants.OP_BLACKLIST -> {
            val fingerprint = Fingerprint.normalize(
                intent.getStringExtra(ModuleConstants.EXTRA_FINGERPRINT)
            )
            if (fingerprint == null) {
                error("invalid fingerprint")
            } else {
                when (val result = BlacklistStore.addBlacklist(
                    fingerprint,
                    intent.getStringExtra(ModuleConstants.EXTRA_HOST).orEmpty(),
                )) {
                    AddBlacklistResult.OK -> {
                        AdbManager.revokeAndDisconnect(fingerprint)
                        snapshot()
                    }
                    AddBlacklistResult.INVALID -> error("invalid fingerprint")
                }
            }
        }

        ModuleConstants.OP_UNBLACKLIST -> {
            BlacklistStore.removeBlacklist(
                intent.getStringExtra(ModuleConstants.EXTRA_FINGERPRINT).orEmpty()
            )
            snapshot()
        }

        ModuleConstants.OP_SETTINGS -> {
            val notify = if (intent.hasExtra(ModuleConstants.EXTRA_NOTIFY)) {
                intent.getBooleanExtra(ModuleConstants.EXTRA_NOTIFY, true)
            } else null
            val retentionDays = if (intent.hasExtra(ModuleConstants.EXTRA_RETENTION_DAYS)) {
                intent.getIntExtra(
                    ModuleConstants.EXTRA_RETENTION_DAYS,
                    ModuleConstants.DEFAULT_RETENTION_DAYS,
                )
            } else null
            BlacklistStore.updateSettings(notify, retentionDays)
            snapshot()
        }

        ModuleConstants.OP_CLEAR_HISTORY -> {
            BlacklistStore.clearHistory()
            snapshot()
        }

        else -> error("unknown operation")
    }

    private fun snapshot(): Reply {
        val paired = runCatching { AdbManager.pairedDevices() }.getOrElse {
            Log.w(ModuleConstants.TAG, "Cannot read paired devices", it)
            emptyList()
        }
        return Reply(
            ModuleConstants.CODE_OK,
            BlacklistStore.snapshot(paired, AdbManager.isReady()),
        )
    }

    private fun error(message: String): Reply =
        Reply(ModuleConstants.CODE_FAILED, JSONObject().put("error", message).toString())

    private data class Reply(val code: Int, val payload: String)

    companion object {
        /**
         * ActivityManagerService is not published yet when
         * onSystemServerStarting fires. Poll until it is, then register the
         * receiver; otherwise the app's very first broadcast is lost.
         */
        fun scheduleRegistration(env: HookEnv, context: Context) {
            val receiver = SystemServerReceiver(env)
            val mainHandler = Handler(Looper.getMainLooper())
            var attempts = 0
            val task = object : Runnable {
                override fun run() {
                    attempts++
                    val activityManagerReady = runCatching {
                        val serviceManager = Class.forName("android.os.ServiceManager")
                        serviceManager.getMethod("getService", String::class.java)
                            .invoke(null, Context.ACTIVITY_SERVICE) != null
                    }.getOrDefault(false)

                    if (activityManagerReady || attempts >= 30) {
                        runCatching { receiver.register(context) }
                            .onSuccess { env.info("Management receiver registered (attempt $attempts)") }
                            .onFailure { env.error("Management receiver registration failed", it) }
                    } else {
                        mainHandler.postDelayed(this, 500L)
                    }
                }
            }
            mainHandler.post(task)
        }
    }
}
