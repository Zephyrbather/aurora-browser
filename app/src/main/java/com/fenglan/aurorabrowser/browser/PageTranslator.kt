package com.fenglan.aurorabrowser.browser

import com.fenglan.aurorabrowser.model.TranslationEngine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.runBlocking

data class PageTextSegment(
    val id: String,
    val text: String,
)

data class TranslatedPageText(
    val id: String,
    val translatedText: String,
)

data class TranslationRunResult(
    val items: List<TranslatedPageText>,
    val engineUsed: TranslationEngine,
    val usedFallback: Boolean = false,
    val translatedCount: Int = items.size,
    val totalCount: Int = items.size,
)

typealias WebViewJsEvaluator = suspend (String) -> String

object PageTranslator {
    const val TARGET_LANGUAGE_LABEL = "简体中文"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val MAX_SEGMENTS = 120
    private const val MAX_SEGMENT_TEXT_LENGTH = 280
    private const val BATCH_SIZE = 6
    private const val BATCH_CHAR_LIMIT = 1400

    private val sessionCache = HashMap<String, String>()

    fun clearSessionCache() {
        sessionCache.clear()
    }

    fun buildCollectScript(limit: Int = MAX_SEGMENTS): String {
        val maxLen = limit.coerceAtMost(MAX_SEGMENTS).coerceAtLeast(1)
        return """
            (function() {
              try {
                var old = document.querySelectorAll('[data-aurora-translation-unit="1"]');
                for (var i = 0; i < old.length; i++) {
                  var n = old[i];
                  var orig = n.getAttribute('data-aurora-source') || '';
                  if (!orig) { var s = n.querySelector('.__aurora_translation_source'); orig = s ? s.textContent : ''; }
                  n.replaceWith(document.createTextNode(orig));
                }
                var os = document.getElementById('__aurora_translation_style__');
                if (os) os.remove();
                var root = document.body || document.documentElement;
                if (!root) return '[]';
                var st = document.createElement('style');
                st.id = '__aurora_translation_style__';
                st.textContent = '.__aurora_translation_text{color:#3b82f6;font-size:0.75em;line-height:1.4;}.__aurora_translation_text.__aurora_block{display:block;margin-top:2px;}.__aurora_translation_text.__aurora_inline{display:inline;margin-left:6px;}.__aurora_translation_source{color:inherit;}';
                (document.head || document.documentElement).appendChild(st);
                var SKIP = {SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,INPUT:1,SELECT:1,OPTION:1,BUTTON:1,CANVAS:1,SVG:1,CODE:1,PRE:1,KBD:1,SAMP:1,IFRAME:1,OBJECT:1,EMBED:1};
                var BLOCK = {H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,P:1,LI:1,TD:1,TH:1,DD:1,DT:1,FIGCAPTION:1,CAPTION:1,BLOCKQUOTE:1,DIV:1,SECTION:1,ARTICLE:1,HEADER:1,FOOTER:1,MAIN:1,ASIDE:1,NAV:1,SUMMARY:1};
                var collected = [];
                var all = root.getElementsByTagName('*');
                for (var i = 0; i < all.length && collected.length < $maxLen; i++) {
                  var el = all[i];
                  if (el.getAttribute('data-aurora-translation-unit') === '1') continue;
                  if (SKIP[el.tagName]) continue;
                  if (el.style && (el.style.display === 'none' || el.style.visibility === 'hidden')) continue;
                  var child = el.firstChild;
                  while (child) {
                    if (child.nodeType === 3) {
                      var t = String(child.nodeValue || '').replace(/\s+/g, ' ').trim();
                      if (t.length >= 2 && t.length <= $MAX_SEGMENT_TEXT_LENGTH && /[A-Za-z\u00C0-\u024F\u4E00-\u9FFF]/.test(t) && !/^(https?:\/\/|www\.)/i.test(t)) {
                        collected.push({node:child, text:t, raw:String(child.nodeValue||''), block:BLOCK[el.tagName]?1:0});
                        if (collected.length >= $maxLen) break;
                      }
                    }
                    child = child.nextSibling;
                  }
                }
                var results = [];
                var seed = String(Date.now());
                for (var i = 0; i < collected.length; i++) {
                  var it = collected[i];
                  var cur = it.node;
                  if (!cur.parentNode) continue;
                  var id = 'aurora_translate_' + seed + '_' + i;
                  var w = document.createElement('span');
                  w.setAttribute('data-aurora-translation-unit', '1');
                  w.setAttribute('data-aurora-translation-id', id);
                  w.setAttribute('data-aurora-source', it.raw);
                  var src = document.createElement('span');
                  src.className = '__aurora_translation_source';
                  src.textContent = it.raw;
                  var tgt = document.createElement('span');
                  tgt.className = '__aurora_translation_text ' + (it.block ? '__aurora_block' : '__aurora_inline');
                  tgt.textContent = '...';
                  w.appendChild(src);
                  w.appendChild(tgt);
                  cur.parentNode.replaceChild(w, cur);
                  results.push({id:id, text:it.text});
                }
                return JSON.stringify(results);
              } catch(e) { return JSON.stringify([{error:e.message||'unknown'}]); }
            })();
        """.trimIndent()
    }

