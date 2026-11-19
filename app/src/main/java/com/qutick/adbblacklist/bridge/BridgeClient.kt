package com.qutick.adbblacklist.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.qutick.adbblacklist.ModuleConstants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-side client for the system_server management bridge.
 *
 * Every reply carries a complete snapshot in EXTRA_PAYLOAD, so the UI never
 * needs a second round trip after a mutation.
 */
internal object BridgeClient {
    data class Reply(val code: Int, val payload: String?, val error: String?)

    fun snapshot(context: Context, callback: (Reply) -> Unit) =
        request(context, ModuleConstants.OP_SNAPSHOT, Bundle.EMPTY, 8_000L, callback)

    fun ping(context: Context, callback: (Reply) -> Unit) =
        request(context, ModuleConstants.OP_PING, Bundle.EMPTY, 3_000L, callback)

    fun blacklist(context: Context, fingerprint: String, host: String, callback: (Reply) -> Unit) =
        request(
            context,
            ModuleConstants.OP_BLACKLIST,
            Bundle().apply {
                putString(ModuleConstants.EXTRA_FINGERPRINT, fingerprint)
                putString(ModuleConstants.EXTRA_HOST, host)
            },
            8_000L,
            callback,
        )

    fun unblacklist(context: Context, fingerprint: String, callback: (Reply) -> Unit) =
        request(
            context,
            ModuleConstants.OP_UNBLACKLIST,
            Bundle().apply { putString(ModuleConstants.EXTRA_FINGERPRINT, fingerprint) },
            8_000L,
            callback,
        )

    fun updateSettings(
        context: Context,
        notifyOnBlock: Boolean?,
        retentionDays: Int?,
        callback: (Reply) -> Unit,
    ) = request(
        context,
        ModuleConstants.OP_SETTINGS,
        Bundle().apply {
            if (notifyOnBlock != null) putBoolean(ModuleConstants.EXTRA_NOTIFY, notifyOnBlock)
            if (retentionDays != null) putInt(ModuleConstants.EXTRA_RETENTION_DAYS, retentionDays)
        },
        8_000L,
        callback,
    )

    fun clearHistory(context: Context, callback: (Reply) -> Unit) =
        request(context, ModuleConstants.OP_CLEAR_HISTORY, Bundle.EMPTY, 8_000L, callback)

    private fun request(
        context: Context,
        operation: String,
        extras: Bundle,
        timeoutMs: Long,
        callback: (Reply) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val receiver = ResultReceiver(handler, callback)
        receiver.arm(timeoutMs)

        val intent = Intent(ModuleConstants.ACTION_MANAGE).apply {
            setPackage(ModuleConstants.SYSTEM_PACKAGE)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(ModuleConstants.EXTRA_OP, operation)
            if (!extras.isEmpty) putExtras(extras)
        }

        runCatching {
            context.sendOrderedBroadcast(
                intent,
                null,
                receiver,
                handler,
                ModuleConstants.CODE_FAILED,
                null,
                null,
            )
        }.onFailure {
            receiver.finish(ModuleConstants.CODE_FAILED, null, it.message ?: "broadcast failed")
        }
    }

    private class ResultReceiver(
        private val handler: Handler,
        private val callback: (Reply) -> Unit,
    ) : BroadcastReceiver() {
        private val completed = AtomicBoolean(false)
        private var timeout: Runnable? = null

        fun arm(timeoutMs: Long) {
            val task = Runnable { finish(ModuleConstants.CODE_FAILED, null, "timeout") }
            timeout = task
            handler.postDelayed(task, timeoutMs)
        }

        fun finish(code: Int, payload: String?, error: String?) {
            if (!completed.compareAndSet(false, true)) return
            timeout?.let { handler.removeCallbacks(it) }
            callback(Reply(code, payload, error))
        }

        override fun onReceive(context: Context, intent: Intent) {
            val extras = getResultExtras(false)
            finish(resultCode, extras?.getString(ModuleConstants.EXTRA_PAYLOAD), null)
        }
    }
}
