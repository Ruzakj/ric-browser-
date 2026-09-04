package com.ruzakj.ricbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONTokener
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var mediaButton: Button

    @Volatile
    private var currentPageUrl: String? = null

    private var webViewDestroyed = false
    private var transientDataCleared = false
    private var pendingDownload: MediaItem? = null

    private val mediaLock = Any()
    private val mediaItems = LinkedHashMap<String, MediaItem>()
    private val mediaHandler = Handler(Looper.getMainLooper())

    private val mediaScanner = object : Runnable {
        override fun run() {
            if (!webViewDestroyed && ::webView.isInitialized) {
                scanDomMedia()
                mediaHandler.postDelayed(this, MEDIA_SCAN_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        address = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { v, _, _ ->
                load(v.text.toString())
                true
            }
        }

        mediaButton = Button(this).apply {
            text = "⬇ Media"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { showMediaList() }
        }

        toolbar.addView(
            address,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        toolbar.addView(
            mediaButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52))
        )

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        )
        root.addView(
            progress,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        )
        root.addView(webView)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
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
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) ProgressBar.GONE else ProgressBar.VISIBLE
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()

                if (scheme == "http" || scheme == "https") {
                    if (AdBlocker.shouldBlock(uri.toString(), currentPageUrl)) return true
                    return false
                }

                return openExternal(uri)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val requestUrl = request.url.toString()
                tryRecordMedia(requestUrl)

                return try {
                    if (AdBlocker.shouldBlock(requestUrl, currentPageUrl)) {
                        AdBlocker.emptyResponse()
                    } else {
                        super.shouldInterceptRequest(view, request)
                    }
                } catch (_: Throwable) {
                    super.shouldInterceptRequest(view, request)
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentPageUrl = url
                address.setText(url)
                clearDetectedMedia()
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                currentPageUrl = url
                address.setText(url)
                view.evaluateJavascript(COSMETIC_AD_GUARD, null)
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
                scanDomMedia()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!webViewDestroyed) {
                    webViewDestroyed = true
                    mediaHandler.removeCallbacks(mediaScanner)
                    runCatching { view.destroy() }
                }
                Toast.makeText(this@MainActivity, "WebView restarted", Toast.LENGTH_SHORT).show()
                recreate()
                return true
            }
        }

        val initialUrl = intent?.dataString?.takeIf { it.isNotBlank() } ?: "https://www.google.com"
        load(initialUrl)
        mediaHandler.postDelayed(mediaScanner, MEDIA_SCAN_INTERVAL_MS)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openExternal(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun load(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return

        val url = when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.contains(".") && !value.contains(" ") -> "https://$value"
            else -> "https://www.google.com/search?q=" + Uri.encode(value)
        }
        currentPageUrl = url
        webView.loadUrl(url)
    }

    private fun clearDetectedMedia() {
        synchronized(mediaLock) { mediaItems.clear() }
        updateMediaButton()
    }

    private fun updateMediaButton() {
        runOnUiThread {
            val count = synchronized(mediaLock) { mediaItems.size }
            mediaButton.text = if (count > 0) "⬇ Media $count" else "⬇ Media"
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
            if (mediaItems.containsKey(item.url)) false
            else {
                if (mediaItems.size >= MAX_MEDIA_ITEMS) {
                    mediaItems.remove(mediaItems.keys.firstOrNull())
                }
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
        val mimeQuery = runCatching { uri.getQueryParameter("mime")?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        val typeQuery = runCatching { uri.getQueryParameter("type")?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        val combinedMime = "$mimeQuery $typeQuery ${Uri.decode(lower)}"

        val extension = path.substringAfterLast('.', "").substringBefore('/')
        return when {
            extension in VIDEO_EXTENSIONS || combinedMime.contains("video/") ->
                MediaItem(url, MediaKind.VIDEO, mimeForVideo(extension))

            extension in AUDIO_EXTENSIONS || combinedMime.contains("audio/") ->
                MediaItem(url, MediaKind.AUDIO, mimeForAudio(extension))

            extension == "m3u8" || lower.contains(".m3u8?") || combinedMime.contains("mpegurl") ->
                MediaItem(url, MediaKind.STREAM, "application/vnd.apple.mpegurl")

            extension == "mpd" || lower.contains(".mpd?") || combinedMime.contains("dash+xml") ->
                MediaItem(url, MediaKind.STREAM, "application/dash+xml")

            uri.host?.endsWith("googlevideo.com", true) == true && uri.path?.contains("videoplayback") == true -> {
                when {
                    combinedMime.contains("audio") -> MediaItem(url, MediaKind.AUDIO, "audio/*")
                    combinedMime.contains("video") -> MediaItem(url, MediaKind.VIDEO, "video/*")
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun mimeForVideo(ext: String): String = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        else -> "video/*"
    }

    private fun mimeForAudio(ext: String): String = when (ext) {
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
        webView.evaluateJavascript(MEDIA_SCAN_SCRIPT) { result ->
            parseMediaScanResult(result)
        }
    }

    private fun parseMediaScanResult(result: String?) {
        if (result.isNullOrBlank() || result == "null") return
        runCatching {
            val decoded = JSONTokener(result).nextValue()
            val jsonText = when (decoded) {
                is String -> decoded
                else -> decoded.toString()
            }
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                tryRecordMedia(array.optString(i))
            }
        }
    }

    private fun showMediaList() {
        val snapshot = synchronized(mediaLock) { mediaItems.values.toList() }
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "No video or audio detected yet", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = snapshot.mapIndexed { index, item ->
            val type = when (item.kind) {
                MediaKind.VIDEO -> "VIDEO"
                MediaKind.AUDIO -> "AUDIO"
                MediaKind.STREAM -> "STREAM"
            }
            val name = URLUtil.guessFileName(item.url, null, item.mime)
            "${index + 1}. $type • $name"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Detected media (${snapshot.size})")
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
                    0 -> playMedia(item, preferMxPlayer = true)
                    1 -> playMedia(item, preferMxPlayer = false)
                    2 -> requestDownload(item)
                    3 -> copyMediaLink(item.url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playMedia(item: MediaItem, preferMxPlayer: Boolean) {
        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.url), item.mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("title", URLUtil.guessFileName(item.url, null, item.mime))
            currentPageUrl?.let { putExtra("referer", it) }
        }

        if (preferMxPlayer) {
            for (packageName in MX_PLAYER_PACKAGES) {
                val mxIntent = Intent(baseIntent).apply { setPackage(packageName) }
                try {
                    startActivity(mxIntent)
                    return
                } catch (_: ActivityNotFoundException) {
                    // Try the next MX Player package.
                }
            }
            Toast.makeText(this, "MX Player not installed, opening player chooser", Toast.LENGTH_SHORT).show()
        }

        try {
            startActivity(Intent.createChooser(baseIntent, "Play media with"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No compatible media player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDownload(item: MediaItem) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = item
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST
            )
            return
        }
        enqueueDownload(item)
    }

    private fun enqueueDownload(item: MediaItem) {
        try {
            val uri = Uri.parse(item.url)
            val fileName = URLUtil.guessFileName(item.url, null, item.mime)
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setDescription("Downloaded by Ric Browser")
                .setMimeType(item.mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            CookieManager.getInstance().getCookie(item.url)?.takeIf { it.isNotBlank() }?.let {
                request.addRequestHeader("Cookie", it)
            }
            if (::webView.isInitialized && !webViewDestroyed) {
                request.addRequestHeader("User-Agent", webView.settings.userAgentString)
            }
            currentPageUrl?.takeIf { it.startsWith("http") }?.let {
                request.addRequestHeader("Referer", it)
            }

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Storage permission is required to download", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to start this download", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyMediaLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Media URL", url))
        Toast.makeText(this, "Media link copied", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            val item = pendingDownload
            pendingDownload = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && item != null) {
                enqueueDownload(item)
            } else {
                Toast.makeText(this, "Download cancelled: storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearTransientBrowserData() {
        if (transientDataCleared || !::webView.isInitialized || webViewDestroyed) return
        transientDataCleared = true
        runCatching { webView.clearCache(true) }
        runCatching { webView.clearHistory() }
        runCatching { webView.clearFormData() }
        runCatching { CookieManager.getInstance().flush() }
    }

    private fun isYouTube(url: String): Boolean = try {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return false
        host == "youtube.com" || host.endsWith(".youtube.com")
    } catch (_: Exception) {
        false
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && !webViewDestroyed && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onStop() {
        if (isFinishing) clearTransientBrowserData()
        super.onStop()
    }

    override fun onPause() {
        if (::webView.isInitialized && !webViewDestroyed) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized && !webViewDestroyed) webView.onResume()
    }

    override fun onDestroy() {
        mediaHandler.removeCallbacks(mediaScanner)
        if (::webView.isInitialized && !webViewDestroyed) {
            clearTransientBrowserData()
            webViewDestroyed = true
            runCatching { webView.stopLoading() }
            runCatching { webView.webChromeClient = null }
            runCatching { webView.webViewClient = WebViewClient() }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    private data class MediaItem(
        val url: String,
        val kind: MediaKind,
        val mime: String
    )

    private enum class MediaKind {
        VIDEO,
        AUDIO,
        STREAM
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST = 5101
        private const val MEDIA_SCAN_INTERVAL_MS = 2500L
        private const val MAX_MEDIA_ITEMS = 40

        private val MX_PLAYER_PACKAGES = arrayOf(
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro"
        )

        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "avi")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "mp4a", "aac", "ogg", "oga", "opus", "wav", "flac")

        private const val MEDIA_SCAN_SCRIPT = """
(() => {
  try {
    const found = new Set();
    const add = (u) => {
      if (!u || typeof u !== 'string') return;
      if (!/^https?:\/\//i.test(u)) return;
      const x = u.toLowerCase();
      if (/\.(mp4|m4v|webm|mkv|mov|3gp|avi|mp3|m4a|aac|ogg|oga|opus|wav|flac|m3u8|mpd)(\?|#|$)/i.test(x) ||
          x.includes('mime=video') || x.includes('mime=audio') ||
          x.includes('mime%3dvideo') || x.includes('mime%3daudio') ||
          x.includes('googlevideo.com/videoplayback')) {
        found.add(u);
      }
    };
    document.querySelectorAll('video,audio,source').forEach(el => {
      add(el.currentSrc); add(el.src); add(el.getAttribute && el.getAttribute('src'));
    });
    if (window.performance && performance.getEntriesByType) {
      performance.getEntriesByType('resource').forEach(e => add(e.name));
    }
    return JSON.stringify(Array.from(found).slice(-80));
  } catch (_) {
    return '[]';
  }
})();
"""

        private const val COSMETIC_AD_GUARD = """
(() => {
  if (window.__ricCosmeticGuard) return;
  window.__ricCosmeticGuard = true;
  const selectors = [
    '.adsbygoogle',
    '[id^="google_ads_"]',
    '[data-ad-client]',
    '[data-ad-slot]',
    'iframe[src*="doubleclick.net"]',
    'iframe[src*="googlesyndication.com"]',
    'iframe[src*="googleadservices.com"]',
    'iframe[src*="taboola.com"]',
    'iframe[src*="outbrain.com"]',
    'iframe[src*="adnxs.com"]',
    'iframe[src*="criteo.com"]'
  ];
  const clean = () => {
    try {
      document.querySelectorAll(selectors.join(',')).forEach(el => el.remove());
    } catch (_) {}
  };
  clean();
  new MutationObserver(clean).observe(document.documentElement, {childList:true, subtree:true});
})();
"""

        private const val YOUTUBE_GUARD = """
(() => {
  if (window.__ricYtGuard) return; window.__ricYtGuard = true;
  const skip = () => {
    try {
      document.querySelectorAll('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-slot,.ytp-ad-overlay-close-button').forEach(e => e.click());
      const v=document.querySelector('video.html5-main-video'), ad=document.querySelector('.ad-showing');
      if(ad && v){v.muted=true;if(isFinite(v.duration)&&v.duration>0)v.currentTime=Math.max(0,v.duration-0.05);}
      document.querySelectorAll('ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,ytd-in-feed-ad-layout-renderer,ytd-action-companion-ad-renderer').forEach(e=>e.remove());
    } catch(e) {}
  };
  new MutationObserver(skip).observe(document.documentElement,{subtree:true,childList:true,attributes:true});
  setInterval(skip,350); skip();
})();
"""
    }
}
