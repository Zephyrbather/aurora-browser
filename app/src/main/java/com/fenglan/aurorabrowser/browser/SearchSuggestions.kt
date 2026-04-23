package com.fenglan.aurorabrowser.browser

import com.fenglan.aurorabrowser.model.SearchEngine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object SearchSuggestions {
    fun fetch(engine: SearchEngine, query: String): List<String> {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        return runCatching {
            when (engine) {
                SearchEngine.GOOGLE -> fetchOpenSearch("https://suggestqueries.google.com/complete/search?client=firefox&q=%s", normalized)
                SearchEngine.BAIDU -> fetchBaidu(normalized)
                SearchEngine.DUCKDUCKGO -> fetchDuckDuckGo(normalized)
                SearchEngine.BING,
                SearchEngine.BRAVE,
                SearchEngine.PERPLEXITY,
                SearchEngine.FELO,
                SearchEngine.PHIND,
                -> fetchOpenSearch("https://api.bing.com/osjson.aspx?query=%s", normalized)
            }
        }.getOrDefault(emptyList())
            .map(String::trim)
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
    }

    private fun fetchOpenSearch(template: String, query: String): List<String> {
        val payload = httpGet(template.format(encode(query)))
        val root = JSONArray(payload)
        val suggestions = root.optJSONArray(1) ?: return emptyList()
        return buildList {
            for (index in 0 until suggestions.length()) {
                add(suggestions.optString(index))
            }
        }
    }

    private fun fetchDuckDuckGo(query: String): List<String> {
        val payload = httpGet("https://duckduckgo.com/ac/?q=${encode(query)}&type=list")
        val root = JSONArray(payload)
        return buildList {
            for (index in 0 until root.length()) {
                add(root.optJSONObject(index)?.optString("phrase").orEmpty())
            }
        }
    }

    private fun fetchBaidu(query: String): List<String> {
        val payload = httpGet("https://suggestion.baidu.com/su?ie=utf-8&json=1&p=3&wd=${encode(query)}")
        val jsonText = payload.substringAfter('(').substringBeforeLast(')').takeIf { it.isNotBlank() } ?: return emptyList()
        val root = JSONObject(jsonText)
        val suggestions = root.optJSONArray("s") ?: return emptyList()
        return buildList {
            for (index in 0 until suggestions.length()) {
                add(suggestions.optString(index))
            }
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(query: String): String {
        return URLEncoder.encode(query, StandardCharsets.UTF_8)
    }
}
