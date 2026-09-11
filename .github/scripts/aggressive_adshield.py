from pathlib import Path

p = Path('app/src/main/java/com/ruzakj/ricbrowser/MainActivity.kt')
s = p.read_text()

s = s.replace('setAcceptThirdPartyCookies(webView, true)', 'setAcceptThirdPartyCookies(webView, false)')

old = '''                if (scheme == "http" || scheme == "https") {
                    if (isAdRequest(uri.toString())) {
                        runOnUiThread { toast("Ad blocked") }
                        return true
                    }
                    return false
                }'''
new = '''                if (scheme == "http" || scheme == "https") {
                    if (isAggressiveAdNavigation(uri.toString(), currentPageUrl)) {
                        runOnUiThread { toast("Ad / redirect blocked") }
                        return true
                    }
                    return false
                }'''
if old in s:
    s = s.replace(old, new, 1)

marker = '    private fun tryRecordMedia(raw: String?) {'
if 'private fun isAggressiveAdNavigation(' not in s:
    helper = '''    private fun isAggressiveAdNavigation(raw: String, page: String?): Boolean {
        if (isAdRequest(raw)) return true
        val lower = raw.lowercase(Locale.ROOT)
        val destHost = runCatching { Uri.parse(raw).host?.lowercase(Locale.ROOT).orEmpty().removePrefix("www.") }.getOrDefault("")
        val pageHost = runCatching { Uri.parse(page.orEmpty()).host?.lowercase(Locale.ROOT).orEmpty().removePrefix("www.") }.getOrDefault("")
        if (destHost.isBlank()) return false
        val affiliateSignals = listOf("utm_source=", "utm_medium=", "utm_campaign=", "register=", "ref=", "aff=", "affiliate=", "clickid=", "subid=")
        if (affiliateSignals.count { lower.contains(it) } >= 2) return true
        val suspiciousHostTokens = listOf("casino", "slot", "gacor", "togel", "bet", "judi", "ads", "adserver", "adclick", "promo", "popunder", "traffic")
        if (suspiciousHostTokens.any { destHost.contains(it) }) return true
        val sourceLooksKurama = pageHost.contains("kurama")
        val crossSite = pageHost.isNotBlank() && destHost != pageHost && !destHost.endsWith(".$pageHost") && !pageHost.endsWith(".$destHost")
        val likelyMedia = Regex("\\.(mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (sourceLooksKurama && crossSite && !likelyMedia) return true
        return false
    }

'''
    if marker not in s:
        raise SystemExit('tryRecordMedia marker not found')
    s = s.replace(marker, helper + marker, 1)

old_tail = '''        return listOf(
            "/ads/", "/adserver/", "/adservice/", "/banner-ad", "/popunder", "/popup-ad",
            "?ad=", "&ad=", "adclick", "adsystem", "advertising"
        ).any { lower.contains(it) }
    }'''
new_tail = '''        if (listOf(
            "/ads/", "/adserver/", "/adservice/", "/banner-ad", "/popunder", "/popup-ad", "/sponsor/",
            "?ad=", "&ad=", "adclick", "adsystem", "advertising", "clickunder", "popunder", "interstitial"
        ).any { lower.contains(it) }) return true
        val badHostToken = listOf("casino", "slot", "gacor", "togel", "bet", "judi", "adserver", "adclick", "popunder", "trafficjunky")
        return badHostToken.any { host.contains(it) }
    }'''
if old_tail in s:
    s = s.replace(old_tail, new_tail, 1)