    fun buildApplyScript(items: List<TranslatedPageText>): String {
        val payload = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("text", item.translatedText)
                    },
                )
            }
        }.toString()
        return """
            (function() {
              try {
                var items = JSON.parse(${JSONObject.quote(payload)});
                items.forEach(function(item) {
                  var selector = '[data-aurora-translation-id="' + String(item.id).replace(/"/g, '\\"') + '"]';
                  var node = document.querySelector(selector);
                  if (!node) return;
                  var tgt = node.querySelector('.__aurora_translation_text');
                  if (tgt) { tgt.textContent = String(item.text || ''); }
                });
              } catch (error) {}
            })();
        """.trimIndent()
    }

    fun buildClearScript(): String {
        return """
            (function() {
              try {
                document.querySelectorAll('[data-aurora-translation-unit="1"]').forEach(function(node) {
                  var original = node.getAttribute('data-aurora-source');
                  if (!original) {
                    var sourceNode = node.querySelector('.__aurora_translation_source');
                    original = sourceNode ? sourceNode.textContent : '';
                  }
                  node.replaceWith(document.createTextNode(original || ''));
                });
                var styleNode = document.getElementById('__aurora_translation_style__');
                if (styleNode) { styleNode.remove(); }
              } catch (error) {}
            })();
        """.trimIndent()
    }

    fun parseCollectedSegments(rawJson: String): List<PageTextSegment> {
        return runCatching {
            val root = JSONArray(rawJson)
            buildList {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val text = item.optString("text").trim()
                    if (id.isNotBlank() && text.isNotBlank()) {
                        add(PageTextSegment(id, text))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun translate(
        engine: TranslationEngine,
        segments: List<PageTextSegment>,
        preferences: BrowserPreferences,
        webViewEval: WebViewJsEvaluator? = null,
    ): TranslationRunResult {
        if (segments.isEmpty()) {
            return TranslationRunResult(emptyList(), engine)
        }
        val uncachedSegments = segments.filter { sessionCache[it.text] == null }
        val translations = mutableMapOf<String, String>()
        segments.forEach { seg ->
            sessionCache[seg.text]?.let { translations[seg.id] = it }
        }
        Log.d("PageTranslator", "translate: ${segments.size} segments, ${uncachedSegments.size} uncached, engine=$engine")
        if (uncachedSegments.isNotEmpty()) {
            val batches = buildBatches(uncachedSegments)
            batches.forEachIndexed { batchIndex, batch ->
                if (batchIndex > 0) Thread.sleep(500)
                val translatedBatch = if (webViewEval != null) {
                    batch.map { segment ->
                        val cached = sessionCache[segment.text]
                        if (!cached.isNullOrBlank()) return@map cached
                        val httpResult = translateSingleWithFallback(segment.text, preferences)
                        if (httpResult.isNotBlank()) return@map httpResult
                        translateSingleViaWebView(segment.text, webViewEval)
                    }
                } else {
                    try {
                        translateBatchWithFallback(engine, batch, preferences)
                    } catch (error: Throwable) {
                        Log.w("PageTranslator", "Batch fallback: ${error.message?.take(60)}")
                        batch.map { segment -> translateSingleWithFallback(segment.text, preferences) }
                    }
                }
                batch.zip(translatedBatch).forEach { (segment, translatedText) ->
                    if (translatedText.isNotBlank()) {
                        translations[segment.id] = translatedText
                        sessionCache[segment.text] = translatedText
                    }
                }
            }
        }
        val translatedCount = translations.values.count { it.isNotBlank() }
        Log.d("PageTranslator", "translate result: $translatedCount/${segments.size} translated")
        return TranslationRunResult(
            items = segments.map { segment ->
                TranslatedPageText(
                    id = segment.id,
                    translatedText = translations[segment.id].orEmpty(),
                )
            },
            engineUsed = engine,
            usedFallback = false,
            translatedCount = translatedCount,
            totalCount = segments.size,
        )
    }

    private fun translateBatch(
        engine: TranslationEngine,
        batch: List<PageTextSegment>,
        preferences: BrowserPreferences,
    ): List<String> {
        val payload = encodeBatchPayload(batch)
        val translatedPayload = when (engine) {
            TranslationEngine.MICROSOFT -> translateWithMicrosoft(payload)
            TranslationEngine.GOOGLE -> translateWithGoogle(payload)
            TranslationEngine.BAIDU -> translateWithBaidu(
                text = payload,
                appId = preferences.getBaiduAppId(),
                secret = preferences.getBaiduSecret(),
            )

            TranslationEngine.YOUDAO -> translateWithYoudao(
                text = payload,
                appKey = preferences.getYoudaoAppKey(),
                appSecret = preferences.getYoudaoAppSecret(),
            )
        }
        return parseBatchPayload(translatedPayload, batch.size)
            ?: batch.map { segment ->
                when (engine) {
                    TranslationEngine.MICROSOFT -> translateWithMicrosoft(segment.text)
                    TranslationEngine.GOOGLE -> translateWithGoogle(segment.text)
                    TranslationEngine.BAIDU -> translateWithBaidu(
                        text = segment.text,
                        appId = preferences.getBaiduAppId(),
                        secret = preferences.getBaiduSecret(),
                    )

                    TranslationEngine.YOUDAO -> translateWithYoudao(
                        text = segment.text,
                        appKey = preferences.getYoudaoAppKey(),
                        appSecret = preferences.getYoudaoAppSecret(),
                    )
                }
            }
    }

    private fun translateBatchWithFallback(
        engine: TranslationEngine,
        batch: List<PageTextSegment>,
        preferences: BrowserPreferences,
    ): List<String> {
        val engines = mutableListOf(engine)
        if (engine != TranslationEngine.GOOGLE) {
            engines.add(TranslationEngine.GOOGLE)
        }
        var lastError: Throwable? = null
        for (eng in engines) {
            try {
                val result = translateBatch(eng, batch, preferences)
                if (result.any { it.isNotBlank() }) return result
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("all translation engines failed")
    }

    private fun translateSingleWithFallback(
        text: String,
        preferences: BrowserPreferences,
    ): String {
        val engines = listOf(TranslationEngine.GOOGLE, TranslationEngine.MICROSOFT)
        for (eng in engines) {
            try {
                val result = when (eng) {
                    TranslationEngine.GOOGLE -> translateWithGoogle(text)
                    TranslationEngine.MICROSOFT -> translateWithMicrosoft(text)
                    TranslationEngine.BAIDU -> translateWithBaidu(
                        text = text,
                        appId = preferences.getBaiduAppId(),
                        secret = preferences.getBaiduSecret(),
                    )
                    TranslationEngine.YOUDAO -> translateWithYoudao(
                        text = text,
                        appKey = preferences.getYoudaoAppKey(),
                        appSecret = preferences.getYoudaoAppSecret(),
                    )
                }
                if (result.isNotBlank()) return result
            } catch (_: Throwable) { }
        }
        return ""
    }

    private fun translateSingleViaWebView(
        text: String,
        eval: WebViewJsEvaluator,
    ): String {
        return runCatching {
            Log.d("PageTranslator", "WebView fallback for: ${text.take(30)}")
            val js = buildWebViewTranslateScript(text)
            val result = runBlocking { eval(js) }
            val cleaned = result.removeSurrounding("\"").replace("\\n", "\n").replace("\\\"", "\"")
            Log.d("PageTranslator", "WebView result: ${cleaned.take(50)}")
            if (cleaned.isNotBlank() && cleaned != "null" && !cleaned.startsWith("<!")) cleaned else ""
        }.getOrElse {
            Log.w("PageTranslator", "WebView error: ${it.message?.take(60)}")
            ""
        }
    }

    fun buildWebViewTranslateScript(text: String): String {
        return """
            (async function() {
              try {
                var urls = [
                  'https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=' + encodeURIComponent(${JSONObject.quote(text)}),
                  'https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=zh-CN&q=' + encodeURIComponent(${JSONObject.quote(text)})
                ];
                for (var i = 0; i < urls.length; i++) {
                  try {
                    var resp = await fetch(urls[i], {credentials: 'omit'});
                    if (!resp.ok) continue;
                    var data = await resp.json();
                    if (Array.isArray(data) && data.length > 0 && Array.isArray(data[0])) {
                      return data[0].map(function(a){return a[0]||''}).join('');
                    }
                    if (Array.isArray(data) && data.length > 0 && typeof data[0] === 'string') {
                      return data.join('');
                    }
                  } catch(e) {}
                }
                return '';
              } catch(e) { return ''; }
            })();
        """.trimIndent()
    }

    private fun buildBatches(segments: List<PageTextSegment>): List<List<PageTextSegment>> {
        val batches = mutableListOf<MutableList<PageTextSegment>>()
        var currentBatch = mutableListOf<PageTextSegment>()
        var currentChars = 0
        segments.forEach { segment ->
            val projectedChars = currentChars + segment.text.length + 32
            if (
                currentBatch.isNotEmpty() &&
                (currentBatch.size >= BATCH_SIZE || projectedChars > BATCH_CHAR_LIMIT)
            ) {
                batches += currentBatch
                currentBatch = mutableListOf()
                currentChars = 0
            }
            currentBatch += segment
            currentChars += segment.text.length + 32
        }
        if (currentBatch.isNotEmpty()) {
            batches += currentBatch
        }
        return batches
    }

    private fun encodeBatchPayload(batch: List<PageTextSegment>): String {
        return buildString {
            batch.forEachIndexed { index, segment ->
                append(marker(index))
                append('\n')
                append(segment.text)
                if (index != batch.lastIndex) {
                    append("\n")
                }
            }
        }
    }

    private fun parseBatchPayload(payload: String, expectedSize: Int): List<String>? {
        val matches = MARKER_REGEX.findAll(payload).toList()
        if (matches.size != expectedSize) {
            return null
        }
        return matches.mapIndexed { index, match ->
            val contentStart = match.range.last + 1
            val contentEnd = matches.getOrNull(index + 1)?.range?.first ?: payload.length
            payload.substring(contentStart, contentEnd).trim()
        }
    }

    private fun translateWithGoogle(text: String): String {
        val endpoints = listOf(
            "https://lingva.ml/api/v1/auto/zh/${encode(text)}",
            "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=zh-CN&q=${encode(text)}",
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=${encode(text)}",
        )
        for (endpoint in endpoints) {
            try {
                val payload = httpGet(endpoint, accept = "application/json,text/plain,*/*")
                if (payload.isBlank() || payload.startsWith("<!")) continue
                if (endpoint.contains("lingva.ml")) {
                    val root = JSONObject(payload)
                    val result = root.optString("translation", "")
                    if (result.isNotBlank()) return result
                } else {
                    val root = JSONArray(payload)
                    val translatedChunks = root.optJSONArray(0) ?: continue
                    val result = buildString {
                        for (index in 0 until translatedChunks.length()) {
                            append(translatedChunks.optJSONArray(index)?.optString(0).orEmpty())
                        }
                    }
                    if (result.isNotBlank()) return result
                }
            } catch (e: Throwable) {
                Log.w("PageTranslator", "Translate failed (${endpoint.substringBefore("?").take(40)}): ${e.message?.take(60)}")
            }
            Thread.sleep(300)
        }
        return try {
            val result = translateWithMyMemory(text)
            if (result.isNotBlank()) result else throw IllegalStateException("mymemory empty")
        } catch (e: Throwable) {
            Log.w("PageTranslator", "All engines failed: ${e.message?.take(60)}")
            throw e
        }
    }

    private fun translateWithMicrosoft(text: String): String {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val token = fetchMicrosoftToken()
                val endpoint = "https://cn.bing.com/ttranslatev3?isVertical=1&IG=${token.ig}&IID=translator.5028.1"
                val response = httpPostForm(
                    url = endpoint,
                    form = linkedMapOf(
                        "fromLang" to "auto-detect",
                        "to" to "zh-Hans",
                        "text" to text,
                        "token" to token.token,
                        "key" to token.key,
                    ),
                    referer = "https://cn.bing.com/translator",
                )
                if (response.contains("\"ShowCaptcha\":true", ignoreCase = true)) {
                    throw IllegalStateException("microsoft translator captcha")
                }
                val root = JSONArray(response)
                return buildString {
                    for (index in 0 until root.length()) {
                        val item = root.optJSONObject(index) ?: continue
                        val translations = item.optJSONArray("translations") ?: continue
                        append(translations.optJSONObject(0)?.optString("text").orEmpty())
                    }
                }
            } catch (e: Throwable) {
                lastError = e
                if (attempt == 0) Thread.sleep(1000)
            }
        }
        throw lastError ?: IllegalStateException("microsoft translation failed")
    }

    private fun translateWithBaidu(text: String, appId: String, secret: String): String {
        require(appId.isNotBlank() && secret.isNotBlank()) { "baidu credential missing" }
        val salt = System.currentTimeMillis().toString()
        val sign = md5(appId + text + salt + secret)
        val url =
            "https://fanyi-api.baidu.com/api/trans/vip/translate?q=${encode(text)}&from=auto&to=zh&appid=${encode(appId)}&salt=$salt&sign=$sign"
        val payload = httpGet(url, accept = "application/json,text/plain,*/*")
        val root = JSONObject(payload)
        if (root.has("error_code")) {
            throw IllegalStateException(root.optString("error_msg").ifBlank { root.optString("error_code") })
        }
        val translations = root.optJSONArray("trans_result") ?: return ""
        return buildString {
            for (index in 0 until translations.length()) {
                append(translations.optJSONObject(index)?.optString("dst").orEmpty())
            }
        }
    }

    private fun translateWithYoudao(text: String, appKey: String, appSecret: String): String {
        require(appKey.isNotBlank() && appSecret.isNotBlank()) { "youdao credential missing" }
        val salt = System.currentTimeMillis().toString()
        val currentTime = (System.currentTimeMillis() / 1000L).toString()
        val sign = sha256(appKey + truncateForYoudao(text) + salt + currentTime + appSecret)
        val response = httpPostForm(
            url = "https://openapi.youdao.com/api",
            form = linkedMapOf(
                "q" to text,
                "from" to "auto",
                "to" to "zh-CHS",
                "appKey" to appKey,
                "salt" to salt,
                "sign" to sign,
                "signType" to "v3",
                "curtime" to currentTime,
            ),
        )
        val root = JSONObject(response)
        if (root.optString("errorCode") != "0") {
            throw IllegalStateException(root.optString("errorCode"))
        }
        val translations = root.optJSONArray("translation") ?: return ""
        return buildString {
            for (index in 0 until translations.length()) {
                append(translations.optString(index))
            }
        }
    }

    private fun fetchMicrosoftToken(): MicrosoftToken {
        val html = httpGet(
            url = "https://cn.bing.com/translator",
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        val ig = Regex("""_G\.IG="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        val tokenMatch = Regex("""params_AbusePreventionHelper\s*=\s*\[(\d+),"([^"]+)",\d+\]""")
            .find(html)
        val key = tokenMatch?.groupValues?.getOrNull(1)
        val token = tokenMatch?.groupValues?.getOrNull(2)
        require(!ig.isNullOrBlank() && !key.isNullOrBlank() && !token.isNullOrBlank()) {
            "microsoft translator token missing"
        }
        return MicrosoftToken(ig = ig, key = key, token = token)
    }

    private fun translateWithMyMemory(text: String): String {
        val payload = httpGet(
            url = "https://api.mymemory.translated.net/get?q=${encode(text)}&langpair=auto|zh-CN",
            accept = "application/json,text/plain,*/*",
        )
        val root = JSONObject(payload)
        val translated = root.optJSONObject("responseData")?.optString("translatedText").orEmpty()
        if (translated.isNotBlank()) {
            return translated
        }
        throw IllegalStateException("mymemory translation failed")
    }

    private fun httpGet(url: String, accept: String): String {
        val connection = openConnection(url).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
        }
        return connection.readResponse()
    }

    private fun httpPostForm(
        url: String,
        form: LinkedHashMap<String, String>,
        referer: String? = null,
    ): String {
        val body = form.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val connection = openConnection(url).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            referer?.let { setRequestProperty("Referer", it) }
        }
        connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(body)
            writer.flush()
        }
        return connection.readResponse()
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",zh-CN;q=0.9,en;q=0.8")
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private fun HttpURLConnection.readResponse(): String {
        return try {
            val stream = if (responseCode in 200..299) inputStream else errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IllegalStateException(if (body.isBlank()) "http $responseCode" else body.take(200))
            }
            body
        } finally {
            disconnect()
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun md5(value: String): String = digest("MD5", value)

    private fun sha256(value: String): String = digest("SHA-256", value)

    private fun digest(algorithm: String, value: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { append("%02x".format(it.toInt() and 0xff)) }
        }
    }

    private fun truncateForYoudao(value: String): String {
        return if (value.length <= 20) {
            value
        } else {
            value.take(10) + value.length + value.takeLast(10)
        }
    }

    private fun marker(index: Int): String = "AURORA_SEG_$index"

    private val MARKER_REGEX = Regex("""AURORA_SEG_(\d+)""")

    private data class MicrosoftToken(
        val ig: String,
        val key: String,
        val token: String,
    )
}
