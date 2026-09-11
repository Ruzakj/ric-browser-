from pathlib import Path

p = Path('app/src/main/java/com/ruzakj/ricbrowser/MainActivity.kt')
s = p.read_text()

if 'import android.webkit.WebResourceResponse\n' not in s:
    s = s.replace('import android.webkit.WebResourceRequest\n', 'import android.webkit.WebResourceRequest\nimport android.webkit.WebResourceResponse\n')
if 'import java.io.ByteArrayInputStream\n' not in s:
    s = s.replace('import java.util.Locale\n', 'import java.util.Locale\nimport java.io.ByteArrayInputStream\n')

old = '''            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = super.shouldInterceptRequest(view, request).also {
                val requestUrl = request.url.toString()
                val referer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                if (isMediaRequestForCurrentPage(referer)) tryRecordMedia(requestUrl)
            }
'''
new = '''            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val requestUrl = request.url.toString()
                val referer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                if (isAdRequest(requestUrl)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (isMediaRequestForCurrentPage(referer)) tryRecordMedia(requestUrl)
                return super.shouldInterceptRequest(view, request)
            }
'''
if old in s:
    s = s.replace(old, new)
elif 'override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse?' not in s:
    raise SystemExit('shouldInterceptRequest block not found')

old_start = '''                clearDetectedMedia(); saveTabs()
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
'''
new_start = '''                clearDetectedMedia(); saveTabs()
                view.evaluateJavascript(COSMETIC_AD_GUARD, null)
                if (isYouTube(url)) view.evaluateJavascript(YOUTUBE_GUARD, null)
'''
if old_start in s:
    s = s.replace(old_start, new_start)

marker = '''    private fun isMediaRequestForCurrentPage(referer: String?): Boolean {
        val page = mediaPageUrl ?: currentPageUrl ?: return false
        if (referer.isNullOrBlank()) return true
        return sameSite(referer, page)
    }
'''
if 'private fun isAdRequest(raw: String): Boolean' not in s:
    addition = marker + '''
    private fun isAdRequest(raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        val host = runCatching { Uri.parse(raw).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        if (host.isBlank()) return false
        val blockedHosts = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "adnxs.com", "taboola.com", "outbrain.com", "criteo.com", "popads.net", "popcash.net",
            "propellerads.com", "adsterra.com", "exoclick.com", "onclicka.com", "onclickalgo.com",
            "hilltopads.net", "juicyads.com", "trafficjunky.net", "mgid.com", "revcontent.com", "bm-88.net"
        )
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        return listOf(
            "/ads/", "/adserver/", "/adservice/", "/banner-ad", "/popunder", "/popup-ad",
            "?ad=", "&ad=", "adclick", "adsystem", "advertising"
        ).any { lower.contains(it) }
    }
'''
    if marker not in s:
        raise SystemExit('media marker not found')
    s = s.replace(marker, addition)

old_play = '''    private fun playMedia(item: MediaItem, preferMx: Boolean) {
        val base = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(item.url), item.mime); putExtra("title", URLUtil.guessFileName(item.url, null, item.mime)); currentPageUrl?.let { putExtra("referer", it) } }
        if (preferMx) {
            for (pkg in MX_PLAYER_PACKAGES) try { startActivity(Intent(base).setPackage(pkg)); return } catch (_: ActivityNotFoundException) {}
            toast("RIC Player not installed")
        }
        try { startActivity(Intent.createChooser(base, "Play media with")) } catch (_: ActivityNotFoundException) { toast("No compatible player found") }
    }
'''
new_play = '''    private fun playMedia(item: MediaItem, preferRicPlayer: Boolean) {
        val base = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.url), item.mime)
            putExtra("title", URLUtil.guessFileName(item.url, null, item.mime))
            currentPageUrl?.let { putExtra("referer", it) }
        }
        if (preferRicPlayer) {
            try {
                startActivity(Intent(base).setClassName(RIC_PLAYER_PACKAGE, RIC_PLAYER_ACTIVITY))
                return
            } catch (_: ActivityNotFoundException) {
                toast("RIC Player belum terpasang")
                return
            }
        }
        try { startActivity(Intent.createChooser(base, "Play media with")) } catch (_: ActivityNotFoundException) { toast("No compatible player found") }
    }
'''
if old_play in s:
    s = s.replace(old_play, new_play)
elif 'private fun playMedia(item: MediaItem, preferRicPlayer: Boolean)' not in s:
    raise SystemExit('playMedia block not found')

