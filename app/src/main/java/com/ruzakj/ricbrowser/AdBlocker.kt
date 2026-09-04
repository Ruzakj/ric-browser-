package com.ruzakj.ricbrowser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adnxs.com",
        "adsrvr.org",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "criteo.net",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "amazon-adsystem.com",
        "adsafeprotected.com",
        "scorecardresearch.com",
        "zedo.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "exoclick.com",
        "trafficjunky.net",
        "mgid.com",
        "revcontent.com",
        "media.net",
        "smartadserver.com",
        "adform.net",
        "serving-sys.com",
        "yieldmo.com",
        "moatads.com",
        "lijit.com",
        "sovrn.com",
        "contextweb.com",
        "bidswitch.net",
        "quantserve.com",
        "quantcount.com",
        "bluekai.com",
        "demdex.net",
        "rlcdn.com",
        "everesttech.net"
    )

    private val blockedTokens = arrayOf(
        "/ads/",
        "/adserver/",
        "/adservice/",
        "/advertising/",
        "/advertisement/",
        "/ad-delivery/",
        "/admanager/",
        "/banner/",
        "/banners/",
        "/popunder/",
        "/popup-ad/",
        "doubleclick",
        "googlesyndication",
        "googleadservices",
        "pagead2",
        "imasdk",
        "adservice",
        "prebid",
        "bidder",
        "adserver",
        "adunit",
        "ad_unit",
        "ad-slot",
        "adsystem"
    )

    private val allowedHosts = setOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "gstatic.com",
        "googleusercontent.com"
    )

    fun shouldBlock(url: String, documentUrl: String? = null): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = normalizeHost(uri.host ?: return false)
        if (host.isEmpty()) return false

        if (allowedHosts.any { hostMatches(host, it) }) return false
        if (isEssential(url, documentUrl)) return false
        if (blockedHosts.any { hostMatches(host, it) }) return true

        val lower = url.lowercase()
        return blockedTokens.any(lower::contains)
    }

    private fun normalizeHost(host: String): String = host.lowercase().removePrefix("www.")

    private fun hostMatches(host: String, rule: String): Boolean =
        host == rule || host.endsWith(".$rule")

    private fun isEssential(url: String, documentUrl: String?): Boolean {
        val host = runCatching { normalizeHost(Uri.parse(url).host.orEmpty()) }.getOrDefault("")
        val doc = runCatching { normalizeHost(Uri.parse(documentUrl ?: "").host.orEmpty()) }.getOrDefault("")

        if (host.endsWith("googlevideo.com")) return true
        if (host.endsWith("ytimg.com")) return true
        if (host.endsWith("youtube.com")) return true
        if (doc.endsWith("youtube.com") && host.endsWith("googleapis.com")) return true

        return false
    }

    fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        204,
        "No Content",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )
}
