package com.fenglan.aurorabrowser.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

class AdBlocker(context: Context) {
    private val blockedHosts: Set<String> by lazy {
        context.assets.open("ad_hosts.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
    }

    fun shouldBlock(requestUri: Uri?, pageUri: Uri?, isMainFrame: Boolean): Boolean {
        if (requestUri == null || isMainFrame) {
            return false
        }
        return isAdLikeUrl(requestUri, pageUri)
    }

    fun isAdLikeUrl(requestUri: Uri?, pageUri: Uri?): Boolean {
        if (requestUri == null) {
            return false
        }
        val requestHost = requestUri.host?.lowercase() ?: return false
        val requestUrl = requestUri.toString().lowercase()
        val pageHost = pageUri?.host?.lowercase()

        if (blockedHosts.any { requestHost == it || requestHost.endsWith(".$it") }) {
            return true
        }

        if (pageHost != null && requestHost != pageHost && SUSPICIOUS_HOST_FRAGMENTS.any { requestHost.contains(it) }) {
            return true
        }

        if (AD_KEYWORDS.any { requestUrl.contains(it) }) {
            return true
        }

        if (SUSPICIOUS_REDIRECT_PATTERNS.any { requestUrl.contains(it) }) {
            return true
        }

        if (pageHost != null && requestHost != pageHost && requestUrl.contains("/ads")) {
            return true
        }

        return false
    }

    fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    companion object {
        private val AD_KEYWORDS = listOf(
            "doubleclick",
            "googlesyndication",
            "googleadservices",
            "adservice",
            "banner",
            "popunder",
            "prebid",
            "taboola",
            "outbrain",
            "adnxs",
            "amazon-adsystem",
            "adclick",
            "advert",
            "affiliate",
            "sponsor",
            "promo",
            "redirect",
            "openapp",
            "downloadapp",
            "landing",
            "campaign",
            "/ads/",
            "adsystem",
            "tracking",
        )

        private val SUSPICIOUS_REDIRECT_PATTERNS = listOf(
            "/cc/",
            "ctype=compel_click",
            "tg.htm?channel=",
            "tg.htm?channelcode=",
            "channelcode=",
            "web_id=",
            "open_n=1",
            "target=1",
            "advertiser_ad_id=",
            "advertiser_id=",
            "return_chance=",
            "hid_chance=",
            "ifrom=",
            "history=",
            "clickp=",
            "jstime=",
        )

        private val SUSPICIOUS_HOST_FRAGMENTS = listOf(
            "1ix3kl",
            "cmtc",
            "mmac",
            "yanpai",
            "heitao",
            "huan",
            "jiejie",
            "yjab",
            "mabioe",
            "kg102h",
            "0oqtn2",
            "s8dhjc",
            "tgutfvr",
            "rfbswq275",
            "tppobi",
        )
    }
}
