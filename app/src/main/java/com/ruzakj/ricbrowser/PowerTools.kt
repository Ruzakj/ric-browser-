package com.ruzakj.ricbrowser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PowerTools {
    private const val PREFS = "ric_power_tools"
    private const val KEY_SEARCH = "search_engine"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_LAST_HOST = "last_host"
    private const val KEY_PROFILE = "profile_"
    private const val KEY_DESKTOP = "desktop_"
    private const val KEY_JS = "js_"
    private const val KEY_IMAGES = "images_"
    private const val KEY_COOKIES = "cookies_"
    private const val KEY_CUSTOM_UA = "custom_ua_"

    private val attached = java.util.WeakHashMap<Activity, Boolean>()

    fun attach(activity: Activity) {
        if (activity !is AppCompatActivity || attached[activity] == true) return
        val webView = findView<WebView>(activity.window.decorView) ?: return
        attached[activity] = true
        installPowerButton(activity, webView)
        installGestures(activity, webView)
        installSearchEngine(activity, webView)
        applySavedSiteSettings(activity, webView)
    }

    private fun installPowerButton(activity: AppCompatActivity, webView: WebView) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val button = Button(activity).apply {
            text = "⚡"
            textSize = 18f
            isAllCaps = false
            alpha = 0.88f
            setOnClickListener { showToolbox(activity, webView) }
            setOnLongClickListener { showSiteProfiles(activity, webView); true }
        }
        val size = dp(activity, 48)
        val params = if (decor is FrameLayout) {
            FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(activity, 14)
                bottomMargin = dp(activity, 22)
            }
        } else ViewGroup.LayoutParams(size, size)
        decor.addView(button, params)
    }

    private fun installGestures(activity: AppCompatActivity, webView: WebView) {
        var downX = 0f
        var downY = 0f
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > dp(activity, 130) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        if (dx > 0 && webView.canGoBack()) { webView.goBack(); toast(activity, "Back") }
                        else if (dx < 0 && webView.canGoForward()) { webView.goForward(); toast(activity, "Forward") }
                    }
                }
            }
            false
        }
    }

    private fun installSearchEngine(activity: AppCompatActivity, webView: WebView) {
        val address = findView<EditText>(activity.window.decorView) ?: return
        address.setOnEditorActionListener { v, _, _ ->
            val raw = v.text.toString().trim()
            if (raw.isBlank()) return@setOnEditorActionListener true
            val url = when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                raw.contains('.') && !raw.contains(' ') -> "https://$raw"
                else -> searchUrl(activity, raw)
            }
            webView.loadUrl(url)
            v.clearFocus()
            true
        }
    }

    private fun showToolbox(activity: AppCompatActivity, webView: WebView) {
        val host = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val desktop = prefs.getBoolean(KEY_DESKTOP + host, false)
        val js = prefs.getBoolean(KEY_JS + host, true)
        val images = prefs.getBoolean(KEY_IMAGES + host, true)
        val items = arrayOf(
            "Site controls",
            "Find in page",
            "Reader mode",
            if (desktop) "Desktop mode: ON" else "Desktop mode: OFF",
            if (js) "JavaScript: ON" else "JavaScript: OFF",
            if (images) "Images: ON" else "Images: OFF",
            "Translate page",
            "Share / copy clean URL",
            "Bookmark this page",
            "Bookmarks",
            "Save offline (.mht)",
            "Screenshot page",
            "Page source",
            "Developer info",
            "Search engine",
            "Open incognito",
            "Clear site data",
            "Site profiles"
        )
        AlertDialog.Builder(activity).setTitle("Ric Toolbox • ${host.ifBlank { "Page" }}").setItems(items) { _, which ->
            when (which) {
                0 -> showSiteControls(activity, webView)
                1 -> showFind(activity, webView)
                2 -> toggleReader(activity, webView)
                3 -> toggleDesktop(activity, webView)
                4 -> toggleJs(activity, webView)
                5 -> toggleImages(activity, webView)
                6 -> translatePage(activity, webView)
                7 -> shareCleanUrl(activity, webView)
                8 -> addBookmark(activity, webView)
                9 -> showBookmarks(activity, webView)
                10 -> saveOffline(activity, webView)
                11 -> screenshot(activity, webView)
                12 -> showSource(activity, webView)
                13 -> showDeveloperInfo(activity, webView)
                14 -> chooseSearchEngine(activity)
                15 -> openIncognito(activity, webView.url)
                16 -> clearSiteData(activity, webView)
                17 -> showSiteProfiles(activity, webView)
            }
        }.show()
    }

    private fun showSiteControls(activity: AppCompatActivity, webView: WebView) {
        val host = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cookies = prefs.getBoolean(KEY_COOKIES + host, true)
        val customUa = prefs.getString(KEY_CUSTOM_UA + host, "").orEmpty()
        val message = buildString {
            append("Site: ").append(host.ifBlank { "unknown" })
            append("\nJavaScript: ").append(if (webView.settings.javaScriptEnabled) "ON" else "OFF")
            append("\nImages: ").append(if (webView.settings.loadsImagesAutomatically) "ON" else "OFF")
            append("\nCookies: ").append(if (cookies) "ON" else "OFF")
            append("\nUser agent: ").append(if (customUa.isBlank()) "Default" else "Custom")
            append("\n\nAd/tracker blocking remains handled by the baseline Ric Browser blocker.")
        }
        AlertDialog.Builder(activity).setTitle("Site control").setMessage(message)
            .setItems(arrayOf("Toggle cookies", "Set custom user-agent", "Reset site settings")) { _, which ->
                when (which) {
                    0 -> toggleCookies(activity, webView)
                    1 -> setCustomUserAgent(activity, webView)
                    2 -> resetSiteSettings(activity, webView)
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun showFind(activity: AppCompatActivity, webView: WebView) {
        val input = EditText(activity).apply { hint = "Find text"; setSingleLine(true) }
        AlertDialog.Builder(activity).setTitle("Find in page").setView(input)
            .setPositiveButton("Find") { _, _ -> webView.findAllAsync(input.text.toString()); webView.showFindDialog(input.text.toString(), true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toggleReader(activity: AppCompatActivity, webView: WebView) {
        val script = """
            (()=>{const id='__ric_reader_style';const old=document.getElementById(id);if(old){old.remove();return 'off';}const s=document.createElement('style');s.id=id;s.textContent='body{max-width:820px!important;margin:0 auto!important;padding:24px!important;font-size:19px!important;line-height:1.65!important;background:#fafafa!important;color:#202124!important} nav,header,footer,aside,[role=navigation],.adsbygoogle,iframe{display:none!important} img,video{max-width:100%!important;height:auto!important}';document.documentElement.appendChild(s);return 'on';})()
        """.trimIndent()
        webView.evaluateJavascript(script) { toast(activity, if (it.contains("on")) "Reader mode on" else "Reader mode off") }
    }

    private fun toggleDesktop(activity: AppCompatActivity, webView: WebView) {
        val h = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = !prefs.getBoolean(KEY_DESKTOP + h, false)
        prefs.edit().putBoolean(KEY_DESKTOP + h, enabled).apply()
        applyDesktop(webView, enabled, prefs.getString(KEY_CUSTOM_UA + h, "").orEmpty())
        webView.reload()
        toast(activity, if (enabled) "Desktop mode on" else "Desktop mode off")
    }

    private fun toggleJs(activity: AppCompatActivity, webView: WebView) {
        val h = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = !webView.settings.javaScriptEnabled
        webView.settings.javaScriptEnabled = value
        prefs.edit().putBoolean(KEY_JS + h, value).apply()
        webView.reload()
        toast(activity, "JavaScript ${if (value) "on" else "off"}")
    }

    private fun toggleImages(activity: AppCompatActivity, webView: WebView) {
        val h = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = !webView.settings.loadsImagesAutomatically
        webView.settings.loadsImagesAutomatically = value
        webView.settings.blockNetworkImage = !value
        prefs.edit().putBoolean(KEY_IMAGES + h, value).apply()
        webView.reload()
        toast(activity, "Images ${if (value) "on" else "off"}")
    }

    private fun toggleCookies(activity: AppCompatActivity, webView: WebView) {
        val h = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = !prefs.getBoolean(KEY_COOKIES + h, true)
        prefs.edit().putBoolean(KEY_COOKIES + h, value).apply()
        CookieManager.getInstance().setAcceptCookie(value)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, value)
        toast(activity, "Cookies ${if (value) "on" else "off"}")
    }

    private fun translatePage(activity: AppCompatActivity, webView: WebView) {
        val url = webView.url ?: return
        webView.loadUrl("https://translate.google.com/translate?sl=auto&tl=id&u=" + Uri.encode(url))
    }

    private fun shareCleanUrl(activity: AppCompatActivity, webView: WebView) {
        val clean = cleanUrl(webView.url ?: return)
        val choices = arrayOf("Share", "Copy")
        AlertDialog.Builder(activity).setTitle(clean).setItems(choices) { _, which ->
            if (which == 0) {
                val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, clean) }
                activity.startActivity(Intent.createChooser(intent, "Share link"))
            } else copy(activity, clean)
        }.show()
    }

    private fun addBookmark(activity: AppCompatActivity, webView: WebView) {
        val url = webView.url ?: return
        val title = webView.title?.take(80).orEmpty().ifBlank { host(url) }
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_BOOKMARKS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add("$title\t$url")
        prefs.edit().putStringSet(KEY_BOOKMARKS, set).apply()
        toast(activity, "Bookmarked")
    }

    private fun showBookmarks(activity: AppCompatActivity, webView: WebView) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_BOOKMARKS, emptySet()).orEmpty().sorted()
        if (values.isEmpty()) { toast(activity, "No bookmarks"); return }
        val labels = values.map { it.substringBefore('\t') }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Bookmarks").setItems(labels) { _, i -> webView.loadUrl(values[i].substringAfter('\t')) }
            .setNeutralButton("Clear all") { _, _ -> prefs.edit().remove(KEY_BOOKMARKS).apply() }
            .setNegativeButton("Close", null).show()
    }

    private fun saveOffline(activity: AppCompatActivity, webView: WebView) {
        val root = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: activity.filesDir
        val dir = File(root, "RicBrowserOffline").apply { mkdirs() }
        val name = "page_${System.currentTimeMillis()}.mht"
        webView.saveWebArchive(File(dir, name).absolutePath, false) { path ->
            toast(activity, if (path.isNullOrBlank()) "Offline save failed" else "Saved offline: $name")
        }
    }

    private fun screenshot(activity: AppCompatActivity, webView: WebView) {
        try {
            val width = webView.width.coerceAtLeast(1)
            val fullHeight = (webView.contentHeight * webView.scale).toInt().coerceAtLeast(webView.height)
            val height = fullHeight.coerceAtMost(12000)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            val root = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: activity.cacheDir
            val dir = File(root, "RicBrowserScreenshots").apply { mkdirs() }
            val file = File(dir, "ric_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
            bitmap.recycle()
            toast(activity, "Screenshot saved: ${file.name}")
        } catch (e: Throwable) { toast(activity, "Screenshot failed") }
    }

    private fun showSource(activity: AppCompatActivity, webView: WebView) {
        webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val text = raw.orEmpty().replace("\\n", "\n").replace("\\\"", "\"").trim('"').take(50000)
            val box = TextView(activity).apply { this.text = text; setTextIsSelectable(true); setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8)) }
            AlertDialog.Builder(activity).setTitle("Page source (first 50 KB)").setView(box).setPositiveButton("Copy") { _, _ -> copy(activity, text) }.setNegativeButton("Close", null).show()
        }
    }

    private fun showDeveloperInfo(activity: AppCompatActivity, webView: WebView) {
        val history = webView.copyBackForwardList()
        val cookies = CookieManager.getInstance().getCookie(webView.url ?: "").orEmpty().isNotBlank()
        val msg = "URL: ${webView.url}\nTitle: ${webView.title}\nHistory entries: ${history.size}\nJavaScript: ${webView.settings.javaScriptEnabled}\nImages: ${webView.settings.loadsImagesAutomatically}\nCookies present: $cookies\n\nUser-Agent:\n${webView.settings.userAgentString}"
        AlertDialog.Builder(activity).setTitle("Developer info").setMessage(msg).setPositiveButton("Copy URL") { _, _ -> copy(activity, webView.url.orEmpty()) }.setNegativeButton("Close", null).show()
    }

    private fun chooseSearchEngine(activity: AppCompatActivity) {
        val names = arrayOf("Google", "DuckDuckGo", "Bing", "Brave Search")
        val values = arrayOf("google", "duck", "bing", "brave")
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = values.indexOf(prefs.getString(KEY_SEARCH, "google")).coerceAtLeast(0)
        AlertDialog.Builder(activity).setTitle("Search engine").setSingleChoiceItems(names, current) { dialog, which ->
            prefs.edit().putString(KEY_SEARCH, values[which]).apply(); dialog.dismiss(); toast(activity, "Search: ${names[which]}")
        }.show()
    }

    private fun openIncognito(activity: AppCompatActivity, url: String?) {
        activity.startActivity(Intent(activity, IncognitoActivity::class.java).putExtra("url", url ?: "https://www.google.com"))
    }

    private fun clearSiteData(activity: AppCompatActivity, webView: WebView) {
        val url = webView.url ?: return
        CookieManager.getInstance().setCookie(url, "")
        CookieManager.getInstance().removeSessionCookies(null)
        webView.clearCache(true)
        webView.clearFormData()
        toast(activity, "Site data cleared")
    }

    private fun showSiteProfiles(activity: AppCompatActivity, webView: WebView) {
        val names = arrayOf("Normal", "Fast", "Private", "Desktop", "Media")
        AlertDialog.Builder(activity).setTitle("Site profile").setItems(names) { _, which ->
            when (which) {
                0 -> applyProfile(activity, webView, "normal", true, true, true, false)
                1 -> applyProfile(activity, webView, "fast", true, false, true, false)
                2 -> applyProfile(activity, webView, "private", true, true, false, false)
                3 -> applyProfile(activity, webView, "desktop", true, true, true, true)
                4 -> applyProfile(activity, webView, "media", true, true, true, false)
            }
        }.show()
    }

    private fun applyProfile(activity: AppCompatActivity, webView: WebView, name: String, js: Boolean, images: Boolean, cookies: Boolean, desktop: Boolean) {
        val h = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PROFILE + h, name).putBoolean(KEY_JS + h, js).putBoolean(KEY_IMAGES + h, images)
            .putBoolean(KEY_COOKIES + h, cookies).putBoolean(KEY_DESKTOP + h, desktop).apply()
        applySavedSiteSettings(activity, webView)
        webView.reload()
        toast(activity, "Profile: $name")
    }

    private fun setCustomUserAgent(activity: AppCompatActivity, webView: WebView) {
        val h = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val input = EditText(activity).apply { setText(prefs.getString(KEY_CUSTOM_UA + h, "")); hint = "Leave empty for default" }
        AlertDialog.Builder(activity).setTitle("Custom user-agent").setView(input).setPositiveButton("Save") { _, _ ->
            val value = input.text.toString().trim()
            prefs.edit().putString(KEY_CUSTOM_UA + h, value).apply()
            applySavedSiteSettings(activity, webView)
            webView.reload()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun resetSiteSettings(activity: AppCompatActivity, webView: WebView) {
        val h = host(webView.url)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PROFILE + h).remove(KEY_DESKTOP + h).remove(KEY_JS + h).remove(KEY_IMAGES + h).remove(KEY_COOKIES + h).remove(KEY_CUSTOM_UA + h).apply()
        webView.settings.javaScriptEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.blockNetworkImage = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        applyDesktop(webView, false, "")
        webView.reload()
        toast(activity, "Site settings reset")
    }

    private fun applySavedSiteSettings(activity: AppCompatActivity, webView: WebView) {
        val h = host(webView.url)
        if (h.isBlank()) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val js = prefs.getBoolean(KEY_JS + h, true)
        val images = prefs.getBoolean(KEY_IMAGES + h, true)
        val cookies = prefs.getBoolean(KEY_COOKIES + h, true)
        val desktop = prefs.getBoolean(KEY_DESKTOP + h, false)
        val customUa = prefs.getString(KEY_CUSTOM_UA + h, "").orEmpty()
        webView.settings.javaScriptEnabled = js
        webView.settings.loadsImagesAutomatically = images
        webView.settings.blockNetworkImage = !images
        CookieManager.getInstance().setAcceptCookie(cookies)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, cookies)
        applyDesktop(webView, desktop, customUa)
        prefs.edit().putString(KEY_LAST_HOST, h).apply()
    }

    private fun applyDesktop(webView: WebView, desktop: Boolean, customUa: String) {
        val defaultUa = WebSettings.getDefaultUserAgent(webView.context).replace("; wv", "")
        webView.settings.userAgentString = when {
            customUa.isNotBlank() -> customUa
            desktop -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
            else -> defaultUa
        }
        webView.settings.useWideViewPort = desktop
        webView.settings.loadWithOverviewMode = desktop
    }

    private fun searchUrl(context: Context, query: String): String {
        val engine = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEARCH, "google")
        val q = Uri.encode(query)
        return when (engine) {
            "duck" -> "https://duckduckgo.com/?q=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            "brave" -> "https://search.brave.com/search?q=$q"
            else -> "https://www.google.com/search?q=$q"
        }
    }

    private fun cleanUrl(raw: String): String {
        return try {
            val uri = Uri.parse(raw)
            val blocked = setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid", "mc_cid", "mc_eid")
            val builder = uri.buildUpon().clearQuery()
            uri.queryParameterNames.filterNot { blocked.contains(it.lowercase(Locale.ROOT)) }.forEach { key ->
                uri.getQueryParameters(key).forEach { value -> builder.appendQueryParameter(key, value) }
            }
            builder.build().toString()
        } catch (_: Exception) { raw }
    }

    private fun host(url: String?): String = try { Uri.parse(url ?: "").host?.removePrefix("www.")?.lowercase(Locale.ROOT).orEmpty() } catch (_: Exception) { "" }

    private fun copy(context: Context, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Ric Browser", text))
        toast(context, "Copied")
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private inline fun <reified T : View> findView(root: View): T? {
        if (root is T) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findView<T>(root.getChildAt(i))?.let { return it }
        return null
    }
}

class IncognitoActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val close = Button(this).apply { text = "×"; setOnClickListener { finish() } }
        val title = TextView(this).apply { text = "Incognito • Ric Browser"; textSize = 16f; setPadding(16, 0, 0, 0) }
        bar.addView(close, LinearLayout.LayoutParams(dp(this, 48), dp(this, 48)))
        bar.addView(title, LinearLayout.LayoutParams(0, dp(this, 48), 1f))
        webView = WebView(this)
        root.addView(bar)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = false
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.userAgentString = webView.settings.userAgentString.replace("; wv", "")
        webView.webViewClient = android.webkit.WebViewClient()
        webView.loadUrl(intent.getStringExtra("url") ?: "https://www.google.com")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.clearHistory(); webView.clearCache(true); webView.clearFormData(); webView.loadUrl("about:blank"); webView.destroy()
        }
        CookieManager.getInstance().removeSessionCookies(null)
        super.onDestroy()
    }
}
