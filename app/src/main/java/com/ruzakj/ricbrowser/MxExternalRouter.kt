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

/** Forces the native media button's External action to MX Player, never a browser. */
object MxExternalRouter {
    private val mxPackages = arrayOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")

    fun attach(activity: Activity) {
        if (activity !is MainActivity) return
        val button = findMediaButton(activity.window.decorView) ?: return
        button.setOnClickListener { showNativeMedia(activity) }
    }

    private fun showNativeMedia(activity: MainActivity) {
        val items = nativeMedia(activity)
        if (items.isEmpty()) return
        val labels = items.mapIndexed { index, item ->
            val kind = field(item, "kind").ifBlank { "MEDIA" }
            val url = field(item, "url")
            "$kind • ${android.webkit.URLUtil.guessFileName(url, null, field(item, "mime"))}"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Media (${items.size})")
            .setItems(labels) { _, i -> actions(activity, items[i]) }
            .setNegativeButton("Tutup", null).show()
    }

    private fun actions(activity: MainActivity, item: Any) {
        AlertDialog.Builder(activity)
            .setTitle("Media action")
            .setItems(arrayOf("External • MX Player", "Download", "Copy link")) { _, i ->
                when (i) {
                    0 -> openMx(activity, item)
                    1 -> invokeNative(activity, "downloadMedia", item)
                    2 -> {
                        val url = field(item, "url")
                        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Media URL", url))
                        Toast.makeText(activity, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("Batal", null).show()
    }

    private fun openMx(activity: MainActivity, item: Any) {
        val url = field(item, "url")
        if (url.isBlank()) return
        val mime = field(item, "mime").ifBlank { guessMime(url) }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", android.webkit.URLUtil.guessFileName(url, null, mime))
            currentPage(activity)?.takeIf { it.startsWith("http") }?.let {
                putExtra("referer", it)
                putExtra("headers", arrayOf("Referer", it))
            }
        }
        for (pkg in mxPackages) {
            try {
                activity.startActivity(Intent(intent).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(activity, "MX Player / MX Player Pro tidak ditemukan", Toast.LENGTH_LONG).show()
    }

    private fun guessMime(url: String): String {
        val s = url.lowercase()
        return when {
            s.contains(".m3u8") -> "application/vnd.apple.mpegurl"
            s.contains(".mpd") -> "application/dash+xml"
            Regex("\\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)(?:[?#]|$)").containsMatchIn(s) -> "audio/*"
            else -> "video/*"
        }
    }

    private fun nativeMedia(activity: MainActivity): List<Any> = runCatching {
        val f = MainActivity::class.java.getDeclaredField("mediaItems").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = f.get(activity) as LinkedHashMap<String, Any>
        synchronized(map) { map.values.toList() }
    }.getOrDefault(emptyList())

    private fun currentPage(activity: MainActivity): String? = runCatching {
        val f = MainActivity::class.java.getDeclaredField("currentPageUrl").apply { isAccessible = true }
        f.get(activity) as? String
    }.getOrNull()

    private fun field(item: Any, name: String): String = runCatching {
        item.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(item)?.toString().orEmpty()
    }.getOrDefault("")

    private fun invokeNative(activity: MainActivity, name: String, item: Any) {
        runCatching {
            val m = MainActivity::class.java.declaredMethods.first { it.name == name && it.parameterTypes.size == 1 }
            m.isAccessible = true
            m.invoke(activity, item)
        }.onFailure { Toast.makeText(activity, "Action failed", Toast.LENGTH_SHORT).show() }
    }

    private fun findMediaButton(root: View): Button? {
        if (root is Button) {
            val t = root.text?.toString().orEmpty()
            if (t.startsWith("↓") || t.startsWith("▶") || t.startsWith("▷")) return root
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) findMediaButton(root.getChildAt(i))?.let { return it }
        return null
    }
}