s = s.replace('        private val MX_PLAYER_PACKAGES = arrayOf("com.ric.player")\n', '        private const val RIC_PLAYER_PACKAGE = "com.ric.player"\n        private const val RIC_PLAYER_ACTIVITY = "com.ric.player.PlayerActivity"\n')

start_tag = '        private const val COSMETIC_AD_GUARD = """'
end_tag = '        private const val YOUTUBE_GUARD = """'
start = s.index(start_tag)
end = s.index(end_tag, start)
guard = '''        private const val COSMETIC_AD_GUARD = """
(() => {
  if (window.__ricCosmeticGuard) { try { window.__ricCosmeticClean && window.__ricCosmeticClean(); } catch(_){} return; }
  window.__ricCosmeticGuard = true;
  const selectors = [
    '.adsbygoogle','[id^="google_ads_"]','[id*="google_ads"]','[class*="adsbygoogle"]',
    '[data-ad-client]','[data-ad-slot]','[class*="ad-banner"]','[class*="ad_banner"]','[class*="banner-ad"]',
    '[id*="ad-banner"]','[id*="ad_banner"]','[class*="popup-ad"]','[class*="popunder"]',
    'iframe[src*="doubleclick.net"]','iframe[src*="googlesyndication.com"]','iframe[src*="googleadservices.com"]',
    'iframe[src*="taboola.com"]','iframe[src*="outbrain.com"]','iframe[src*="adnxs.com"]','iframe[src*="criteo.com"]',
    'iframe[src*="adsterra"]','iframe[src*="propellerads"]','iframe[src*="popads"]','iframe[src*="exoclick"]',
    'a[href*="bm-88.net"]','iframe[src*="bm-88.net"]','img[src*="bm-88.net"]'
  ];
  const gambling = /(slot|gacor|judi|casino|togel|bet88|bet365|scatter|rtp\\s*\\d|spin\\s*(?:gratis|santai|sekarang)|depo\\s*(?:receh|murah)|maxwin)/i;
  const removeAdLike = el => {
    if (!el || !el.parentNode) return;
    const box = el.closest && el.closest('aside,ins,figure,section,div,a');
    const target = box || el;
    const text = ((target.innerText || '') + ' ' + (target.getAttribute && (target.getAttribute('href') || '')) + ' ' + (el.getAttribute && (el.getAttribute('src') || el.getAttribute('alt') || ''))).slice(0,1200);
    if (/bm-88\\.net/i.test(text) || gambling.test(text)) target.remove();
  };
  const clean = () => {
    try { document.querySelectorAll(selectors.join(',')).forEach(el => el.remove()); } catch(_) {}
    try { document.querySelectorAll('a[href],img[src],iframe[src]').forEach(removeAdLike); } catch(_) {}
    try {
      document.querySelectorAll('[style*="position: fixed"],[style*="position:fixed"],[style*="position: sticky"],[style*="position:sticky"]').forEach(el => {
        const r = el.getBoundingClientRect();
        const text = ((el.innerText || '') + ' ' + (el.getAttribute('class') || '') + ' ' + (el.getAttribute('id') || '')).slice(0,600);
        if ((r.width > innerWidth * .65 && r.height > 70) && (gambling.test(text) || /ad|banner|popup|promo/i.test(text))) el.remove();
      });
    } catch(_) {}
  };
  window.__ricCosmeticClean = clean;
  clean();
  new MutationObserver(clean).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','href','style','class']});
  setInterval(clean,1200);
})()
"""
'''
s = s[:start] + guard + s[end:]

old_nav = '''            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url; val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
                if (scheme == "http" || scheme == "https") return false
                return openExternal(uri)
            }'''
new_nav = '''            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url; val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
                if (scheme == "http" || scheme == "https") {
                    if (isAdRequest(uri.toString())) {
                        runOnUiThread { toast("Ad blocked") }
                        return true
                    }
                    return false
                }
                return openExternal(uri)
            }'''
if old_nav in s:
    s = s.replace(old_nav, new_nav, 1)

if '"bm-88.net"' not in s:
    s = s.replace('"hilltopads.net", "juicyads.com", "trafficjunky.net", "mgid.com", "revcontent.com"', '"hilltopads.net", "juicyads.com", "trafficjunky.net", "mgid.com", "revcontent.com", "bm-88.net"')

p.write_text(s)
