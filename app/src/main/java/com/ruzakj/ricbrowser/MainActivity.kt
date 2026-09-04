package com.ruzakj.ricbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var mediaButton: Button
    private lateinit var tabsRow: LinearLayout
    private lateinit var webContainer: FrameLayout

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = 0
    private var webViewDestroyed = true
    private var pendingDownload: MediaItem? = null

    @Volatile
    private var currentPageUrl: String? = null

    private val mediaLock = Any()
    private val mediaItems = LinkedHashMap<String, MediaItem>()
    private val mediaHandler = Handler(Looper.getMainLooper())

    private val mediaScanner = object : Runnable {
        override fun run() {
            if (!webViewDestroyed && ::webView.isInitialized) scanDomMedia()
            mediaHandler.postDelayed(this, MEDIA_SCAN_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = SURFACE_COLOR
        window.navigationBarColor = SURFACE_COLOR

        buildUi()
        restoreTabs()

        val incoming = intent?.dataString?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (incoming != null) {
            tabs.add(BrowserTab(incoming, hostLabel(incoming)))
            activeTabIndex = tabs.lastIndex
            saveTabs()
        }

        if (tabs.isEmpty()) tabs.add(BrowserTab(HOME_URL, "New tab"))
        activeTabIndex = activeTabIndex.coerceIn(0, tabs.lastIndex)
        renderTabs()
        openActiveTab()
        mediaHandler.postDelayed(mediaScanner, MEDIA_SCAN_INTERVAL_MS)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE_COLOR)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val backButton = toolbarButton("‹") { handleBack() }
        address = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            setTextSize(14f)
            setPadding(dp(14), 0, dp(14), 0)
            setSelectAllOnFocus(true)
            setTextColor(TEXT_COLOR)
            setHintTextColor(MUTED_TEXT_COLOR)
            background = roundedBackground(INPUT_COLOR, BORDER_COLOR, 18f)
            setOnEditorActionListener { v, _, _ ->
                loadInActiveTab(v.text.toString())
                true
            }
        }

        mediaButton = toolbarButton("↓") { showMediaList() }.apply { isEnabled = false }
        val newTabButton = toolbarButton("+") { newTab() }

        toolbar.addView(backButton, squareParams(42))
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        toolbar.addView(mediaButton, squareParams(42))
        toolbar.addView(newTabButton, squareParams(42).apply { marginStart = dp(4) })

        val tabScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(8), 0, dp(8), dp(5))
        }
        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroller.addView(tabsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        webContainer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        root.addView(tabScroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(43)))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        root.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun toolbarButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = if (label == "↓") 19f else 24f
        setTextColor(TEXT_COLOR)
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minHeight = 0
        background = roundedBackground(INPUT_COLOR, BORDER_COLOR, 18f)
        setOnClickListener { onClick() }
    }

    private fun renderTabs() {
        tabsRow.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val active = index == activeTabIndex
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
                background = roundedBackground(
                    if (active) ACTIVE_TAB_COLOR else INPUT_COLOR,
                    if (active) ACTIVE_BORDER_COLOR else BORDER_COLOR,
                    15f
                )
                setOnClickListener { switchToTab(index) }
            }
            val label = TextView(this).apply {
                text = tab.title.ifBlank { hostLabel(tab.url) }.take(22)
                textSize = 12f
                setTextColor(TEXT_COLOR)
                maxLines = 1
                setPadding(0, 0, dp(8), 0)
            }
            val close = TextView(this).apply {
                text = "×"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(MUTED_TEXT_COLOR)
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { closeTab(index) }
            }
            chip.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
            chip.addView(close, LinearLayout.LayoutParams(dp(30), dp(32)))
            tabsRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginEnd = dp(6)
            })
        }
    }

    private fun newTab(url: String = HOME_URL) {
        tabs.add(BrowserTab(url, "New tab"))
        activeTabIndex = tabs.lastIndex
        saveTabs()
        renderTabs()
        openActiveTab()
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices || index == activeTabIndex) return
        persistCurrentTabUrl()
        activeTabIndex = index
        saveTabs()
        renderTabs()
        openActiveTab()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        if (tabs.size == 1) {
            tabs[0] = BrowserTab(HOME_URL, "New tab")
            activeTabIndex = 0
        } else {
            tabs.removeAt(index)
            activeTabIndex = when {
                index < activeTabIndex -> activeTabIndex - 1
                activeTabIndex >= tabs.size -> tabs.lastIndex
                else -> activeTabIndex
            }
        }
        saveTabs()
        renderTabs()
        openActiveTab()
    }

    private fun persistCurrentTabUrl() {
        if (activeTabIndex !in tabs.indices) return
        val url = if (::webView.isInitialized && !webViewDestroyed) webView.url else currentPageUrl
        if (!url.isNullOrBlank() && url.startsWith("http")) tabs[activeTabIndex].url = url
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openActiveTab() {
        if (activeTabIndex !in tabs.indices) return
        destroyCurrentWebView(clearCache = false)
        clearDetectedMedia()

        val tab = tabs[activeTabIndex]
        currentPageUrl = tab.url
        address.setText(tab.url)

        webView = WebView(this)
        webViewDestroyed = false
        webView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view !== webView) return
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (view !== webView || activeTabIndex !in tabs.indices) return
                val clean = title?.trim().orEmpty()
                if (clean.isNotEmpty()) {
                    tabs[activeTabIndex].title = clean.take(40)
                    saveTabs()
                    renderTabs()
                }
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
                if (scheme == "http" || scheme == "https") {
                    return runCatching { AdBlocker.shouldBlock(uri.toString(), currentPageUrl) }.getOrDefault(false)
                }
                return openExternal(uri)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val requestUrl = request.url.toString()
                tryRecordMedia(requestUrl)
                return if (runCatching { AdBlocker.shouldBlock(requestUrl, currentPageUrl) }.getOrDefault(false)) {
                    AdBlocker.emptyResponse()
                } else {
                    super.shouldInterceptRequest(view, request)
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (view !== webView || activeTabIndex !in tabs.indices) return
                currentPageUrl = url
                tabs[activeTabIndex].url = url
                address.setText(url)
                clearDetectedMedia()
                saveTabs()
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (view !== webView || activeTabIndex !in tabs.indices) return
                currentPageUrl = url
                tabs[activeTabIndex].url = url
                address.setText(url)
                saveTabs()
                view.evaluateJavascript(COSMETIC_AD_GUARD, null)
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
                scanDomMedia()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) {
                    webViewDestroyed = true
                    runCatching { view.destroy() }
                    Toast.makeText(this@MainActivity, "WebView restarted", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                return true
            }
        }

        webContainer.removeAllViews()
        webContainer.addView(webView)
        webView.loadUrl(tab.url)
    }

    private fun loadInActiveTab(input: String) {
        val value = input.trim()
        if (value.isEmpty() || webViewDestroyed) return
        val url = when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.contains(".") && !value.contains(" ") -> "https://$value"
            else -> "https://www.google.com/search?q=" + Uri.encode(value)
        }
        currentPageUrl = url
        tabs.getOrNull(activeTabIndex)?.url = url
        saveTabs()
        webView.loadUrl(url)
    }

    private fun handleBack() {
        if (::webView.isInitialized && !webViewDestroyed && webView.canGoBack()) {
            webView.goBack()
        } else {
            saveTabs()
            moveTaskToBack(true)
        }
    }

    override fun onBackPressed() = handleBack()

    private fun clearBrowserCache() {
        if (::webView.isInitialized && !webViewDestroyed) {
            runCatching { webView.clearCache(true) }
        }
        runCatching { applicationContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { CookieManager.getInstance().flush() }
    }

    private fun destroyCurrentWebView(clearCache: Boolean) {
        if (!::webView.isInitialized || webViewDestroyed) return
        if (clearCache) runCatching { webView.clearCache(true) }
        webViewDestroyed = true
        runCatching { webContainer.removeView(webView) }
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.webChromeClient = null }
        runCatching { webView.webViewClient = WebViewClient() }
        runCatching { webView.clearHistory() }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
    }

    private fun restoreTabs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        activeTabIndex = prefs.getInt(KEY_ACTIVE_TAB, 0)
        val raw = prefs.getString(KEY_TABS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url").takeIf { it.startsWith("http") } ?: continue
                tabs.add(BrowserTab(url, item.optString("title").ifBlank { hostLabel(url) }))
                if (tabs.size >= MAX_TABS) break
            }
        }
    }

    private fun saveTabs() {
        if (tabs.isEmpty()) return
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(JSONObject().put("url", tab.url).put("title", tab.title))
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TABS, array.toString())
            .putInt(KEY_ACTIVE_TAB, activeTabIndex.coerceIn(0, tabs.lastIndex))
            .apply()
    }

    private fun hostLabel(url: String): String = runCatching {
        Uri.parse(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "New tab"
    }.getOrDefault("New tab")

    private fun clearDetectedMedia() {
        synchronized(mediaLock) { mediaItems.clear() }
        updateMediaButton()
    }

    private fun updateMediaButton() {
        runOnUiThread {
            val count = synchronized(mediaLock) { mediaItems.size }
            mediaButton.text = if (count > 0) "↓ $count" else "↓"
            mediaButton.isEnabled = count > 0
        }
    }

    private fun tryRecordMedia(rawUrl: String?) {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty() || url.startsWith("blob:", true) || url.startsWith("data:", true)) return
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        if (runCatching { AdBlocker.shouldBlock(url, currentPageUrl) }.getOrDefault(false)) return
        val item = classifyMedia(url) ?: return
        val added = synchronized(mediaLock) {
            if (mediaItems.containsKey(item.url)) false else {
                if (mediaItems.size >= MAX_MEDIA_ITEMS) mediaItems.remove(mediaItems.keys.firstOrNull())
                mediaItems[item.url] = item
                true
            }
        }
        if (added) updateMediaButton()
    }

    private fun classifyMedia(url: String): MediaItem? {
        val lower = url.lowercase(Locale.ROOT)
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        val ext = path.substringAfterLast('.', "")
        val mime = buildString {
            append(runCatching { uri.getQueryParameter("mime") }.getOrNull().orEmpty())
            append(' ')
            append(runCatching { uri.getQueryParameter("type") }.getOrNull().orEmpty())
            append(' ')
            append(Uri.decode(lower))
        }.lowercase(Locale.ROOT)

        return when {
            ext == "m3u8" || lower.contains(".m3u8?") || mime.contains("mpegurl") -> MediaItem(url, MediaKind.STREAM, "application/vnd.apple.mpegurl")
            ext == "mpd" || lower.contains(".mpd?") || mime.contains("dash+xml") -> MediaItem(url, MediaKind.STREAM, "application/dash+xml")
            ext in VIDEO_EXTENSIONS || mime.contains("video/") -> MediaItem(url, MediaKind.VIDEO, videoMime(ext))
            ext in AUDIO_EXTENSIONS || mime.contains("audio/") -> MediaItem(url, MediaKind.AUDIO, audioMime(ext))
            uri.host?.endsWith("googlevideo.com", true) == true && path.contains("videoplayback") -> when {
                mime.contains("audio") -> MediaItem(url, MediaKind.AUDIO, "audio/*")
                mime.contains("video") -> MediaItem(url, MediaKind.VIDEO, "video/*")
                else -> null
            }
            else -> null
        }
    }

    private fun videoMime(ext: String) = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        else -> "video/*"
    }

    private fun audioMime(ext: String) = when (ext) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "audio/*"
    }

    private fun scanDomMedia() {
        if (webViewDestroyed || !::webView.isInitialized) return
        webView.evaluateJavascript(MEDIA_SCAN_SCRIPT) { parseMediaScanResult(it) }
    }

    private fun parseMediaScanResult(result: String?) {
        if (result.isNullOrBlank() || result == "null") return
        runCatching {
            val decoded = JSONTokener(result).nextValue()
            val text = if (decoded is String) decoded else decoded.toString()
            val array = JSONArray(text)
            for (i in 0 until array.length()) tryRecordMedia(array.optString(i))
        }
    }

    private fun showMediaList() {
        val snapshot = synchronized(mediaLock) { mediaItems.values.toList() }
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "No video or audio detected yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = snapshot.map { item ->
            val kind = when (item.kind) {
                MediaKind.VIDEO -> "VIDEO"
                MediaKind.AUDIO -> "AUDIO"
                MediaKind.STREAM -> "STREAM"
            }
            "$kind • ${URLUtil.guessFileName(item.url, null, item.mime)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Media (${snapshot.size})")
            .setItems(labels) { _, which -> showMediaActions(snapshot[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showMediaActions(item: MediaItem) {
        val actions = arrayOf("Play MX Player", "Play external", "Download", "Copy link")
        AlertDialog.Builder(this)
            .setTitle(URLUtil.guessFileName(item.url, null, item.mime))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> playMedia(item, true)
                    1 -> playMedia(item, false)
                    2 -> requestDownload(item)
                    3 -> copyMediaLink(item.url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playMedia(item: MediaItem, preferMxPlayer: Boolean) {
        val base = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.url), item.mime)
            putExtra("title", URLUtil.guessFileName(item.url, null, item.mime))
            currentPageUrl?.let { putExtra("referer", it) }
        }
        if (preferMxPlayer) {
            for (pkg in MX_PLAYER_PACKAGES) {
                try {
                    startActivity(Intent(base).setPackage(pkg))
                    return
                } catch (_: ActivityNotFoundException) { }
            }
            Toast.makeText(this, "MX Player not installed", Toast.LENGTH_SHORT).show()
        }
        try {
            startActivity(Intent.createChooser(base, "Play media with"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No compatible player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDownload(item: MediaItem) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = item
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
            return
        }
        enqueueDownload(item)
    }

    private fun enqueueDownload(item: MediaItem) {
        try {
            val fileName = URLUtil.guessFileName(item.url, null, item.mime)
            val request = DownloadManager.Request(Uri.parse(item.url))
                .setTitle(fileName)
                .setDescription("Downloaded by Ric Browser")
                .setMimeType(item.mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            CookieManager.getInstance().getCookie(item.url)?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
            if (::webView.isInitialized && !webViewDestroyed) request.addRequestHeader("User-Agent", webView.settings.userAgentString)
            currentPageUrl?.takeIf { it.startsWith("http") }?.let { request.addRequestHeader("Referer", it) }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to start download", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyMediaLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Media URL", url))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            val item = pendingDownload
            pendingDownload = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && item != null) enqueueDownload(item)
            else Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternal(uri: Uri): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: Exception) {
        Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        true
    }

    private fun isYouTube(url: String): Boolean = runCatching {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT).orEmpty()
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }.getOrDefault(false)

    override fun onPause() {
        persistCurrentTabUrl()
        saveTabs()
        if (::webView.isInitialized && !webViewDestroyed) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized && !webViewDestroyed) webView.onResume()
    }

    override fun onStop() {
        persistCurrentTabUrl()
        saveTabs()
        clearBrowserCache()
        super.onStop()
    }

    override fun onDestroy() {
        mediaHandler.removeCallbacks(mediaScanner)
        persistCurrentTabUrl()
        saveTabs()
        clearBrowserCache()
        destroyCurrentWebView(clearCache = true)
        super.onDestroy()
    }

    private fun squareParams(size: Int) = LinearLayout.LayoutParams(dp(size), dp(size))

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    data class BrowserTab(var url: String, var title: String)
    data class MediaItem(val url: String, val kind: MediaKind, val mime: String)
    enum class MediaKind { VIDEO, AUDIO, STREAM }

    companion object {
        private const val PREFS_NAME = "ric_browser_tabs"
        private const val KEY_TABS = "tabs_json"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val HOME_URL = "https://www.google.com"
        private const val MAX_TABS = 20
        private const val MAX_MEDIA_ITEMS = 80
        private const val MEDIA_SCAN_INTERVAL_MS = 2500L
        private const val STORAGE_PERMISSION_REQUEST = 3021

        private val MX_PLAYER_PACKAGES = arrayOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "mp4a", "aac", "ogg", "oga", "opus", "wav", "flac")

        private val SURFACE_COLOR = Color.rgb(250, 250, 250)
        private val INPUT_COLOR = Color.rgb(245, 245, 245)
        private val BORDER_COLOR = Color.rgb(225, 225, 225)
        private val ACTIVE_TAB_COLOR = Color.rgb(232, 238, 255)
        private val ACTIVE_BORDER_COLOR = Color.rgb(180, 195, 245)
        private val TEXT_COLOR = Color.rgb(30, 30, 30)
        private val MUTED_TEXT_COLOR = Color.rgb(110, 110, 110)

        private const val MEDIA_SCAN_SCRIPT = """
(() => {
  const out = new Set();
  const add = v => {
    if (!v || typeof v !== 'string') return;
    try { v = new URL(v, location.href).href; } catch (_) { return; }
    if (/^https?:/i.test(v)) out.add(v);
  };
  document.querySelectorAll('video,audio,source').forEach(el => {
    add(el.currentSrc); add(el.src); add(el.getAttribute && el.getAttribute('src'));
  });
  try {
    performance.getEntriesByType('resource').forEach(r => {
      if (/\.(mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|oga|opus|wav|flac|m3u8|mpd)(?:[?#]|$)/i.test(r.name) || /googlevideo\.com\/videoplayback/i.test(r.name)) add(r.name);
    });
  } catch (_) {}
  return JSON.stringify(Array.from(out));
})()
"""

        private const val COSMETIC_AD_GUARD = """
(() => {
  if (window.__ricCosmeticGuard) return;
  window.__ricCosmeticGuard = true;
  const selectors=['.adsbygoogle','[id^="google_ads_"]','[data-ad-client]','[data-ad-slot]','iframe[src*="doubleclick.net"]','iframe[src*="googlesyndication.com"]','iframe[src*="googleadservices.com"]','iframe[src*="taboola.com"]','iframe[src*="outbrain.com"]','iframe[src*="adnxs.com"]','iframe[src*="criteo.com"]'];
  const clean=()=>{try{document.querySelectorAll(selectors.join(',')).forEach(el=>el.remove())}catch(_){}};
  clean(); new MutationObserver(clean).observe(document.documentElement,{childList:true,subtree:true});
})()
"""

        private const val YOUTUBE_GUARD = """
(() => {
  if (window.__ricYtGuard) return; window.__ricYtGuard=true;
  const skip=()=>{try{
    document.querySelectorAll('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-slot,.ytp-ad-overlay-close-button').forEach(e=>e.click());
    document.querySelectorAll('ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,ytd-in-feed-ad-layout-renderer,ytd-action-companion-ad-renderer').forEach(e=>e.remove());
  }catch(_){}};
  new MutationObserver(skip).observe(document.documentElement,{subtree:true,childList:true}); setInterval(skip,500); skip();
})()
"""
    }
}
