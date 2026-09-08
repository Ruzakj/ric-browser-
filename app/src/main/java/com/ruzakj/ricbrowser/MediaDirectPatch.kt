package com.ruzakj.ricbrowser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.LinkedHashMap

/**
 * Keeps the media button on a true media-player route.
 * Tap: choose detected media (when needed) and open directly in MX Player.
 * Long press: choose media, then choose a non-browser external media player.
 *
 * We intentionally do not use Intent.createChooser() for http/https media URLs,
 * because browsers such as Chrome can register as ACTION_VIEW handlers and may
 * download the media instead of playing it.
 */
object MediaDirectPatch {
    private val mxPackages = arrayOf(
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro"
    )

    private val blockedBrowserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser",
        "com.google.android.googlequicksearchbox"
    )

    /** Re-applied on every resume so MainActivity can never restore its old listener. */
    fun attach(activity: Activity) {
        if (activity !is MainActivity) return
        val button = findMediaButton(activity.window.decorView) ?: return
        button.setOnClickListener { chooseMedia(activity, external = false) }
        button.setOnLongClickListener {
            chooseMedia(activity, external = true)
            true
        }
    }

    private fun chooseMedia(activity: MainActivity, external: Boolean) {
        val items = mediaItems(activity)
        if (items.isEmpty()) return

        if (items.size == 1) {
            if (external) playExternalFiltered(activity, items.first()) else playMx(activity, items.first())
            return
        }

        val labels = items.mapIndexed { index, item ->
            val kind = fieldString(item, "kind").ifBlank { "MEDIA" }
            val url = fieldString(item, "url")
            val name = runCatching {
                android.webkit.URLUtil.guessFileName(url, null, fieldString(item, "mime"))
            }.getOrDefault("Media ${index + 1}")
            "$kind • $name"
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle(if (external) "Pilih media • Pemutar lain" else "Pilih media • MX Player")
            .setItems(labels) { _, index ->
                if (external) playExternalFiltered(activity, items[index]) else playMx(activity, items[index])
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun mediaItems(activity: MainActivity): List<Any> = runCatching {
        val field = MainActivity::class.java.getDeclaredField("mediaItems").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(activity) as LinkedHashMap<String, Any>
        synchronized(map) { map.values.toList() }
    }.getOrDefault(emptyList())

    private fun playMx(activity: MainActivity, item: Any) {
        val url = fieldString(item, "url")
        val mime = fieldString(item, "mime").ifBlank { "video/*" }
        if (url.isBlank()) return

        val base = mediaIntent(activity, url, mime)
        for (pkg in mxPackages) {
            try {
                activity.startActivity(Intent(base).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next MX Player package.
            } catch (_: SecurityException) {
                // Try the next package.
            }
        }

        Toast.makeText(activity, "MX Player tidak ditemukan • tahan tombol media untuk pemutar lain", Toast.LENGTH_LONG).show()
    }

    private fun playExternalFiltered(activity: MainActivity, item: Any) {
        val url = fieldString(item, "url")
        val mime = fieldString(item, "mime").ifBlank { "video/*" }
        if (url.isBlank()) return

        val base = mediaIntent(activity, url, mime)
        val pm = activity.packageManager
        val candidates = pm.queryIntentActivities(base, 0)
            .filter { info ->
                val pkg = info.activityInfo.packageName
                pkg != activity.packageName &&
                    pkg !in blockedBrowserPackages &&
                    !looksLikeBrowser(pkg)
            }
            .distinctBy { it.activityInfo.packageName }

        if (candidates.isEmpty()) {
            Toast.makeText(activity, "Tidak ada media player lain yang kompatibel", Toast.LENGTH_SHORT).show()
            return
        }

        if (candidates.size == 1) {
            val info = candidates.first().activityInfo
            runCatching {
                activity.startActivity(Intent(base).setClassName(info.packageName, info.name))
            }.onFailure {
                Toast.makeText(activity, "Gagal membuka media player", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val labels = candidates.map { it.loadLabel(pm).toString() }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Buka dengan media player")
            .setItems(labels) { _, index ->
                val info = candidates[index].activityInfo
                runCatching {
                    activity.startActivity(Intent(base).setClassName(info.packageName, info.name))
                }.onFailure {
                    Toast.makeText(activity, "Gagal membuka ${labels[index]}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun mediaIntent(activity: MainActivity, url: String, mime: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", android.webkit.URLUtil.guessFileName(url, null, mime))
            currentPageUrl(activity)?.takeIf { it.startsWith("http") }?.let {
                putExtra("referer", it)
                putExtra("headers", arrayOf("Referer", it))
            }
        }
    }

    private fun currentPageUrl(activity: MainActivity): String? = runCatching {
        val field = MainActivity::class.java.getDeclaredField("currentPageUrl").apply { isAccessible = true }
        field.get(activity) as? String
    }.getOrNull()

    private fun fieldString(item: Any, name: String): String = runCatching {
        item.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(item)?.toString().orEmpty()
    }.getOrDefault("")

    private fun looksLikeBrowser(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("browser") || p.contains("chrome") || p.contains("firefox") ||
            p.contains("opera") || p.contains("brave") || p.contains("vivaldi") ||
            p.contains("edge") || p.contains("duckduckgo")
    }

    private fun findMediaButton(root: View): Button? {
        if (root is Button) {
            val text = root.text?.toString().orEmpty()
            if (text.startsWith("↓") || text.startsWith("▶") || text.startsWith("▷")) return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findMediaButton(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}