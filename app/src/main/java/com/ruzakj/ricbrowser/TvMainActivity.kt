package com.ruzakj.ricbrowser

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Android TV / Google TV entry point designed to be fully usable with a remote.
 * Phone/tablet continues to use MainActivity.
 */
class TvMainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var toolbar: LinearLayout

    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildTvUi()
        configureWebView()

        val start = intent?.dataString?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: HOME
        loadUrl(start)
        address.requestFocus()
    }

    private fun buildTvUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(28), dp(18), dp(28), dp(18))
        }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = tvButton("←") {
            if (webView.canGoBack()) webView.goBack()
        }
        val forward = tvButton("→") {
            if (webView.canGoForward()) webView.goForward()
        }
        val home = tvButton("⌂") { loadUrl(HOME) }
        val reload = tvButton("↻") { webView.reload() }

        address = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(170, 174, 184))
            setPadding(dp(22), 0, dp(22), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBackground(false)
            setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(110).start()
                background = focusBackground(focused)
                if (focused) {
                    currentUrl()?.let { setText(it); selectAll() }
                } else {
                    setText(displayUrl(currentUrl()))
                }
            }
            setOnEditorActionListener { _, _, _ ->
                loadFromAddress()
                true
            }
        }

        val go = tvButton("GO") { loadFromAddress() }
        val menu = tvButton("☰") { showTvMenu() }

        toolbar.addView(back, fixed(dp(62), dp(58)))
        toolbar.addView(forward, fixed(dp(62), dp(58)).apply { marginStart = dp(8) })
        toolbar.addView(home, fixed(dp(62), dp(58)).apply { marginStart = dp(8) })
        toolbar.addView(reload, fixed(dp(62), dp(58)).apply { marginStart = dp(8) })
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
            marginStart = dp(14); marginEnd = dp(14)
        })
        toolbar.addView(go, fixed(dp(82), dp(58)))
        toolbar.addView(menu, fixed(dp(70), dp(58)).apply { marginStart = dp(8) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        status = TextView(this).apply {
            text = "Remote: D-pad navigates • OK selects • Back returns • Search opens address bar"
            textSize = 13f
            setTextColor(Color.rgb(170, 174, 184))
            setPadding(dp(4), dp(8), 0, dp(8))
        }

        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
            setOnFocusChangeListener { view, focused ->
                if (focused) view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
        }

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(8) })
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        setContentView(root)

        // Native D-pad focus path across the toolbar and down into the page.
        address.nextFocusDownId = webView.id
        webView.nextFocusUpId = address.id
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase().orEmpty()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoaded = false
                status.text = "Loading…"
                if (!address.hasFocus()) address.setText(displayUrl(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                if (!address.hasFocus()) address.setText(displayUrl(url))
                injectRemoteNavigation()
                status.text = "D-pad: move focus • OK: open/click • Back: previous page • Search: address bar"
                view.requestFocus()
            }
        }
    }

    private fun loadFromAddress() {
        val raw = address.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return
        hideKeyboard()
        address.clearFocus()
        loadUrl(normalizeInput(raw))
        webView.requestFocus()
    }

    private fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    private fun normalizeInput(raw: String): String = when {
        raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
        raw.contains('.') && !raw.contains(' ') -> "https://$raw"
        else -> "https://www.google.com/search?q=${Uri.encode(raw)}"
    }

    private fun currentUrl(): String? = if (::webView.isInitialized) webView.url else null

    private fun displayUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching { Uri.parse(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)
    }

    private fun showTvMenu() {
        val options = arrayOf("Address / Search", "Home", "Reload", "Page up", "Page down", "Zoom +", "Zoom -", "Close")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ric Browser TV")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> focusAddress()
                    1 -> loadUrl(HOME)
                    2 -> webView.reload()
                    3 -> webView.pageUp(false)
                    4 -> webView.pageDown(false)
                    5 -> webView.zoomIn()
                    6 -> webView.zoomOut()
                    7 -> finish()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Back", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        // Global remote shortcuts that work regardless of current focus.
        when (event.keyCode) {
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_MENU -> {
                focusAddress()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                webView.evaluateJavascript("window.__ricTvMediaToggle&&window.__ricTvMediaToggle()", null)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                webView.pageUp(false)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                webView.pageDown(false)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (address.hasFocus()) {
                    hideKeyboard(); address.clearFocus(); webView.requestFocus(); return true
                }
                if (webView.canGoBack()) {
                    webView.goBack(); return true
                }
            }
        }

        if (webView.hasFocus() && pageLoaded) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveDomFocus("left"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveDomFocus("right"); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveDomFocus("down"); return true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // At the very top, Up returns to the native browser controls.
                    if (webView.scrollY <= dp(6)) focusAddress() else moveDomFocus("up")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    webView.evaluateJavascript("window.__ricTvClick&&window.__ricTvClick()", null)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveDomFocus(direction: String) {
        webView.evaluateJavascript("window.__ricTvMove&&window.__ricTvMove('$direction')", null)
    }

    private fun focusAddress() {
        address.requestFocus()
        address.selectAll()
        address.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun injectRemoteNavigation() {
        webView.evaluateJavascript(TV_REMOTE_JS, null)
    }

    private fun tvButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = if (label.length <= 2) 25f else 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minWidth = 0
        minHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        isFocusable = true
        background = focusBackground(false)
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.12f else 1f).scaleY(if (focused) 1.12f else 1f).setDuration(110).start()
            background = focusBackground(focused)
        }
    }

    private fun focusBackground(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(if (focused) FOCUS else PANEL)
        setStroke(dp(if (focused) 3 else 1), if (focused) Color.WHITE else BORDER)
    }

    private fun fixed(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val HOME = "https://www.google.com"
        private val BG = Color.rgb(10, 12, 16)
        private val PANEL = Color.rgb(28, 31, 39)
        private val BORDER = Color.rgb(65, 69, 80)
        private val FOCUS = Color.rgb(47, 94, 190)

        private const val TV_REMOTE_JS = """
(() => {
  if (window.__ricTvInstalled) return;
  window.__ricTvInstalled = true;
  const style = document.createElement('style');
  style.textContent = `
    :focus { outline: 5px solid #4f8cff !important; outline-offset: 5px !important; }
    [data-ric-tv-focus="1"] { outline: 5px solid #4f8cff !important; outline-offset: 5px !important; }
  `;
  (document.head || document.documentElement).appendChild(style);

  const selector = 'a[href],button,input,select,textarea,summary,[role="button"],[role="link"],[tabindex],video,audio';
  const visible = el => {
    const s = getComputedStyle(el), r = el.getBoundingClientRect();
    return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity || 1) > 0.05 && r.width >= 4 && r.height >= 4 && r.bottom >= 0 && r.right >= 0 && r.top <= innerHeight && r.left <= innerWidth && !el.disabled;
  };
  const items = () => Array.from(document.querySelectorAll(selector)).filter(visible);
  const center = r => ({x:r.left+r.width/2, y:r.top+r.height/2});
  const mark = el => {
    document.querySelectorAll('[data-ric-tv-focus="1"]').forEach(x => x.removeAttribute('data-ric-tv-focus'));
    if (!el) return false;
    el.setAttribute('data-ric-tv-focus','1');
    try { el.focus({preventScroll:true}); } catch (_) { try { el.focus(); } catch (_) {} }
    try { el.scrollIntoView({block:'center', inline:'center', behavior:'smooth'}); } catch (_) { el.scrollIntoView(); }
    return true;
  };

  window.__ricTvMove = dir => {
    const all = items(); if (!all.length) return false;
    let cur = document.activeElement;
    if (!cur || cur === document.body || cur === document.documentElement || !all.includes(cur)) {
      const cx = innerWidth/2, cy = innerHeight/2;
      cur = all.slice().sort((a,b) => {
        const A=center(a.getBoundingClientRect()), B=center(b.getBoundingClientRect());
        return Math.hypot(A.x-cx,A.y-cy)-Math.hypot(B.x-cx,B.y-cy);
      })[0];
      return mark(cur);
    }
    const c = center(cur.getBoundingClientRect());
    const ranked = all.filter(el => el !== cur).map(el => {
      const p = center(el.getBoundingClientRect());
      const dx=p.x-c.x, dy=p.y-c.y;
      const valid = dir==='left'?dx<-3:dir==='right'?dx>3:dir==='up'?dy<-3:dy>3;
      if (!valid) return null;
      const primary = (dir==='left'||dir==='right') ? Math.abs(dx) : Math.abs(dy);
      const cross = (dir==='left'||dir==='right') ? Math.abs(dy) : Math.abs(dx);
      return {el, score: primary + cross*2.4 + Math.hypot(dx,dy)*0.15};
    }).filter(Boolean).sort((a,b)=>a.score-b.score);
    if (ranked.length) return mark(ranked[0].el);
    if (dir==='down') { scrollBy({top:Math.round(innerHeight*.68),behavior:'smooth'}); }
    if (dir==='up') { scrollBy({top:-Math.round(innerHeight*.68),behavior:'smooth'}); }
    return false;
  };

  window.__ricTvClick = () => {
    const el = document.activeElement;
    if (!el || el===document.body || el===document.documentElement) return window.__ricTvMove('down');
    if (el.tagName==='VIDEO' || el.tagName==='AUDIO') { el.paused ? el.play() : el.pause(); return true; }
    try { el.click(); return true; } catch (_) { return false; }
  };

  window.__ricTvMediaToggle = () => {
    const media = document.querySelector('video,audio');
    if (!media) return false;
    media.paused ? media.play() : media.pause();
    return true;
  };
})();
"""
    }
}
