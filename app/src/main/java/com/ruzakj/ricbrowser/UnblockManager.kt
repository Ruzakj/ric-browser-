package com.ruzakj.ricbrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

class UnblockManager(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun statusLabel(): String = if (prefs.getBoolean(KEY_ENABLED, false) && savedProxy().isNotBlank()) "ON" else "OFF"

    fun applySaved(onApplied: (() -> Unit)? = null) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (prefs.getBoolean(KEY_ENABLED, false)) toast("WebView proxy override is not supported on this device")
            onApplied?.invoke()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val proxy = savedProxy()
        if (enabled && proxy.isNotBlank()) {
            val config = ProxyConfig.Builder()
                .addProxyRule(proxy)
                .addDirect()
                .build()
            ProxyController.getInstance().setProxyOverride(config, executor) { onApplied?.invoke() }
        } else {
            ProxyController.getInstance().clearProxyOverride(executor) { onApplied?.invoke() }
        }
    }

    fun showDialog(onChanged: () -> Unit) {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val proxy = savedProxy()
        val status = if (enabled && proxy.isNotBlank()) "Enabled\n$proxy" else "Disabled"
        val options = arrayOf(
            "Turn off",
            "Set / change proxy",
            "Android Private DNS settings"
        )
        AlertDialog.Builder(activity)
            .setTitle("Unblock mode")
            .setMessage("Current: $status\n\nRic Browser can route WebView traffic through an HTTP, HTTPS, or SOCKS proxy. This can reach pages blocked by DNS/ISP filtering when the proxy itself is reachable.")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
                        applySaved { toast("Unblock mode off"); onChanged() }
                    }
                    1 -> showProxyEditor(onChanged)
                    2 -> openPrivateDnsSettings()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showProxyEditor(onChanged: () -> Unit) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, dp(6), p, 0)
        }
        val hint = TextView(activity).apply {
            text = "Examples: proxy.example.com:8080, https://proxy.example.com:443, socks://127.0.0.1:9050"
            textSize = 12f
        }
        val input = EditText(activity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "Proxy host:port"
            setText(savedProxy())
            setSelection(text.length)
        }
        box.addView(hint)
        box.addView(input)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Unblock proxy")
            .setView(box)
            .setPositiveButton("Enable", null)
            .setNeutralButton("Clear", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val proxy = normalizeProxy(input.text.toString())
                if (proxy == null) {
                    input.error = "Use host:port or http(s)/socks://host:port"
                    return@setOnClickListener
                }
                prefs.edit().putString(KEY_PROXY, proxy).putBoolean(KEY_ENABLED, true).apply()
                applySaved {
                    toast("Unblock mode enabled")
                    dialog.dismiss()
                    onChanged()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                prefs.edit().remove(KEY_PROXY).putBoolean(KEY_ENABLED, false).apply()
                applySaved {
                    toast("Proxy cleared")
                    dialog.dismiss()
                    onChanged()
                }
            }
        }
        dialog.show()
    }

    private fun savedProxy(): String = prefs.getString(KEY_PROXY, "")?.trim().orEmpty()

    private fun normalizeProxy(value: String): String? {
        var v = value.trim()
        if (v.isBlank() || v.length > 240 || v.contains('@') || v.any { it.isWhitespace() }) return null
        if (v.startsWith("socks5://", true) || v.startsWith("socks4://", true)) {
            v = "socks://" + v.substringAfter("://")
        }
        val lower = v.lowercase()
        if ("://" in v && !(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("socks://"))) return null
        val authority = v.substringAfter("://", v)
        if (authority.isBlank() || authority.contains('/') || authority.contains('?') || authority.contains('#')) return null
        val portText = if (authority.startsWith("[")) authority.substringAfter("]:", "") else authority.substringAfterLast(':', "")
        if (portText.isNotEmpty()) {
            val port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        }
        val hostPart = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else if (authority.count { it == ':' } == 1) authority.substringBeforeLast(':') else authority
        if (hostPart.isBlank()) return null
        return v
    }

    private fun openPrivateDnsSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS)
        runCatching {
            val privateDns = Intent("android.settings.PRIVATE_DNS_SETTINGS")
            activity.startActivity(privateDns)
        }.onFailure { activity.startActivity(intent) }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "ric_unblock"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROXY = "proxy"
    }
}
