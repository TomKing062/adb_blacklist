package com.qutick.adbblacklist.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.qutick.adbblacklist.ModuleConstants
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Posts the low-priority "request blocked" notice from system_server.
 *
 * The UI class is referenced by name only: system_server must not load the
 * app's AndroidX-based Activity class through the module class loader.
 */
internal object Notifier {
    private const val MAIN_ACTIVITY = "com.qutick.adbblacklist.ui.MainActivity"

    @Volatile private var notificationManager: NotificationManager? = null
    private val lastNotice = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        if (notificationManager != null) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val chinese = isChinese()
            val channel = NotificationChannel(
                ModuleConstants.CHANNEL_ID,
                if (chinese) "ADB 黑名单拦截" else "ADB Blacklist blocks",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = if (chinese) {
                    "黑名单电脑尝试 ADB 调试时提醒"
                } else {
                    "Alerts when a blacklisted computer requests ADB debugging"
                }
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
            notificationManager = manager
        }.onFailure { Log.w(ModuleConstants.TAG, "Cannot initialize notifications", it) }
    }

    fun notifyBlocked(context: Context?, host: String, fingerprint: String) {
        if (context == null) return
        if (notificationManager == null) init(context)
        val manager = notificationManager ?: return

        val now = System.currentTimeMillis()
        val previous = lastNotice[fingerprint] ?: 0L
        if (now - previous < ModuleConstants.NOTIFY_COOLDOWN_MS) return
        lastNotice[fingerprint] = now

        val chinese = isChinese()
        val title = if (chinese) "已拦截 ADB 调试请求" else "ADB debugging request blocked"
        val name = host.ifBlank { fingerprint }
        val text = if (chinese) {
            "电脑“$name”在黑名单中，已自动拒绝。"
        } else {
            "“$name” is blacklisted. The debugging request was denied."
        }

        val contentIntent = runCatching {
            PendingIntent.getActivity(
                context,
                fingerprint.hashCode(),
                Intent().apply {
                    setClassName(ModuleConstants.MODULE_PACKAGE, MAIN_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()

        val notification = Notification.Builder(context, ModuleConstants.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .apply {
                contentIntent?.let { setContentIntent(it) }
            }
            .build()

        runCatching {
            manager.notify(fingerprint.hashCode() and 0x7FFFFFFF, notification)
        }.onFailure { Log.w(ModuleConstants.TAG, "Failed to post the block notification", it) }
    }

    private fun isChinese(): Boolean =
        Locale.getDefault().language.startsWith("zh")
}
