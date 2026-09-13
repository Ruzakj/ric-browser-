package com.ruzakj.ricbrowser

import android.net.Uri
import java.util.Locale

object AggressiveAdBlocker {
    private val blockedHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adnxs.com", "taboola.com", "outbrain.com", "criteo.com", "popads.net", "popcash.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "onclicka.com", "onclickalgo.com",
        "hilltopads.net", "juicyads.com", "trafficjunky.net", "mgid.com", "revcontent.com",
        "adskeeper.co.uk", "admaven.com", "monetag.com", "onclickperformance.com", "pushground.com",
        "evadav.com", "richads.com", "zeropark.com", "clickadu.com", "galaksion.com", "bm-88.net"
    )

    private val pathSignals = listOf(
        "/ads/", "/adserver/", "/adservice/", "/adserve/", "/banner-ad", "/popup-ad", "/popunder",
        "/interstitial", "/sponsor/", "/promoted/", "adclick", "adsystem", "advertising", "clickunder",
        "popunder", "interstitial", "redirect-ad", "smartlink", "push-notification"
    )

    private val suspiciousHostTokens = listOf(
        "casino", "slot", "gacor", "togel", "judi", "adserver", "adclick", "popunder", "trafficjunky",
        "smartlink", "clickunder", "pushads", "bannerads"
    )

    private val mediaRegex = Regex("""\.(mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd)(?:[?#]|$)""", RegexOption.IGNORE_CASE)

    fun isBlockedRequest(raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        val host = host(raw)
        if (host.isBlank()) return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        if (pathSignals.any(lower::contains)) return true
        if (suspiciousHostTokens.any(host::contains)) return true
        return false
    }

    fun isBlockedNavigation(raw: String, page: String?): Boolean {
        if (raw.contains("__ric_user_approved=1")) return false
        if (isBlockedRequest(raw)) return true
        if (mediaRegex.containsMatchIn(raw)) return false

        val lower = raw.lowercase(Locale.ROOT)
        val destHost = host(raw).removePrefix("www.")
        val pageHost = host(page.orEmpty()).removePrefix("www.")
        if (destHost.isBlank()) return false

        val affiliateSignals = listOf(
            "utm_source=", "utm_medium=", "utm_campaign=", "register=", "ref=", "aff=", "affiliate=",
            "clickid=", "subid=", "sub_id=", "campaign=", "zoneid=", "offer_id="
        )
        if (affiliateSignals.count(lower::contains) >= 2) return true
        if (suspiciousHostTokens.any(destHost::contains)) return true

        val crossSite = pageHost.isNotBlank() && destHost != pageHost &&
            !destHost.endsWith(".$pageHost") && !pageHost.endsWith(".$destHost")

        val redirectSignals = listOf("redirect=", "redirect_url=", "url=", "target=", "destination=", "out=")
        if (crossSite && redirectSignals.any(lower::contains) && affiliateSignals.any(lower::contains)) return true

        return false
    }

    private fun host(raw: String): String = runCatching {
        Uri.parse(raw).host?.lowercase(Locale.ROOT).orEmpty()
    }.getOrDefault("")

    const val COSMETIC_JS = """
(() => {
  const BAD = /(slot|gacor|judi|casino|togel|bet(?:88|365)?|scatter|maxwin|rtp\s*\d|depo(?:sit)?|jackpot|spin\s*(?:gratis|sekarang)|bm-88|adsterra|propellerads|popads|exoclick|doubleclick|googlesyndication|googleadservices|adservice|adserver|popunder|clickunder|smartlink|pushads|sponsored)/i;
  const MEDIA = /\.(?:mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd)(?:[?#]|$)/i;
  const selectors = [
    '.adsbygoogle','[id^="google_ads_"]','[id*="google_ads"]','[class*="adsbygoogle"]','[data-ad-client]','[data-ad-slot]',
    '[class*="ad-banner"]','[class*="ad_banner"]','[class*="banner-ad"]','[id*="ad-banner"]','[id*="ad_banner"]',
    '[class*="popup-ad"]','[class*="popunder"]','[class*="advert"]','[id*="advert"]','[class*="sponsor"]','[id*="sponsor"]',
    '[class*="promo-banner"]','[id*="promo-banner"]','[class*="interstitial"]','[id*="interstitial"]',
    'iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]','iframe[src*="googleadservices"]','iframe[src*="taboola"]',
    'iframe[src*="outbrain"]','iframe[src*="adnxs"]','iframe[src*="criteo"]','iframe[src*="adsterra"]','iframe[src*="propellerads"]',
    'iframe[src*="popads"]','iframe[src*="exoclick"]','iframe[src*="bm-88.net"]','img[src*="bm-88.net"]'
  ];

  const suspicious = u => {
    if (!u || MEDIA.test(String(u))) return false;
    const x = String(u);
    if (BAD.test(x)) return true;
    return (x.match(/(?:utm_source|utm_medium|utm_campaign|register|ref|aff|affiliate|clickid|subid|zoneid|offer_id)=/gi) || []).length >= 2;
  };

  const hostname = u => {
    try { return new URL(u, location.href).hostname || u; } catch (_) { return String(u || 'unknown link'); }
  };

  const approvedUrl = u => {
    try {
      const x = new URL(u, location.href);
      const base = x.hash ? x.hash.substring(1) + '&' : '';
      x.hash = base + '__ric_user_approved=1';
      return x.href;
    } catch (_) { return String(u); }
  };

  const askPermission = u => window.confirm(
    'Ric Browser mencegah redirect otomatis.\n\n' +
    'Tujuan: ' + hostname(u) + '\n\n' +
    'Link ini terdeteksi sebagai iklan / redirect mencurigakan. Tetap buka?'
  );

  try {
    const nativeOpen = window.open ? window.open.bind(window) : null;
    window.open = function(url, target, features) {
      if (!url) return null;
      if (suspicious(url) || BAD.test(String(url))) {
        if (!askPermission(url)) return null;
        location.href = approvedUrl(url);
        return window;
      }
      return nativeOpen ? nativeOpen(url, target, features) : null;
    };
  } catch (_) {}

  const remove = el => {
    if (!el || !el.parentNode) return;
    const target = (el.closest && el.closest('aside,ins,figure,section,article,div')) || el;
    const text = ((target.innerText || '')+' '+(target.id || '')+' '+(target.className || '')+' '+(el.src || '')+' '+(el.alt || '')).slice(0,2200);
    if (el.tagName !== 'A' && (BAD.test(text) || suspicious(el.src || ''))) target.remove();
  };

  const clean = () => {
    try { document.querySelectorAll(selectors.join(',')).forEach(el => el.remove()); } catch (_) {}
    try { document.querySelectorAll('img[src],iframe[src],script[src]').forEach(remove); } catch (_) {}
    try { document.querySelectorAll('[style*="position: fixed"],[style*="position:fixed"],[style*="position: sticky"],[style*="position:sticky"]').forEach(el => {
      const r=el.getBoundingClientRect(); const t=((el.innerText||'')+' '+(el.className||'')+' '+(el.id||'')).slice(0,1400);
      if (r.width > innerWidth*.45 && r.height > 55 && (BAD.test(t) || /ad|banner|popup|sponsor|promo|install app/i.test(t))) el.remove();
    }); } catch (_) {}
  };

  if (!window.__ricPermissionGate) {
    window.__ricPermissionGate = true;
    document.addEventListener('click', e => {
      try {
        const a = e.target && e.target.closest && e.target.closest('a[href]');
        if (!a) return;
        const href = a.href || '';
        if (!(suspicious(href) || BAD.test((a.innerText || '') + ' ' + href))) return;
        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
        if (askPermission(href)) location.href = approvedUrl(href);
      } catch (_) {}
    }, true);
  }

  clean();
  if (!window.__ricAggressiveObserver) {
    window.__ricAggressiveObserver = new MutationObserver(clean);
    window.__ricAggressiveObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','href','style','class','target','rel']});
    setInterval(clean,350);
  }
})();
"""
}
