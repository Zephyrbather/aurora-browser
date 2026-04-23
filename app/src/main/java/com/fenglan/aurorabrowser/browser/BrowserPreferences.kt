package com.fenglan.aurorabrowser.browser

import android.content.Context
import com.fenglan.aurorabrowser.model.BrowserSettings
import com.fenglan.aurorabrowser.model.BrowserThemeMode
import com.fenglan.aurorabrowser.model.SearchEngine
import com.fenglan.aurorabrowser.model.SearchHistoryItem
import com.fenglan.aurorabrowser.model.TranslationEngine
import org.json.JSONArray
import org.json.JSONObject

class BrowserPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): BrowserSettings {
        val themeMode = BrowserThemeMode.fromKey(
            preferences.getString(
                KEY_THEME_MODE,
                if (preferences.getBoolean(KEY_NIGHT_MODE, false)) {
                    BrowserThemeMode.DARK.key
                } else {
                    BrowserThemeMode.LIGHT.key
                },
            ),
        )
        return BrowserSettings(
            nightMode = themeMode.isDark,
            desktopMode = preferences.getBoolean(KEY_DESKTOP_MODE, false),
            adBlockEnabled = preferences.getBoolean(KEY_AD_BLOCK, true),
            showMostVisitedSites = preferences.getBoolean(KEY_SHOW_MOST_VISITED, true),
            themeMode = themeMode,
            translationEngine = TranslationEngine.fromKey(
                preferences.getString(KEY_TRANSLATION_ENGINE, TranslationEngine.MICROSOFT.key),
            ),
        )
    }

    fun setNightMode(enabled: Boolean) {
        val themeMode = if (enabled) BrowserThemeMode.DARK else BrowserThemeMode.LIGHT
        preferences.edit()
            .putBoolean(KEY_NIGHT_MODE, enabled)
            .putString(KEY_THEME_MODE, themeMode.key)
            .apply()
    }

    fun setDesktopMode(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AD_BLOCK, enabled).apply()
    }

    fun setShowMostVisitedSites(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_MOST_VISITED, enabled).apply()
    }

    fun setThemeMode(mode: BrowserThemeMode) {
        preferences.edit()
            .putString(KEY_THEME_MODE, mode.key)
            .putBoolean(KEY_NIGHT_MODE, mode.isDark)
            .apply()
    }

    fun getRecentSearches(): List<String> {
        return getSearchHistory().map { it.query }
    }

    fun getSearchHistory(): List<SearchHistoryItem> {
        val raw = preferences.getString(KEY_RECENT_SEARCHES, "[]").orEmpty()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    readSearchHistoryItem(json, index)?.let(::add)
                }
            }
        }.getOrDefault(emptyList()).distinctBy { it.query.lowercase() }
    }

    fun saveRecentSearch(query: String) {
        val cleaned = query.trim()
        if (cleaned.isBlank()) {
            return
        }
        val updated = buildList {
            add(SearchHistoryItem(cleaned, System.currentTimeMillis()))
            addAll(getSearchHistory().filterNot { it.query.equals(cleaned, ignoreCase = true) }.take(MAX_RECENT_SEARCHES - 1))
        }
        preferences.edit().putString(KEY_RECENT_SEARCHES, encodeSearchHistory(updated)).apply()
    }

    fun deleteRecentSearch(query: String) {
        val updated = getSearchHistory().filterNot { it.query.equals(query.trim(), ignoreCase = true) }
        preferences.edit().putString(KEY_RECENT_SEARCHES, encodeSearchHistory(updated)).apply()
    }

    fun clearRecentSearches() {
        preferences.edit().putString(KEY_RECENT_SEARCHES, "[]").apply()
    }

    fun getSearchEngine(): SearchEngine {
        return SearchEngine.fromKey(preferences.getString(KEY_SEARCH_ENGINE, SearchEngine.BING.key))
    }

    fun setSearchEngine(engine: SearchEngine) {
        preferences.edit().putString(KEY_SEARCH_ENGINE, engine.key).apply()
    }

    fun getTranslationEngine(): TranslationEngine {
        return TranslationEngine.fromKey(preferences.getString(KEY_TRANSLATION_ENGINE, TranslationEngine.MICROSOFT.key))
    }

    fun setTranslationEngine(engine: TranslationEngine) {
        preferences.edit().putString(KEY_TRANSLATION_ENGINE, engine.key).apply()
    }

    fun getBaiduAppId(): String = preferences.getString(KEY_BAIDU_APP_ID, "").orEmpty()

    fun setBaiduAppId(value: String) {
        preferences.edit().putString(KEY_BAIDU_APP_ID, value.trim()).apply()
    }

    fun getBaiduSecret(): String = preferences.getString(KEY_BAIDU_SECRET, "").orEmpty()

    fun setBaiduSecret(value: String) {
        preferences.edit().putString(KEY_BAIDU_SECRET, value.trim()).apply()
    }

    fun getYoudaoAppKey(): String = preferences.getString(KEY_YOUDAO_APP_KEY, "").orEmpty()

    fun setYoudaoAppKey(value: String) {
        preferences.edit().putString(KEY_YOUDAO_APP_KEY, value.trim()).apply()
    }

    fun getYoudaoAppSecret(): String = preferences.getString(KEY_YOUDAO_APP_SECRET, "").orEmpty()

    fun setYoudaoAppSecret(value: String) {
        preferences.edit().putString(KEY_YOUDAO_APP_SECRET, value.trim()).apply()
    }

    fun getDownloadSubdirectory(): String {
        return normalizeDownloadSubdirectory(
            preferences.getString(KEY_DOWNLOAD_SUBDIRECTORY, DEFAULT_DOWNLOAD_SUBDIRECTORY),
        )
    }

    fun setDownloadSubdirectory(path: String) {
        preferences.edit()
            .putString(KEY_DOWNLOAD_SUBDIRECTORY, normalizeDownloadSubdirectory(path))
            .apply()
    }

    fun getStartPageUrl(): String {
        return normalizeStartPageUrl(
            preferences.getString(KEY_START_PAGE_URL, DEFAULT_START_PAGE_URL),
        )
    }

    fun setStartPageUrl(url: String?) {
        preferences.edit()
            .putString(KEY_START_PAGE_URL, normalizeStartPageUrl(url))
            .apply()
    }

    private fun normalizeDownloadSubdirectory(path: String?): String {
        return path
            ?.trim()
            ?.replace('\\', '/')
            ?.replace(Regex("^/+"), "")
            ?.replace(Regex("/+"), "/")
            ?.trim('/')
            ?.ifBlank { DEFAULT_DOWNLOAD_SUBDIRECTORY }
            ?: DEFAULT_DOWNLOAD_SUBDIRECTORY
    }

    private fun normalizeStartPageUrl(url: String?): String {
        return url
            ?.trim()
            ?.ifBlank { DEFAULT_START_PAGE_URL }
            ?: DEFAULT_START_PAGE_URL
    }

    private fun readSearchHistoryItem(json: JSONArray, index: Int): SearchHistoryItem? {
        val rawItem = json.opt(index) ?: return null
        return when (rawItem) {
            is JSONObject -> {
                val query = rawItem.optString("query").trim()
                if (query.isBlank()) {
                    null
                } else {
                    SearchHistoryItem(
                        query = query,
                        searchedAt = rawItem.optLong("searched_at", 0L),
                    )
                }
            }

            is String -> {
                val query = rawItem.trim()
                if (query.isBlank()) null else SearchHistoryItem(query, 0L)
            }

            else -> {
                val query = rawItem.toString().trim()
                if (query.isBlank()) null else SearchHistoryItem(query, 0L)
            }
        }
    }

    private fun encodeSearchHistory(items: List<SearchHistoryItem>): String {
        val json = JSONArray()
        items.forEach { item ->
            json.put(
                JSONObject()
                    .put("query", item.query)
                    .put("searched_at", item.searchedAt),
            )
        }
        return json.toString()
    }

    companion object {
        private const val PREFS_NAME = "aurora_browser_prefs"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_AD_BLOCK = "ad_block"
        private const val KEY_SHOW_MOST_VISITED = "show_most_visited"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DOWNLOAD_SUBDIRECTORY = "download_subdirectory"
        private const val KEY_START_PAGE_URL = "start_page_url"
        private const val KEY_TRANSLATION_ENGINE = "translation_engine"
        private const val KEY_BAIDU_APP_ID = "baidu_app_id"
        private const val KEY_BAIDU_SECRET = "baidu_secret"
        private const val KEY_YOUDAO_APP_KEY = "youdao_app_key"
        private const val KEY_YOUDAO_APP_SECRET = "youdao_app_secret"
        private const val DEFAULT_DOWNLOAD_SUBDIRECTORY = "Aurora"
        private const val DEFAULT_START_PAGE_URL = "about:blank"
        private const val MAX_RECENT_SEARCHES = 50
    }
}
