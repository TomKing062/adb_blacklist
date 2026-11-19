package com.qutick.adbblacklist.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.qutick.adbblacklist.ModuleConstants
import com.qutick.adbblacklist.R
import com.qutick.adbblacklist.bridge.BridgeClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var status: TextView

    private var loading = false
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(12), dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = getString(R.string.screen_title)
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(themeColor(android.R.attr.textColorPrimary))
        })
        status = TextView(this).apply {
            text = getString(R.string.loading)
            textSize = 12f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(3), 0, 0)
        }
        titles.addView(status)
        header.addView(
            titles,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            },
        )
        header.addView(MaterialButton(this).apply {
            text = getString(R.string.action_refresh)
            isAllCaps = false
            setOnClickListener { refresh() }
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
        )
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) firstResume = false else refresh()
    }

    private fun refresh() {
        if (loading) return
        loading = true
        status.text = getString(R.string.loading)
        renderLoading()

        BridgeClient.snapshot(this) snapshot@{ reply ->
            loading = false
            if (isFinishing || isDestroyed) return@snapshot

            val parsed = reply.payload?.let(::parseSnapshot)
            if (reply.code == ModuleConstants.CODE_OK && parsed != null) {
                render(parsed)
            } else {
                renderInactive(replyDetail(reply))
            }
        }
    }

    private fun renderLoading() {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(TextView(this).apply {
            text = getString(R.string.loading)
            textSize = 14f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(dp(4), dp(32), dp(4), dp(4))
        })
    }

    private fun renderInactive(detail: String?) {
        content.removeAllViews()
        content.gravity = Gravity.NO_GRAVITY
        status.text = getString(R.string.status_disconnected)
        addCard { inner ->
            inner.addView(titleView(getString(R.string.module_inactive_title)))
            inner.addView(detailView(getString(R.string.status_hint)))
            if (!detail.isNullOrBlank()) {
                inner.addView(detailView(detail))
            }
        }
    }

    private fun render(snapshot: UiSnapshot) {
        content.removeAllViews()
        content.gravity = Gravity.NO_GRAVITY
        status.text = getString(R.string.status_connected, snapshot.sdk)

        if (!snapshot.managerReady) {
            addCard { inner ->
                inner.addView(titleView(getString(R.string.status_not_ready)))
                inner.addView(detailView(getString(R.string.status_hint)))
            }
        }

        val blacklisted = snapshot.blacklist.map { it.fingerprint }.toSet()

        addSection(getString(R.string.section_blacklist))
        if (snapshot.blacklist.isEmpty()) {
            addEmpty(getString(R.string.empty_blacklist))
        } else {
            snapshot.blacklist.forEach { entry -> addBlacklistRow(entry) }
        }

        addSection(getString(R.string.section_paired))
        if (snapshot.paired.isEmpty()) {
            addEmpty(getString(R.string.empty_paired))
        } else {
            snapshot.paired.forEach { entry ->
                addPairedRow(entry, blacklisted.contains(entry.fingerprint))
            }
        }

        addSectionWithAction(
            getString(R.string.section_history),
            getString(R.string.action_clear),
        ) { confirmClearHistory() }
        addEmpty(getString(R.string.history_hint))
        if (snapshot.history.isEmpty()) {
            addEmpty(getString(R.string.empty_history))
        } else {
            snapshot.history.forEach { entry ->
                addHistoryRow(entry, blacklisted.contains(entry.fingerprint))
            }
        }

        addSection(getString(R.string.section_settings))
        addSettingsCard(snapshot)

        content.addView(TextView(this).apply {
            val version = versionName()
            text = if (version.isBlank()) "" else getString(R.string.version_label, version)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(dp(4), dp(24), dp(4), dp(4))
        })
    }

    private fun addBlacklistRow(entry: UiBlacklist) {
        addCard { inner ->
            inner.addView(titleView(hostLabel(entry.host)))
            inner.addView(monoView(entry.fingerprint))
            inner.addView(detailView(getString(R.string.label_added_at, time(entry.addedAt))))
            inner.addView(actionRow(getString(R.string.action_unblacklist)) {
                confirmUnblacklist(entry.host, entry.fingerprint)
            })
        }
    }

    private fun addPairedRow(entry: UiPaired, blacklisted: Boolean) {
        addCard { inner ->
            inner.addView(titleView(hostLabel(entry.host)))
            inner.addView(monoView(entry.fingerprint))
            inner.addView(detailView(getString(
                if (entry.connected) R.string.label_connected else R.string.label_authorized
            )))
            if (blacklisted) {
                inner.addView(detailView(getString(R.string.label_blacklisted)))
                inner.addView(actionRow(getString(R.string.action_unblacklist)) {
                    confirmUnblacklist(entry.host, entry.fingerprint)
                })
            } else {
                inner.addView(actionRow(getString(R.string.action_blacklist)) {
                    confirmBlacklist(entry.host, entry.fingerprint)
                })
            }
        }
    }

    private fun addHistoryRow(entry: UiHistory, blacklisted: Boolean) {
        addCard { inner ->
            inner.addView(titleView(hostLabel(entry.host)))
            inner.addView(monoView(entry.fingerprint))
            inner.addView(detailView(getString(
                R.string.history_summary,
                time(entry.lastSeen),
                entry.requests,
                resultLabel(entry.result),
            )))
            if (entry.blockedCount > 0) {
                inner.addView(detailView(getString(R.string.history_blocked_count, entry.blockedCount)))
            }
            if (blacklisted) {
                inner.addView(actionRow(getString(R.string.action_unblacklist)) {
                    confirmUnblacklist(entry.host, entry.fingerprint)
                })
            } else {
                inner.addView(actionRow(getString(R.string.action_blacklist)) {
                    confirmBlacklist(entry.host, entry.fingerprint)
                })
            }
        }
    }

    private fun addSettingsCard(snapshot: UiSnapshot) {
        addCard { inner ->
            val notifyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(titleView(getString(R.string.notify_switch_title)))
            texts.addView(detailView(getString(R.string.notify_switch_desc)))
            notifyRow.addView(
                texts,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                },
            )
            notifyRow.addView(MaterialSwitch(this).apply {
                isChecked = snapshot.settings.notifyOnBlock
                setOnCheckedChangeListener { _, checked ->
                    updateSettings(checked, null)
                }
            })
            inner.addView(notifyRow)

            inner.addView(divider())

            val retentionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(2))
                isClickable = true
                setOnClickListener { chooseRetention(snapshot) }
            }
            retentionRow.addView(
                titleView(getString(R.string.retention_title)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                },
            )
            retentionRow.addView(detailView(retentionLabel(snapshot.settings.retentionDays)))
            inner.addView(retentionRow)
        }
    }

    private fun confirmBlacklist(host: String, fingerprint: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.blacklist_confirm_title)
            .setMessage(getString(R.string.blacklist_confirm_message, hostLabel(host), fingerprint))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                BridgeClient.blacklist(this, fingerprint, host) { reply -> handleMutation(reply) }
            }
            .show()
    }

    private fun confirmUnblacklist(host: String, fingerprint: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unblacklist_confirm_title)
            .setMessage(getString(R.string.unblacklist_confirm_message, hostLabel(host)))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                BridgeClient.unblacklist(this, fingerprint) { reply -> handleMutation(reply) }
            }
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history_confirm_title)
            .setMessage(R.string.clear_history_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                BridgeClient.clearHistory(this) { reply -> handleMutation(reply) }
            }
            .show()
    }

    private fun chooseRetention(snapshot: UiSnapshot) {
        val values = intArrayOf(7, 30, 90, 0)
        val options = arrayOf<CharSequence>(
            getString(R.string.retention_days_7),
            getString(R.string.retention_days_30),
            getString(R.string.retention_days_90),
            getString(R.string.retention_forever),
        )
        val current = values.indexOf(snapshot.settings.retentionDays).let { if (it >= 0) it else 1 }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.retention_dialog_title)
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                updateSettings(null, values[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateSettings(notifyOnBlock: Boolean?, retentionDays: Int?) {
        BridgeClient.updateSettings(this, notifyOnBlock, retentionDays) { reply ->
            handleMutation(reply)
        }
    }

    private fun handleMutation(reply: BridgeClient.Reply) {
        if (isFinishing || isDestroyed) return
        if (reply.code == ModuleConstants.CODE_OK) {
            val parsed = reply.payload?.let(::parseSnapshot)
            if (parsed != null) render(parsed)
            Toast.makeText(this, R.string.operation_done, Toast.LENGTH_SHORT).show()
            // Revocation is posted to the framework handler; refresh once it lands.
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) refresh()
            }, 700L)
        } else {
            Toast.makeText(this, errorText(reply), Toast.LENGTH_LONG).show()
        }
    }

    private fun errorText(reply: BridgeClient.Reply): String {
        val detail = replyDetail(reply) ?: getString(R.string.operation_invalid)
        return getString(R.string.operation_failed, detail)
    }

    private fun replyDetail(reply: BridgeClient.Reply): String? {
        if (reply.error == "timeout") return getString(R.string.operation_timeout)
        val payloadError = reply.payload?.let {
            runCatching { JSONObject(it).optString("error") }.getOrNull()
        }
        return payloadError?.takeIf { it.isNotBlank() }
            ?: reply.error?.takeIf { it.isNotBlank() }
    }

    private fun addSection(title: String) {
        addSectionWithAction(title, null, null)
    }

    private fun addSectionWithAction(title: String, action: String?, onClick: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(20), dp(4), dp(6))
        }
        row.addView(
            TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(themeColor(android.R.attr.textColorPrimary))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            },
        )
        if (action != null && onClick != null) {
            row.addView(TextView(this).apply {
                text = action
                textSize = 13f
                setTextColor(themeColor(android.R.attr.colorAccent))
                setPadding(dp(10), dp(6), dp(4), dp(6))
                isClickable = true
                setOnClickListener { onClick() }
            })
        }
        content.addView(row)
    }

    private fun addEmpty(message: String) {
        content.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(dp(4), dp(4), dp(4), dp(6))
            setLineSpacing(0f, 1.15f)
        })
    }

    private fun addCard(block: (LinearLayout) -> Unit) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            radius = dp(16).toFloat()
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        block(inner)
        card.addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(card)
    }

    private fun titleView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(themeColor(android.R.attr.textColorPrimary))
    }

    private fun detailView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(themeColor(android.R.attr.textColorSecondary))
        setPadding(0, dp(4), 0, 0)
        setLineSpacing(0f, 1.15f)
    }

    private fun monoView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(themeColor(android.R.attr.textColorSecondary))
        setPadding(0, dp(4), 0, 0)
        setTextIsSelectable(true)
    }

    private fun actionRow(label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
            addView(MaterialButton(this@MainActivity).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            })
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0x1A808080)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply { topMargin = dp(12) }
    }

    private fun hostLabel(host: String): String =
        host.ifBlank { getString(R.string.label_unknown_host) }

    private fun resultLabel(result: String): String = getString(
        when (result) {
            "blocked" -> R.string.result_blocked
            "denied" -> R.string.result_denied
            "allowed_once" -> R.string.result_allowed_once
            "allowed_always" -> R.string.result_allowed_always
            else -> R.string.result_pending
        }
    )

    private fun retentionLabel(days: Int): String =
        if (days <= 0) getString(R.string.retention_forever)
        else getString(R.string.retention_value, days)

    private fun time(value: Long): String {
        if (value <= 0L) return "—"
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
    }

    private fun themeColor(attr: Int): Int {
        val values = obtainStyledAttributes(intArrayOf(attr))
        return try {
            values.getColor(0, Color.BLACK)
        } finally {
            values.recycle()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private data class UiSnapshot(
        val sdk: Int,
        val managerReady: Boolean,
        val settings: UiSettings,
        val blacklist: List<UiBlacklist>,
        val history: List<UiHistory>,
        val paired: List<UiPaired>,
    )

    private data class UiSettings(val notifyOnBlock: Boolean, val retentionDays: Int)
    private data class UiBlacklist(val fingerprint: String, val host: String, val addedAt: Long)
    private data class UiHistory(
        val fingerprint: String,
        val host: String,
        val lastSeen: Long,
        val requests: Int,
        val result: String,
        val blockedCount: Int,
    )
    private data class UiPaired(val fingerprint: String, val host: String, val connected: Boolean)

    private fun parseSnapshot(payload: String): UiSnapshot? = runCatching {
        val root = JSONObject(payload)
        val settings = root.optJSONObject("settings")
        UiSnapshot(
            sdk = root.optInt("sdk", Build.VERSION.SDK_INT),
            managerReady = root.optBoolean("managerReady", false),
            settings = UiSettings(
                notifyOnBlock = settings?.optBoolean("notifyOnBlock", true) ?: true,
                retentionDays = settings?.optInt("retentionDays", 30) ?: 30,
            ),
            blacklist = root.optJSONArray("blacklist").mapObjects { value ->
                val fingerprint = value.optString("fingerprint")
                if (fingerprint.isBlank()) null else UiBlacklist(
                    fingerprint = fingerprint,
                    host = value.optString("host"),
                    addedAt = value.optLong("addedAt", 0L),
                )
            },
            history = root.optJSONArray("history").mapObjects { value ->
                val fingerprint = value.optString("fingerprint")
                if (fingerprint.isBlank()) null else UiHistory(
                    fingerprint = fingerprint,
                    host = value.optString("host"),
                    lastSeen = value.optLong("lastSeen", 0L),
                    requests = value.optInt("requests", 1),
                    result = value.optString("result", "pending"),
                    blockedCount = value.optInt("blockedCount", 0),
                )
            },
            paired = root.optJSONArray("paired").mapObjects { value ->
                val fingerprint = value.optString("fingerprint")
                if (fingerprint.isBlank()) null else UiPaired(
                    fingerprint = fingerprint,
                    host = value.optString("host"),
                    connected = value.optBoolean("connected", false),
                )
            },
        )
    }.getOrNull()

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val result = ArrayList<T>()
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            transform(value)?.let { result.add(it) }
        }
        return result
    }
}
