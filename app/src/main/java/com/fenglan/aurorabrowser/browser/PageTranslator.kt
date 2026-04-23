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
)

object PageTranslator {
    const val TARGET_LANGUAGE_LABEL = "简体中文"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val MAX_SEGMENTS = 120
    private const val MAX_SEGMENT_TEXT_LENGTH = 280
    private const val BATCH_SIZE = 6
    private const val BATCH_CHAR_LIMIT = 1400

    fun buildCollectScript(limit: Int = MAX_SEGMENTS): String {
        return """
            (function() {
              try {
                var clearExisting = function() {
                  document.querySelectorAll('ruby[data-aurora-translation-unit="1"]').forEach(function(node) {
                    var original = node.getAttribute('data-aurora-source');
                    if (!original) {
                      var sourceNode = node.querySelector('.__aurora_translation_source');
                      original = sourceNode ? sourceNode.textContent : '';
                    }
                    node.replaceWith(document.createTextNode(original || ''));
                  });
                  var styleNode = document.getElementById('__aurora_translation_style__');
                  if (styleNode) {
                    styleNode.remove();
                  }
                };
                clearExisting();
                if (!document.body && !document.documentElement) {
                  return '[]';
                }
                var ensureStyle = function() {
                  var styleNode = document.getElementById('__aurora_translation_style__');
                  if (!styleNode) {
                    styleNode = document.createElement('style');
                    styleNode.id = '__aurora_translation_style__';
                    (document.head || document.documentElement).appendChild(styleNode);
                  }
                  styleNode.textContent =
                    'ruby[data-aurora-translation-unit="1"]{ruby-position:under;white-space:pre-wrap;line-height:1.65;}' +
                    'ruby[data-aurora-translation-unit="1"] rt.__aurora_translation_text{' +
                    'color:#3b82f6;font-size:0.72em;line-height:1.35;white-space:normal;}' +
                    'ruby[data-aurora-translation-unit="1"] .__aurora_translation_source{color:inherit;}';
                };
                ensureStyle();
                var root = document.body || document.documentElement;
                var results = [];
                var seed = String(Date.now());
                var shouldSkip = function(parent, text) {
                  if (!parent || !text) {
                    return true;
                  }
                  if (parent.closest && parent.closest(
                    '[data-aurora-translation-unit="1"],script,style,noscript,textarea,input,select,option,button,canvas,svg,code,pre,kbd,samp'
                  )) {
                    return true;
                  }
                  var compact = String(text).replace(/\s+/g, ' ').trim();
                  if (!compact || compact.length < 2 || compact.length > $MAX_SEGMENT_TEXT_LENGTH) {
                    return true;
                  }
                  if (!/[A-Za-z\u00C0-\u024F\u4E00-\u9FFF]/.test(compact)) {
                    return true;
                  }
                  if (/^(https?:\/\/|www\.)/i.test(compact)) {
                    return true;
                  }
                  var style = window.getComputedStyle(parent);
                  if (!style || style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity || '1') === 0) {
                    return true;
                  }
                  var rect = parent.getBoundingClientRect();
                  return !rect || rect.width < 4 || rect.height < 4;
                };
                var walker = document.createTreeWalker(
                  root,
                  NodeFilter.SHOW_TEXT,
                  {
                    acceptNode: function(node) {
                      return shouldSkip(node && node.parentElement, node && node.nodeValue)
                        ? NodeFilter.FILTER_REJECT
                        : NodeFilter.FILTER_ACCEPT;
                    }
                  }
                );
                var current;
                while ((current = walker.nextNode()) && results.length < ${limit.coerceAtMost(MAX_SEGMENTS).coerceAtLeast(1)}) {
                  var raw = String(current.nodeValue || '');
                  var compact = raw.replace(/\s+/g, ' ').trim();
                  var wrapper = document.createElement('ruby');
                  var id = 'aurora_translate_' + seed + '_' + results.length;
                  wrapper.setAttribute('data-aurora-translation-unit', '1');
                  wrapper.setAttribute('data-aurora-translation-id', id);
                  wrapper.setAttribute('data-aurora-source', raw);
                  var source = document.createElement('span');
                  source.className = '__aurora_translation_source';
                  source.textContent = raw;
                  var translation = document.createElement('rt');
                  translation.className = '__aurora_translation_text';
                  translation.textContent = '翻译中...';
                  wrapper.appendChild(source);
                  wrapper.appendChild(translation);
                  if (current.parentNode) {
                    current.parentNode.replaceChild(wrapper, current);
                    results.push({ id: id, text: compact });
                  }
                }
                return JSON.stringify(results);
              } catch (error) {
                return '[]';
              }
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
                  var selector = 'ruby[data-aurora-translation-id="' + String(item.id).replace(/"/g, '\\"') + '"]';
                  var node = document.querySelector(selector);
                  if (!node) {
                    return;
                  }
                  var translation = node.querySelector('rt.__aurora_translation_text');
                  if (translation) {
                    translation.textContent = String(item.text || '');
                  }
                });
              } catch (error) {}
            })();
        """.trimIndent()
    }

    fun buildClearScript(): String {
        return """
            (function() {
              try {
                document.querySelectorAll('ruby[data-aurora-translation-unit="1"]').forEach(function(node) {
                  var original = node.getAttribute('data-aurora-source');
                  if (!original) {
                    var sourceNode = node.querySelector('.__aurora_translation_source');
                    original = sourceNode ? sourceNode.textContent : '';
                  }
                  node.replaceWith(document.createTextNode(original || ''));
                });
                var styleNode = document.getElementById('__aurora_translation_style__');
                if (styleNode) {
                  styleNode.remove();
                }
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
    ): TranslationRunResult {
        if (segments.isEmpty()) {
            return TranslationRunResult(emptyList(), engine)
        }
        val translations = mutableMapOf<String, String>()
        val batches = buildBatches(segments)
        var engineUsed = engine
        var usedFallback = false
        batches.forEach { batch ->
            val translatedBatch = try {
                translateBatch(engine, batch, preferences)
            } catch (error: Throwable) {
                if (engine == TranslationEngine.MICROSOFT) {
                    engineUsed = TranslationEngine.GOOGLE
                    usedFallback = true
                    translateBatch(TranslationEngine.GOOGLE, batch, preferences)
                } else {
                    throw error
                }
            }
            batch.zip(translatedBatch).forEach { (segment, translatedText) ->
                translations[segment.id] = translatedText
            }
        }
        return TranslationRunResult(
            items = segments.map { segment ->
                TranslatedPageText(
                    id = segment.id,
                    translatedText = translations[segment.id].orEmpty(),
                )
            },
            engineUsed = engineUsed,
            usedFallback = usedFallback,
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
        return runCatching {
            val payload = httpGet(
                url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=${encode(text)}",
                accept = "application/json,text/plain,*/*",
            )
            val root = JSONArray(payload)
            val translatedChunks = root.optJSONArray(0) ?: return@runCatching ""
            buildString {
                for (index in 0 until translatedChunks.length()) {
                    append(translatedChunks.optJSONArray(index)?.optString(0).orEmpty())
                }
            }
        }.getOrElse {
            translateWithMyMemory(text)
        }
    }

    private fun translateWithMicrosoft(text: String): String {
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
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",zh-CN;q=0.9,en;q=0.8")
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private fun HttpURLConnection.readResponse(): String {
        return try {
            val stream = if (responseCode in 200..299) inputStream else errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty().also {
                if (responseCode !in 200..299) {
                    throw IllegalStateException(if (it.isBlank()) "http $responseCode" else it)
                }
            }
        } finally {
            disconnect()
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
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
