package com.ruzakj.ricbrowser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar

    @Volatile
    private var currentPageUrl: String? = null

    private var webViewDestroyed = false
    private var transientDataCleared = false

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

        address = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { v, _, _ ->
                load(v.text.toString())
                true
            }
        }

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
            address,
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
                val scheme = uri.scheme?.lowercase().orEmpty()

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
                return try {
                    if (AdBlocker.shouldBlock(request.url.toString(), currentPageUrl)) {
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
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                currentPageUrl = url
                address.setText(url)
                view.evaluateJavascript(COSMETIC_AD_GUARD, null)
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!webViewDestroyed) {
                    webViewDestroyed = true
                    runCatching { view.destroy() }
                }
                Toast.makeText(this@MainActivity, "WebView restarted", Toast.LENGTH_SHORT).show()
                recreate()
                return true
            }
        }

        val initialUrl = intent?.dataString?.takeIf { it.isNotBlank() } ?: "https://www.google.com"
        load(initialUrl)
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

    private fun clearTransientBrowserData() {
        if (transientDataCleared || !::webView.isInitialized || webViewDestroyed) return
        transientDataCleared = true
        runCatching { webView.clearCache(true) }
        runCatching { webView.clearHistory() }
        runCatching { webView.clearFormData() }
        runCatching { CookieManager.getInstance().flush() }
    }

    private fun isYouTube(url: String): Boolean = try {
        val host = Uri.parse(url).host?.lowercase() ?: return false
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

    companion object {
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
