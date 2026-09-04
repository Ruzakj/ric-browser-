package com.ruzakj.ricbrowser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {
    private val blockedHosts = setOf("doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com","adnxs.com","adsrvr.org","taboola.com","outbrain.com","criteo.com","pubmatic.com","rubiconproject.com","openx.net","casalemedia.com","amazon-adsystem.com","adsafeprotected.com","scorecardresearch.com","zedo.com","popads.net","popcash.net","propellerads.com","adsterra.com","exoclick.com","trafficjunky.net","mgid.com","revcontent.com")
    private val blockedTokens = arrayOf("/ads/","/adserver/","/advertising/","/advertisement/","/banner/","/popunder/","doubleclick","googlesyndication","googleadservices","pagead2","imasdk","adservice","prebid","bidder","telemetry")
    private val allowedGoogle = setOf("accounts.google.com","accounts.youtube.com","gstatic.com","googleusercontent.com")

    fun shouldBlock(url: String, documentUrl: String? = null): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = (uri.host ?: "").lowercase().removePrefix("www.")
        if (host.isEmpty()) return false
        if (allowedGoogle.any { host == it || host.endsWith(".$it") }) return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        val lower = url.lowercase()
        return blockedTokens.any { lower.contains(it) } && !isEssential(url, documentUrl)
    }

    private fun isEssential(url: String, documentUrl: String?): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        val doc = runCatching { Uri.parse(documentUrl ?: "").host.orEmpty().lowercase() }.getOrDefault("")
        return host.endsWith("youtube.com") || host.endsWith("googlevideo.com") || (doc.endsWith("youtube.com") && host.endsWith("googleapis.com"))
    }

    fun emptyResponse(): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