start_tag = '        private const val COSMETIC_AD_GUARD = """'
end_tag = '        private const val YOUTUBE_GUARD = """'
start = s.index(start_tag)
end = s.index(end_tag, start)
guard = r'''        private const val COSMETIC_AD_GUARD = """
(() => {
  const host = (location.hostname || '').toLowerCase();
  const isKurama = host.includes('kurama');
  const bad = /(slot|gacor|judi|casino|togel|bet(?:88|365)?|scatter|maxwin|rtp\s*\d|depo(?:sit)?|jackpot|spin\s*(?:gratis|sekarang)|bm-88|adsterra|propellerads|popads|exoclick|doubleclick|googlesyndication|adservice|adserver|popunder|clickunder)/i;
  const media = /\.(?:mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd)(?:[?#]|$)/i;
  const sameSite = u => { try { const x = new URL(u, location.href); return x.hostname === location.hostname || x.hostname.endsWith('.' + location.hostname) || location.hostname.endsWith('.' + x.hostname); } catch (_) { return true; } };
  const suspiciousUrl = u => {
    if (!u) return false;
    const x = String(u);
    if (media.test(x)) return false;
    if (bad.test(x)) return true;
    if ((x.match(/(?:utm_source|utm_medium|utm_campaign|register|ref|aff|affiliate|clickid|subid)=/gi) || []).length >= 2) return true;
    if (isKurama && /^https?:/i.test(x) && !sameSite(x)) return true;
    return false;
  };
  try { if (isKurama) { window.open = function(){ return null; }; } } catch (_) {}
  const selectors = [
    '.adsbygoogle','[id^="google_ads_"]','[id*="google_ads"]','[class*="adsbygoogle"]','[data-ad-client]','[data-ad-slot]',
    '[class*="ad-banner"]','[class*="ad_banner"]','[class*="banner-ad"]','[id*="ad-banner"]','[id*="ad_banner"]',
    '[class*="popup-ad"]','[class*="popunder"]','[class*="advert"]','[id*="advert"]','[class*="sponsor"]','[id*="sponsor"]',
    '[class*="promo-banner"]','[id*="promo-banner"]','iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]',
    'iframe[src*="googleadservices"]','iframe[src*="taboola"]','iframe[src*="outbrain"]','iframe[src*="adnxs"]','iframe[src*="criteo"]',
    'iframe[src*="adsterra"]','iframe[src*="propellerads"]','iframe[src*="popads"]','iframe[src*="exoclick"]',
    'a[href*="bm-88.net"]','iframe[src*="bm-88.net"]','img[src*="bm-88.net"]'
  ];
  const removeTarget = el => {
    if (!el || !el.parentNode) return;
    const target = (el.closest && el.closest('aside,ins,figure,section,article,div,a')) || el;
    const text = ((target.innerText || '') + ' ' + (target.id || '') + ' ' + (target.className || '') + ' ' + (el.href || '') + ' ' + (el.src || '') + ' ' + (el.alt || '')).slice(0,1800);
    if (bad.test(text) || suspiciousUrl(el.href || el.src || '')) target.remove();
  };
  const clean = () => {
    try { document.querySelectorAll(selectors.join(',')).forEach(el => el.remove()); } catch(_) {}
    try { document.querySelectorAll('a[href],img[src],iframe[src],script[src]').forEach(removeTarget); } catch(_) {}
    try { document.querySelectorAll('a[target="_blank"],a[rel*="sponsored"]').forEach(a => { if (suspiciousUrl(a.href) || bad.test((a.innerText || '') + ' ' + a.href)) a.remove(); }); } catch(_) {}
    try {
      document.querySelectorAll('[style*="position: fixed"],[style*="position:fixed"],[style*="position: sticky"],[style*="position:sticky"]').forEach(el => {
        const r = el.getBoundingClientRect();
        const text = ((el.innerText || '') + ' ' + (el.className || '') + ' ' + (el.id || '')).slice(0,1000);
        if ((r.width > innerWidth * .45 && r.height > 55) && (bad.test(text) || /ad|banner|popup|sponsor|promo/i.test(text))) el.remove();
      });
    } catch(_) {}
  };
  if (!window.__ricAggressiveClickGuard) {
    window.__ricAggressiveClickGuard = true;
    document.addEventListener('click', e => {
      try {
        const a = e.target && e.target.closest && e.target.closest('a[href]');
        if (!a) return;
        const href = a.href || '';
        if (suspiciousUrl(href) || bad.test((a.innerText || '') + ' ' + href)) {
          e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
        }
      } catch (_) {}
    }, true);
  }
  window.__ricCosmeticClean = clean;
  clean();
  if (!window.__ricAggressiveObserver) {
    window.__ricAggressiveObserver = new MutationObserver(clean);
    window.__ricAggressiveObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','href','style','class','target','rel']});
    setInterval(clean,400);
  }
})()
"""
'''
s = s[:start] + guard + s[end:]

p.write_text(s)
