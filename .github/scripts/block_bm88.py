from pathlib import Path

p = Path('app/src/main/java/com/ruzakj/ricbrowser/MainActivity.kt')
s = p.read_text()

old_hosts = '''            "hilltopads.net", "juicyads.com", "trafficjunky.net", "mgid.com", "revcontent.com"
        )'''
new_hosts = '''            "hilltopads.net", "juicyads.com", "trafficjunky.net", "mgid.com", "revcontent.com", "bm-88.net"
        )'''
if old_hosts in s:
    s = s.replace(old_hosts, new_hosts, 1)

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

# Same-origin banner creatives can still point to bm-88 even when their image is served elsewhere.
s = s.replace(
    '''    'iframe[src*="adsterra"]','iframe[src*="propellerads"]','iframe[src*="popads"]','iframe[src*="exoclick"]'\n  ];''',
    '''    'iframe[src*="adsterra"]','iframe[src*="propellerads"]','iframe[src*="popads"]','iframe[src*="exoclick"]',\n    'a[href*="bm-88.net"]','iframe[src*="bm-88.net"]','img[src*="bm-88.net"]'\n  ];'''
)
s = s.replace(
    '    if (gambling.test(text)) target.remove();',
    '    if (/bm-88\\.net/i.test(text) || gambling.test(text)) target.remove();'
)

# Prevent manually entered/restored bm-88 URLs from becoming the active page.
old_load = '''        val url = when { value.startsWith("http://", true) || value.startsWith("https://", true) -> value; value.contains(".") && !value.contains(" ") -> "https://$value"; else -> "https://www.google.com/search?q=" + Uri.encode(value) }
        clearDetectedMedia()'''
new_load = '''        val url = when { value.startsWith("http://", true) || value.startsWith("https://", true) -> value; value.contains(".") && !value.contains(" ") -> "https://$value"; else -> "https://www.google.com/search?q=" + Uri.encode(value) }
        if (isAdRequest(url)) { toast("Ad blocked"); return }
        clearDetectedMedia()'''
if old_load in s:
    s = s.replace(old_load, new_load, 1)

p.write_text(s)
