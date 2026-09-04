package com.ruzakj.ricbrowser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import io.github.edsuns.adfilter.AdFilter

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private val filter by lazy { AdFilter.get() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        address = EditText(this).apply {
            hint = "Search or enter address"
            singleLine = true
            setOnEditorActionListener { v, _, _ -> load(v.text.toString()); true }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        webView = WebView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(address, LinearLayout.LayoutParams(-1, 52))
        root.addView(progress, LinearLayout.LayoutParams(-1, 3))
        root.addView(webView)
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString.replace("; wv", "")
            setSupportMultipleWindows(false)
        }
        filter.setupWebView(webView)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) ProgressBar.GONE else ProgressBar.VISIBLE
            }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                filter.shouldIntercept(view, request).resourceResponse
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                filter.performScript(view, url)
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                address.setText(url)
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
            }
        }
        load(intent?.dataString ?: "https://www.google.com")
    }

    private fun load(input: String) {
        val value = input.trim()
        if (value.isEmpty()) return
        val url = when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.contains(".") && !value.contains(" ") -> "https://$value"
            else -> "https://www.google.com/search?q=" + Uri.encode(value)
        }
        webView.loadUrl(url)
    }

    private fun isYouTube(url: String): Boolean = try {
        val host = Uri.parse(url).host ?: return false
        host == "youtube.com" || host.endsWith(".youtube.com")
    } catch (_: Exception) { false }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    companion object {
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
