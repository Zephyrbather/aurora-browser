package com.fenglan.aurorabrowser.browser

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.coroutines.resume

object MagnetSearchClient {
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131 Safari/537.36"

    private data class BrowserProfile(
        val userAgent: String,
    )

    private data class DetailMetadata(
        val magnetUrl: String?,
        val sizeText: String?,
    )

    private data class CachedDetailMetadata(
        val metadata: DetailMetadata?,
        val cachedAt: Long,
    )

    private val anchorRegex = Regex(
        """<a\s+[^>]*href\s*=\s*(['"])(.*?)\1[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val magnetRegex = Regex(
        """magnet:\?xt=urn:[^"'<>\s]+""",
        RegexOption.IGNORE_CASE,
    )
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")
    private val htmlNumericEntityRegex = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val sizeRegex = Regex(
        """(?i)\b\d+(?:[.,]\d+)?\s*(?:bytes?|[kmgtpe]i?b)\b""",
    )
    private val labelSizeRegex = Regex(
        """(?i)(?:size|total\s*size|file\s*size|filesize|大小|容量|文件大小)[^0-9]{0,48}(\d+(?:[.,]\d+)?\s*(?:bytes?|[kmgtpe]i?b))""",
    )
    private val hardChallengeIndicators = listOf(
        "challenge-platform", "cf-browser-verify",
        "cf-challenge", "just a moment", "checking your browser",
        "ddos protection", "_cf_chl_opt", "cf-turnstile",
        "challenge-form", "cf_clearance", "attention required!",
        "verify you are human", "human verification", "captcha",
    )
    private val browserProfiles = listOf(
        BrowserProfile(MOBILE_USER_AGENT),
        BrowserProfile(DESKTOP_USER_AGENT),
    )
    private val detailMetadataCache = object : LinkedHashMap<String, CachedDetailMetadata>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDetailMetadata>?): Boolean {
            return size > MAX_DETAIL_METADATA_CACHE_ENTRIES
        }
    }
    private val resultPathKeywords = listOf(
        "/torrent/", "/torrents/", "/detail/", "/details/", "/hash/", "/view/",
        "/file/", "/download/", "/downloads/", "/magnet/", "/bt/", "/ep/",
        "/title/", "/torrentdownload/",
    )
    private val resultQueryKeywords = listOf(
        "id=", "hash=", "info_hash=", "torrent=", "torrentid=",
    )

    suspend fun search(source: MagnetSearchSource, query: String, context: Context? = null): MagnetSearchOutcome {
        val cleaned = query.trim()
        if (cleaned.isBlank()) {
            return MagnetSearchOutcome(source, emptyList(), "关键词为空")
        }
        val merged = linkedMapOf<String, MagnetSearchResult>()
        var httpCfDetected = false
        var httpFailed = false
        var httpSucceeded = false
        var renderedSucceeded = false
        val searchUrls = source.buildSearchUrls(cleaned)
        val allowWebViewFallback = context != null && shouldUseWebViewFallback(source)

        searchUrls.forEach { searchUrl ->
            for (profile in browserProfiles) {
                runCatching {
                    val html = downloadText(searchUrl, context, profile)
                    if (looksLikeChallengePage(html)) {
                        httpCfDetected = true
                        error("CF_CHALLENGE")
                    }
                    httpSucceeded = true
                    parseResults(source, searchUrl, html).forEach { result ->
                        merged.putIfAbsent(result.copyUrl, result)
                    }
                }.onFailure { error ->
                    if (error.message != "CF_CHALLENGE") httpFailed = true
                }
                if (merged.isNotEmpty()) {
                    return resolveAndReturn(source, merged, context, null)
                }
            }
        }

        if (allowWebViewFallback && (httpCfDetected || httpFailed || merged.isEmpty())) {
            val safeContext = context ?: return resolveAndReturn(
                source = source,
                merged = merged,
                context = context,
                emptyErrorMessage = emptySearchErrorMessage(
                    source = source,
                    httpSucceeded = httpSucceeded || renderedSucceeded,
                    httpCfDetected = httpCfDetected,
                    httpFailed = httpFailed,
                ),
            )
            val emptyDirectResult = !httpCfDetected && httpSucceeded && merged.isEmpty()
            val fallbackUrls = if (emptyDirectResult) searchUrls.take(1) else searchUrls
            val fallbackProfiles = if (emptyDirectResult) browserProfiles.take(1) else browserProfiles
            for (searchUrl in fallbackUrls) {
                for (profile in fallbackProfiles) {
                    runCatching {
                        val html = fetchViaWebView(safeContext, searchUrl, profile)
                        if (html.isNotBlank() && !looksLikeChallengePage(html)) {
                            renderedSucceeded = true
                            parseResults(source, searchUrl, html).forEach { result ->
                                merged.putIfAbsent(result.copyUrl, result)
                            }
                        }
                    }
                    if (merged.isNotEmpty()) {
                        break
                    }
                }
                if (merged.isNotEmpty()) {
                    break
                }
            }
        }

        return resolveAndReturn(
            source = source,
            merged = merged,
            context = context,
            emptyErrorMessage = emptySearchErrorMessage(
                source = source,
                httpSucceeded = httpSucceeded || renderedSucceeded,
                httpCfDetected = httpCfDetected,
                httpFailed = httpFailed,
            ),
        )
    }

    private suspend fun resolveAndReturn(
        source: MagnetSearchSource,
        merged: LinkedHashMap<String, MagnetSearchResult>,
        context: Context?,
        emptyErrorMessage: String?,
    ): MagnetSearchOutcome {
        if (merged.isEmpty()) {
            return MagnetSearchOutcome(source, emptyList(), emptyErrorMessage)
        }
        if (context != null) {
            val resolved = resolveDetailMagnets(
                results = merged.values.toList(),
                context = context,
                allowWebViewFallback = shouldUseWebViewFallback(source),
            )
            return MagnetSearchOutcome(source, resolved)
        }
        return MagnetSearchOutcome(source, merged.values.toList())
    }

    private fun emptySearchErrorMessage(
        source: MagnetSearchSource,
        httpSucceeded: Boolean,
        httpCfDetected: Boolean,
        httpFailed: Boolean,
    ): String? {
        if (httpSucceeded) return null
        if (source.id == "0magnet") {
            return if (httpFailed || httpCfDetected) "源站暂时不可访问，请稍后重试" else null
        }
        return when {
            httpCfDetected -> MagnetSearchOutcome.ERROR_REQUIRES_VERIFICATION
            httpFailed -> "站点暂时不可访问"
            else -> null
        }
    }

    private suspend fun resolveDetailMagnets(
        results: List<MagnetSearchResult>,
        context: Context,
        allowWebViewFallback: Boolean,
    ): List<MagnetSearchResult> = coroutineScope {
        val needResolution = results
            .filter { it.pageUrl != null && (it.magnetUrl == null || it.sizeText == null) }
        if (needResolution.isEmpty()) {
            return@coroutineScope results
        }
        val toResolve = needResolution.take(MAX_DETAIL_PAGES)
        val resolved = toResolve.map { result ->
            async(Dispatchers.IO) {
                val metadata = fetchDetailMetadata(result.pageUrl!!, context, allowWebViewFallback)
                result to metadata
            }
        }.awaitAll()
        val metadataMap = resolved
            .filter { it.second != null }
            .associate { it.first.pageUrl!! to it.second!! }
        results.map { result ->
            val metadata = metadataMap[result.pageUrl]
            if (metadata == null) {
                result
            } else {
                result.copy(
                    magnetUrl = result.magnetUrl ?: metadata.magnetUrl,
                    sizeText = result.sizeText ?: metadata.sizeText,
                )
            }
        }.distinctBy { it.magnetUrl ?: it.torrentUrl ?: it.pageUrl }
    }

    private suspend fun fetchDetailMetadata(
        pageUrl: String,
        context: Context,
        allowWebViewFallback: Boolean,
    ): DetailMetadata? {
        cachedDetailMetadata(pageUrl)?.let { return it.metadata }
        var best: DetailMetadata? = null
        browserProfiles.forEach { profile ->
            val html = runCatching {
                downloadText(pageUrl, context, profile)
            }.getOrNull()
            if (html != null && !looksLikeChallengePage(html)) {
                val metadata = parseDetailMetadata(html)
                if (metadata.magnetUrl != null) {
                    cacheDetailMetadata(pageUrl, metadata)
                    return metadata
                }
                if (metadata.sizeText != null && best == null) {
                    best = metadata
                }
            }
        }
        if (!allowWebViewFallback) {
            cacheDetailMetadata(pageUrl, best)
            return best
        }
        browserProfiles.forEach { profile ->
            val wvHtml = runCatching {
                fetchViaWebView(context, pageUrl, profile)
            }.getOrNull().orEmpty()
            if (wvHtml.isNotBlank() && !looksLikeChallengePage(wvHtml)) {
                val metadata = parseDetailMetadata(wvHtml)
                if (metadata.magnetUrl != null) {
                    cacheDetailMetadata(pageUrl, metadata)
                    return metadata
                }
                if (metadata.sizeText != null && best == null) {
                    best = metadata
                }
            }
        }
        cacheDetailMetadata(pageUrl, best)
        return best
    }

    fun parseResults(source: MagnetSearchSource, searchUrl: String, html: String): List<MagnetSearchResult> {
        val results = linkedMapOf<String, MagnetSearchResult>()
        anchorRegex.findAll(html).forEach { match ->
            val href = decodeHtml(match.groupValues[2]).trim()
            val absoluteUrl = normalizeUrl(searchUrl, href) ?: return@forEach
            val lowerUrl = absoluteUrl.lowercase()
            val isMagnet = lowerUrl.startsWith("magnet:")
            val directMagnet = if (isMagnet) TorrentTools.extractValidMagnet(absoluteUrl) else null
            if (isMagnet && directMagnet == null) {
                return@forEach
            }
            val isTorrentFile = TorrentTools.isTorrentUrl(absoluteUrl) || lowerUrl.endsWith("/download")
            val isResultPage = looksLikeResultPage(source, absoluteUrl)
            if (!isMagnet && !isTorrentFile && !isResultPage) {
                return@forEach
            }
            val title = cleanTitle(match.groupValues[3]).ifBlank {
                fallbackTitleForUrl(absoluteUrl)
            }
            if (title.length < 2 && !isMagnet) {
                return@forEach
            }
            val context = resultContext(html, match.range)
            val pageUrl = absoluteUrl.takeIf { !isMagnet && !isTorrentFile }
            val magnetUrl = directMagnet ?: if (pageUrl != null) firstValidMagnet(context) else null
            val torrentUrl = absoluteUrl.takeIf { isTorrentFile }
            val key = magnetUrl ?: torrentUrl ?: pageUrl ?: return@forEach
            results.putIfAbsent(
                key,
                MagnetSearchResult(
                    sourceId = source.id,
                    sourceLabel = source.label,
                    title = title,
                    pageUrl = pageUrl,
                    magnetUrl = magnetUrl,
                    torrentUrl = torrentUrl,
                    sizeText = extractSizeText(context),
                ),
            )
        }
        val decodedHtml = decodeHtml(html)
        magnetRegex.findAll(decodedHtml).forEach { match ->
            val magnet = TorrentTools.extractValidMagnet(match.value) ?: return@forEach
            val context = resultContext(decodedHtml, match.range)
            results.putIfAbsent(
                magnet,
                MagnetSearchResult(
                    sourceId = source.id,
                    sourceLabel = source.label,
                    title = titleFromMagnet(magnet) ?: "磁力结果",
                    pageUrl = null,
                    magnetUrl = magnet,
                    torrentUrl = null,
                    sizeText = extractSizeText(context),
                ),
            )
        }
        return results.values.toList()
    }

    private fun parseDetailMetadata(html: String): DetailMetadata {
        return DetailMetadata(
            magnetUrl = firstValidMagnet(html),
            sizeText = extractSizeText(html),
        )
    }

    private fun firstValidMagnet(raw: String): String? {
        val decoded = decodeHtml(raw)
        return magnetRegex.findAll(decoded)
            .mapNotNull { TorrentTools.extractValidMagnet(it.value) }
            .firstOrNull()
    }

    private fun downloadText(
        url: String,
        context: Context? = null,
        profile: BrowserProfile = browserProfiles.first(),
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", profile.userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            setRequestProperty("Accept-Encoding", "gzip, deflate")
            setRequestProperty("Cache-Control", "max-age=0")
            setRequestProperty("Upgrade-Insecure-Requests", "1")
            setRequestProperty("Sec-Fetch-Dest", "document")
            setRequestProperty("Sec-Fetch-Mode", "navigate")
            setRequestProperty("Sec-Fetch-Site", "none")
            if (context != null) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val cookies = cookieManager.getCookie(url)
                if (cookies.isNotBlank()) {
                    setRequestProperty("Cookie", cookies)
                }
            }
            val referer = deriveReferer(url)
            if (referer != null) {
                setRequestProperty("Referer", referer)
            }
        }
        return connection.useConnection {
            val code = responseCode
            if (code == 503 || code == 403) {
                detectCfAndThrow(code)
            }
            if (code !in 200..399) {
                error("HTTP $code")
            }
            val charset = contentType
                ?.substringAfter("charset=", "")
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: Charsets.UTF_8
            decodedInputStream().use { stream ->
                val raw = stream.readBytes()
                val body = raw.toString(charset)
                if (context != null) {
                    headerFields.entries
                        .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                        .flatMap { it.value }
                        .forEach { cookie -> CookieManager.getInstance().setCookie(url, cookie) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        CookieManager.getInstance().flush()
                    }
                }
                body
            }
        }
    }

    private fun shouldUseWebViewFallback(source: MagnetSearchSource): Boolean {
        return source.id != "0magnet"
    }

    private fun cachedDetailMetadata(pageUrl: String): CachedDetailMetadata? {
        return synchronized(detailMetadataCache) {
            val cached = detailMetadataCache[pageUrl] ?: return@synchronized null
            if (System.currentTimeMillis() - cached.cachedAt <= DETAIL_METADATA_CACHE_TTL_MS) {
                cached
            } else {
                detailMetadataCache.remove(pageUrl)
                null
            }
        }
    }

    private fun cacheDetailMetadata(pageUrl: String, metadata: DetailMetadata?) {
        synchronized(detailMetadataCache) {
            detailMetadataCache[pageUrl] = CachedDetailMetadata(metadata, System.currentTimeMillis())
        }
    }

    private fun HttpURLConnection.detectCfAndThrow(code: Int) {
        val body = runCatching {
            errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull().orEmpty()
        if (body.isNotBlank() && containsChallengeIndicators(body)) {
            error("CF_CHALLENGE")
        }
        if (code !in 200..399) {
            error("HTTP $code")
        }
    }

    internal fun looksLikeChallengePage(html: String): Boolean {
        if (html.isBlank()) return false
        if (html.length < 800 && containsChallengeIndicators(html)) return true
        if (html.length < 400 && !html.contains("magnet", ignoreCase = true) &&
            !html.contains("torrent", ignoreCase = true) && !html.contains("<table") &&
            !html.contains("search result", ignoreCase = true)) {
            if (containsChallengeIndicators(html)) return true
            if (html.contains("<script") && html.contains("challenge", ignoreCase = true)) return true
        }
        return containsChallengeIndicators(html) && html.length < 2000
    }

    private fun containsChallengeIndicators(text: String): Boolean {
        val lower = text.lowercase()
        if (hardChallengeIndicators.any { lower.contains(it) }) {
            return true
        }
        if (!lower.contains("cloudflare")) {
            return false
        }
        val rayMarker = lower.contains("cf-ray") || lower.contains("ray id")
        val browserCheck = lower.contains("enable cookies") && lower.contains("javascript")
        return rayMarker || browserCheck
    }

    private suspend fun fetchViaWebView(context: Context, url: String, profile: BrowserProfile): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                var resumed = false
                val handler = Handler(Looper.getMainLooper())
                var pageLoadCount = 0
                val startTime = System.currentTimeMillis()

                val webView = WebView(context.applicationContext).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString = profile.userAgent
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            pageLoadCount++
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (resumed) return
                            handler.removeCallbacksAndMessages(null)
                            val elapsed = System.currentTimeMillis() - startTime
                            val delay = when {
                                pageLoadCount <= 1 && elapsed < 3000 -> CF_FIRST_WAIT_MS
                                pageLoadCount <= 2 -> CF_RETRY_WAIT_MS
                                else -> 500L
                            }
                            handler.postDelayed({
                                if (!resumed) {
                                    resumed = true
                                    prepareAndExtractHtml(view, continuation, this@apply, handler)
                                }
                            }, delay)
                        }

                        @Suppress("DEPRECATION", "deprecation", "OVERRIDE_DEPRECATION")
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            if (!resumed && pageLoadCount == 0) {
                                resumed = true
                                handler.removeCallbacksAndMessages(null)
                                continuation.resume("")
                                this@apply.destroy()
                            }
                        }
                    }
                }
                webView.loadUrl(url)

                handler.postDelayed({
                    if (!resumed) {
                        resumed = true
                        prepareAndExtractHtml(webView, continuation, webView, handler)
                    }
                }, MAX_WEBVIEW_WAIT_MS)

                continuation.invokeOnCancellation {
                    if (!resumed) {
                        resumed = true
                        handler.removeCallbacksAndMessages(null)
                        webView.destroy()
                    }
                }
            }
        }

    private fun prepareAndExtractHtml(
        view: WebView?,
        continuation: kotlinx.coroutines.CancellableContinuation<String>,
        webView: WebView,
        handler: Handler,
    ) {
        val script = """
            (function(){
                try {
                    window.scrollTo(0, document.body ? document.body.scrollHeight : 0);
                    return document.readyState || '';
                } catch(e) {
                    return '';
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(script) {
            handler.postDelayed({
                extractHtml(view, continuation, webView, handler)
            }, WEBVIEW_SETTLE_WAIT_MS)
        } ?: extractHtml(view, continuation, webView, handler)
    }

    private fun extractHtml(
        view: WebView?,
        continuation: kotlinx.coroutines.CancellableContinuation<String>,
        webView: WebView,
        handler: Handler,
    ) {
        handler.removeCallbacksAndMessages(null)
        view?.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML})();"
        ) { result ->
            val html = result
                ?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?.replace("\\t", "\t")
                ?.replace("\\/", "/")
                ?.replace("\\u003C", "<")
                .orEmpty()
            if (!html.startsWith("<!", ignoreCase = true) && !html.startsWith("<html", ignoreCase = true)) {
                continuation.resume("")
            } else {
                continuation.resume(html)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().flush()
            }
            webView.destroy()
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }

    private fun HttpURLConnection.decodedInputStream() = when (contentEncoding?.lowercase()) {
        "gzip" -> GZIPInputStream(inputStream)
        "deflate" -> InflaterInputStream(inputStream)
        else -> inputStream
    }

    private fun normalizeUrl(baseUrl: String, href: String): String? {
        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        if (href.startsWith("magnet:", ignoreCase = true)) {
            return href
        }
        return runCatching { URI(baseUrl).resolve(href).toString() }.getOrNull()
    }

    private fun looksLikeResultPage(source: MagnetSearchSource, url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.path.orEmpty().lowercase()
        val query = uri?.rawQuery.orEmpty().lowercase()
        val lowerUrl = url.lowercase()
        if (resultPathKeywords.any { path.contains(it) } ||
            resultQueryKeywords.any { query.contains(it) } ||
            lowerUrl.contains("details.php") ||
            lowerUrl.contains("description.php") ||
            lowerUrl.contains("download.php")) {
            return true
        }
        return when (source.id) {
            "0magnet" -> path.matches(Regex("""/![a-zA-Z0-9]{4}"""))
            "eztv" -> path.startsWith("/ep/")
            "tpb" -> path.endsWith("/description.php") && query.contains("id=")
            "glodls" -> path.contains("/torrent/") || query.contains("torrent")
            "yourbittorrent" -> path.contains("/torrent/")
            "bt4g" -> path.contains("/magnet/") || query.contains("hash=")
            else -> false
        }
    }

    private fun cleanTitle(raw: String): String {
        return decodeHtml(raw)
            .replace(tagRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()
            .take(MAX_TITLE_LENGTH)
    }

    private fun resultContext(html: String, range: IntRange): String {
        val blockStart = listOf(
            html.lastIndexOf("<tr", range.first, ignoreCase = true),
            html.lastIndexOf("<li", range.first, ignoreCase = true),
            html.lastIndexOf("<article", range.first, ignoreCase = true),
            html.lastIndexOf("<item", range.first, ignoreCase = true),
        ).filter { it >= 0 }
            .maxOrNull()
            ?.takeIf { range.first - it <= MAX_RESULT_CONTEXT_CHARS }
        if (blockStart != null) {
            val blockEnd = listOf(
                html.indexOf("</tr>", range.last, ignoreCase = true).takeIf { it >= 0 }?.plus(5),
                html.indexOf("</li>", range.last, ignoreCase = true).takeIf { it >= 0 }?.plus(5),
                html.indexOf("</article>", range.last, ignoreCase = true).takeIf { it >= 0 }?.plus(10),
                html.indexOf("</item>", range.last, ignoreCase = true).takeIf { it >= 0 }?.plus(7),
            ).filterNotNull()
                .filter { it > range.last }
                .minOrNull()
                ?.takeIf { it - range.last <= MAX_RESULT_CONTEXT_CHARS }
            if (blockEnd != null) {
                return html.substring(blockStart, blockEnd.coerceAtLeast(blockStart))
            }
        }
        val start = (range.first - RESULT_CONTEXT_PADDING).coerceAtLeast(0)
        val end = (range.last + RESULT_CONTEXT_PADDING).coerceAtMost(html.length)
        return html.substring(start, end.coerceAtLeast(start))
    }

    internal fun extractSizeText(raw: String): String? {
        val text = decodeHtml(raw)
            .replace(tagRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()
        return labelSizeRegex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizeSizeText)
            ?: sizeRegex.find(text)?.value?.let(::normalizeSizeText)
    }

    private fun normalizeSizeText(raw: String): String {
        val match = sizeRegex.find(raw.trim()) ?: return raw.trim()
        val unitMatch = Regex("""(?i)^(\d+(?:[.,]\d+)?)\s*(bytes?|[kmgtpe]i?b)$""")
            .find(match.value.trim())
            ?: return match.value.trim()
        val number = unitMatch.groupValues[1].replace(',', '.')
        val unit = when (unitMatch.groupValues[2].lowercase()) {
            "byte", "bytes", "b" -> "B"
            "kb" -> "KB"
            "kib" -> "KiB"
            "mb" -> "MB"
            "mib" -> "MiB"
            "gb" -> "GB"
            "gib" -> "GiB"
            "tb" -> "TB"
            "tib" -> "TiB"
            "pb" -> "PB"
            "pib" -> "PiB"
            "eb" -> "EB"
            "eib" -> "EiB"
            else -> unitMatch.groupValues[2].uppercase()
        }
        return "$number $unit"
    }

    private fun fallbackTitleForUrl(url: String): String {
        return runCatching {
            URI(url).path.substringAfterLast('/').ifBlank { URI(url).host.orEmpty() }
        }.getOrDefault("磁力结果")
    }

    private fun titleFromMagnet(magnet: String): String? {
        return TorrentTools.titleForMagnet(magnet)
    }

    private fun decodeHtml(value: String): String {
        val named = value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        return htmlNumericEntityRegex.replace(named) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x", ignoreCase = true)) {
                raw.drop(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            } ?: return@replace match.value
            code.takeIf { it in 0..Char.MAX_VALUE.code }?.toChar()?.toString() ?: match.value
        }
    }

    private fun deriveReferer(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty()
        return when {
            host.contains("0magnet") || host.contains("0mag") -> "${uri.scheme}://$host/"
            host.contains("btsow") -> "https://btsow.one/"
            host.contains("eztv") -> "https://eztvx.to/"
            host.contains("1337x") -> "https://1337x.to/"
            else -> null
        }
    }

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_TITLE_LENGTH = 160
    private const val MAX_DETAIL_PAGES = 15
    private const val MAX_DETAIL_METADATA_CACHE_ENTRIES = 160
    private const val DETAIL_METADATA_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val MAX_RESULT_CONTEXT_CHARS = 6000
    private const val RESULT_CONTEXT_PADDING = 1200
    private const val CF_FIRST_WAIT_MS = 2200L
    private const val CF_RETRY_WAIT_MS = 1400L
    private const val MAX_WEBVIEW_WAIT_MS = 9000L
    private const val WEBVIEW_SETTLE_WAIT_MS = 500L
}
