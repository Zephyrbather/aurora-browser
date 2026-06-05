package com.fenglan.aurorabrowser.browser

import android.content.Context
import com.fenglan.aurorabrowser.model.BrowserSettings
import com.fenglan.aurorabrowser.model.BrowserThemeMode
import com.fenglan.aurorabrowser.model.P2pDownloadEngine
import com.fenglan.aurorabrowser.model.SearchEngine
import com.fenglan.aurorabrowser.model.SearchHistoryItem
import com.fenglan.aurorabrowser.model.SiteSettings
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
            nightMode = BrowserThemeTools.isEffectivelyDark(themeMode),
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

    fun getCustomHomeImagePath(): String? {
        return preferences.getString(KEY_CUSTOM_HOME_IMAGE, null)?.takeIf { it.isNotBlank() }
    }

    fun setCustomHomeImagePath(path: String?) {
        preferences.edit().putString(KEY_CUSTOM_HOME_IMAGE, path).apply()
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

    fun getP2pDownloadEngine(): P2pDownloadEngine {
        return P2pDownloadEngine.fromKey(preferences.getString(KEY_P2P_DOWNLOAD_ENGINE, P2pDownloadEngine.INTERNAL.key))
    }

    fun setP2pDownloadEngine(engine: P2pDownloadEngine) {
        preferences.edit().putString(KEY_P2P_DOWNLOAD_ENGINE, engine.key).apply()
    }

    fun getMagnetSources(): List<MagnetSearchSource> {
        return (MagnetSearchSources.builtIn + getCustomMagnetSources())
            .distinctBy { it.id }
    }

    fun getCustomMagnetSources(): List<MagnetSearchSource> {
        val raw = preferences.getString(KEY_CUSTOM_MAGNET_SOURCES, "[]").orEmpty()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    readCustomMagnetSource(json.optJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
            .distinctBy { it.template.lowercase() }
            .take(MAX_CUSTOM_MAGNET_SOURCES)
    }

    fun addCustomMagnetSource(label: String, template: String): MagnetSearchSource? {
        val source = MagnetSearchSources.createCustom(
            id = MagnetSearchSources.customId(label, template),
            label = label,
            template = template,
        ) ?: return null
        val updated = buildList {
            add(source)
            addAll(
                getCustomMagnetSources().filterNot {
                    it.label.equals(source.label, ignoreCase = true) ||
                        it.template.equals(source.template, ignoreCase = true)
                },
            )
        }.take(MAX_CUSTOM_MAGNET_SOURCES)
        preferences.edit().putString(KEY_CUSTOM_MAGNET_SOURCES, encodeMagnetSources(updated)).apply()
        return source
    }

    fun deleteCustomMagnetSource(id: String) {
        val updated = getCustomMagnetSources().filterNot { it.id == id }
        preferences.edit().putString(KEY_CUSTOM_MAGNET_SOURCES, encodeMagnetSources(updated)).apply()
    }

    fun getSelectedMagnetSourceIds(): Set<String> {
        val available = getMagnetSources().map { it.id }.toSet()
        val raw = preferences.getString(KEY_SELECTED_MAGNET_SOURCE_IDS, "").orEmpty()
        val selected = runCatching {
            val json = JSONArray(raw)
            buildSet {
                for (index in 0 until json.length()) {
                    json.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
            .filter { it in available }
            .toSet()
        if (selected.isEmpty()) {
            return available
        }
        return if (selected.containsAll(PREVIOUS_DEFAULT_MAGNET_SOURCE_IDS) ||
            (selected.size == ORIGINAL_DEFAULT_MAGNET_SOURCE_IDS.size && selected.containsAll(ORIGINAL_DEFAULT_MAGNET_SOURCE_IDS))) {
            selected + MagnetSearchSources.builtIn.map { it.id }
        } else {
            selected
        }
    }

    fun setSelectedMagnetSourceIds(ids: Set<String>) {
        val json = JSONArray()
        ids.forEach { json.put(it) }
        preferences.edit().putString(KEY_SELECTED_MAGNET_SOURCE_IDS, json.toString()).apply()
    }

    fun getMagnetSearchHistory(): List<SearchHistoryItem> {
        val raw = preferences.getString(KEY_MAGNET_SEARCH_HISTORY, "[]").orEmpty()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    readSearchHistoryItem(json, index)?.let(::add)
                }
            }
        }.getOrDefault(emptyList()).distinctBy { it.query.lowercase() }
    }

    fun getLastMagnetSearchQuery(): String {
        return preferences.getString(KEY_LAST_MAGNET_SEARCH_QUERY, "").orEmpty()
            .ifBlank { getMagnetSearchHistory().firstOrNull()?.query.orEmpty() }
    }

    fun saveLastMagnetSearchQuery(query: String) {
        preferences.edit().putString(KEY_LAST_MAGNET_SEARCH_QUERY, query.trim()).apply()
    }

    fun getLastMagnetSearchOutcomes(): List<MagnetSearchOutcome> {
        val raw = preferences.getString(KEY_LAST_MAGNET_SEARCH_OUTCOMES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    readMagnetSearchOutcome(json.optJSONObject(i))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveLastMagnetSearchOutcomes(outcomes: List<MagnetSearchOutcome>) {
        val trimmedOutcomes = MagnetSearchStateTools.trimOutcomesForCache(outcomes)
        val json = JSONArray()
        trimmedOutcomes.forEach { outcome ->
            val obj = JSONObject()
            obj.put("sourceId", outcome.source.id)
            obj.put("sourceLabel", outcome.source.label)
            obj.put("error", outcome.errorMessage ?: JSONObject.NULL)
            val resultsArr = JSONArray()
            outcome.results.forEach { r ->
                val robj = JSONObject()
                robj.put("sourceId", r.sourceId)
                robj.put("sourceLabel", r.sourceLabel)
                robj.put("title", r.title)
                robj.put("pageUrl", r.pageUrl ?: JSONObject.NULL)
                robj.put("magnetUrl", r.magnetUrl ?: JSONObject.NULL)
                robj.put("torrentUrl", r.torrentUrl ?: JSONObject.NULL)
                robj.put("sizeText", r.sizeText ?: JSONObject.NULL)
                resultsArr.put(robj)
            }
            obj.put("results", resultsArr)
            json.put(obj)
        }
        preferences.edit().putString(KEY_LAST_MAGNET_SEARCH_OUTCOMES, json.toString()).apply()
    }

    fun getMagnetSourceHealth(): Map<String, MagnetSourceHealth> {
        val raw = preferences.getString(KEY_MAGNET_SOURCE_HEALTH, "{}").orEmpty()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { sourceId ->
                    readMagnetSourceHealth(root.optJSONObject(sourceId))?.let { health ->
                        put(sourceId, health)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun rankMagnetSourcesForSearch(sources: List<MagnetSearchSource>): List<MagnetSearchSource> {
        return MagnetSearchStateTools.rankSourcesForSearch(sources, getMagnetSourceHealth())
    }

    fun recordMagnetSourceHealth(sourceId: String, outcome: MagnetSearchOutcome, durationMs: Long) {
        val existing = getMagnetSourceHealth()
        val updated = MagnetSearchStateTools.updatedHealth(
            previous = existing[sourceId],
            sourceId = sourceId,
            outcome = outcome,
            durationMs = durationMs,
            now = System.currentTimeMillis(),
        )
        val root = JSONObject()
        (existing + (sourceId to updated))
            .entries
            .sortedByDescending { it.value.updatedAt }
            .take(MAX_MAGNET_SOURCE_HEALTH)
            .forEach { (_, health) ->
                root.put(health.sourceId, encodeMagnetSourceHealth(health))
            }
        preferences.edit().putString(KEY_MAGNET_SOURCE_HEALTH, root.toString()).apply()
    }

    fun getLastMagnetSourceId(): String {
        return preferences.getString(KEY_LAST_MAGNET_SOURCE_ID, "").orEmpty()
    }

    fun saveLastMagnetSourceId(sourceId: String) {
        preferences.edit().putString(KEY_LAST_MAGNET_SOURCE_ID, sourceId).apply()
    }

    fun saveMagnetSearchHistory(query: String) {
        val cleaned = query.trim()
        if (cleaned.isBlank()) {
            return
        }
        val updated = buildList {
            add(SearchHistoryItem(cleaned, System.currentTimeMillis()))
            addAll(
                getMagnetSearchHistory()
                    .filterNot { it.query.equals(cleaned, ignoreCase = true) }
                    .take(MAX_MAGNET_SEARCH_HISTORY - 1),
            )
        }
        preferences.edit().putString(KEY_MAGNET_SEARCH_HISTORY, encodeSearchHistory(updated)).apply()
    }

    fun deleteMagnetSearchHistory(query: String) {
        val updated = getMagnetSearchHistory().filterNot { it.query.equals(query.trim(), ignoreCase = true) }
        preferences.edit().putString(KEY_MAGNET_SEARCH_HISTORY, encodeSearchHistory(updated)).apply()
    }

    fun clearMagnetSearchHistory() {
        preferences.edit().putString(KEY_MAGNET_SEARCH_HISTORY, "[]").apply()
    }

    fun getTranslationEngine(): TranslationEngine {
        return TranslationEngine.fromKey(preferences.getString(KEY_TRANSLATION_ENGINE, TranslationEngine.MICROSOFT.key))
    }

    fun setTranslationEngine(engine: TranslationEngine) {
        preferences.edit().putString(KEY_TRANSLATION_ENGINE, engine.key).apply()
    }

    fun getBaiduAppId(): String = SecureTextStore.decrypt(preferences.getString(KEY_BAIDU_APP_ID, ""))

    fun setBaiduAppId(value: String) {
        preferences.edit().putString(KEY_BAIDU_APP_ID, SecureTextStore.encrypt(value.trim())).apply()
    }

    fun getBaiduSecret(): String = SecureTextStore.decrypt(preferences.getString(KEY_BAIDU_SECRET, ""))

    fun setBaiduSecret(value: String) {
        preferences.edit().putString(KEY_BAIDU_SECRET, SecureTextStore.encrypt(value.trim())).apply()
    }

    fun getYoudaoAppKey(): String = SecureTextStore.decrypt(preferences.getString(KEY_YOUDAO_APP_KEY, ""))

    fun setYoudaoAppKey(value: String) {
        preferences.edit().putString(KEY_YOUDAO_APP_KEY, SecureTextStore.encrypt(value.trim())).apply()
    }

    fun getYoudaoAppSecret(): String = SecureTextStore.decrypt(preferences.getString(KEY_YOUDAO_APP_SECRET, ""))

    fun setYoudaoAppSecret(value: String) {
        preferences.edit().putString(KEY_YOUDAO_APP_SECRET, SecureTextStore.encrypt(value.trim())).apply()
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

    fun getHlsConcurrency(): Int {
        return preferences.getInt(KEY_HLS_CONCURRENCY, DEFAULT_HLS_CONCURRENCY)
            .coerceIn(MIN_HLS_CONCURRENCY, MAX_HLS_CONCURRENCY)
    }

    fun setHlsConcurrency(value: Int) {
        preferences.edit()
            .putInt(KEY_HLS_CONCURRENCY, value.coerceIn(MIN_HLS_CONCURRENCY, MAX_HLS_CONCURRENCY))
            .apply()
    }

    fun getSiteSettings(host: String?): SiteSettings? {
        val normalizedHost = normalizeHost(host) ?: return null
        val root = siteSettingsRoot()
        val item = root.optJSONObject(normalizedHost) ?: return SiteSettings(host = normalizedHost)
        return SiteSettings(
            host = normalizedHost,
            desktopMode = if (item.has("desktop_mode")) item.optBoolean("desktop_mode") else null,
            adBlockEnabled = if (item.has("ad_block")) item.optBoolean("ad_block") else null,
            autoTranslate = item.optBoolean("auto_translate", false),
            autofillEnabled = item.optBoolean("autofill", true),
        )
    }

    fun saveSiteSettings(settings: SiteSettings) {
        val normalizedHost = normalizeHost(settings.host) ?: return
        val root = siteSettingsRoot()
        root.put(
            normalizedHost,
            JSONObject().apply {
                settings.desktopMode?.let { put("desktop_mode", it) }
                settings.adBlockEnabled?.let { put("ad_block", it) }
                put("auto_translate", settings.autoTranslate)
                put("autofill", settings.autofillEnabled)
            },
        )
        preferences.edit().putString(KEY_SITE_SETTINGS, root.toString()).apply()
    }

    fun clearSiteSettings(host: String?) {
        val normalizedHost = normalizeHost(host) ?: return
        val root = siteSettingsRoot()
        root.remove(normalizedHost)
        preferences.edit().putString(KEY_SITE_SETTINGS, root.toString()).apply()
    }

    fun getAllSiteSettings(): List<SiteSettings> {
        val root = siteSettingsRoot()
        return buildList {
            root.keys().forEach { host ->
                getSiteSettings(host)?.let(::add)
            }
        }.sortedBy { it.host }
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

    private fun siteSettingsRoot(): JSONObject {
        return runCatching {
            JSONObject(preferences.getString(KEY_SITE_SETTINGS, "{}").orEmpty())
        }.getOrDefault(JSONObject())
    }

    private fun normalizeHost(host: String?): String? {
        return host
            ?.trim()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.ifBlank { null }
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

    private fun readMagnetSearchOutcome(item: JSONObject?): MagnetSearchOutcome? {
        item ?: return null
        val sourceId = item.optString("sourceId")
        val sourceLabel = item.optString("sourceLabel")
        val error = item.optNullableString("error")
        val source = MagnetSearchSource(id = sourceId, label = sourceLabel, template = "")
        val resultsArr = item.optJSONArray("results") ?: JSONArray()
        val results = buildList {
            for (j in 0 until resultsArr.length()) {
                val robj = resultsArr.optJSONObject(j) ?: continue
                add(
                    MagnetSearchResult(
                        sourceId = robj.optString("sourceId"),
                        sourceLabel = robj.optString("sourceLabel"),
                        title = robj.optString("title"),
                        pageUrl = robj.optNullableString("pageUrl"),
                        magnetUrl = robj.optNullableString("magnetUrl"),
                        torrentUrl = robj.optNullableString("torrentUrl"),
                        sizeText = robj.optNullableString("sizeText"),
                    ),
                )
            }
        }
        return MagnetSearchOutcome(source = source, results = results, errorMessage = error)
    }

    private fun readMagnetSourceHealth(item: JSONObject?): MagnetSourceHealth? {
        item ?: return null
        val sourceId = item.optString("sourceId").trim().ifBlank { return null }
        return MagnetSourceHealth(
            sourceId = sourceId,
            lastStatus = MagnetSourceStatus.fromKey(item.optString("lastStatus")),
            lastDurationMs = item.optLong("lastDurationMs", 0L),
            lastResultCount = item.optInt("lastResultCount", 0),
            successCount = item.optInt("successCount", 0),
            failureCount = item.optInt("failureCount", 0),
            verificationCount = item.optInt("verificationCount", 0),
            updatedAt = item.optLong("updatedAt", 0L),
        )
    }

    private fun encodeMagnetSourceHealth(health: MagnetSourceHealth): JSONObject {
        return JSONObject()
            .put("sourceId", health.sourceId)
            .put("lastStatus", health.lastStatus.key)
            .put("lastDurationMs", health.lastDurationMs)
            .put("lastResultCount", health.lastResultCount)
            .put("successCount", health.successCount)
            .put("failureCount", health.failureCount)
            .put("verificationCount", health.verificationCount)
            .put("updatedAt", health.updatedAt)
    }

    private fun readCustomMagnetSource(item: JSONObject?): MagnetSearchSource? {
        item ?: return null
        return MagnetSearchSources.createCustom(
            id = item.optString("id"),
            label = item.optString("label"),
            template = item.optString("template"),
        )
    }

    private fun encodeMagnetSources(items: List<MagnetSearchSource>): String {
        val json = JSONArray()
        items.filter { it.isCustom }.forEach { item ->
            json.put(
                JSONObject()
                    .put("id", item.id)
                    .put("label", item.label)
                    .put("template", item.template),
            )
        }
        return json.toString()
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return optString(name).trim().ifBlank { null }
    }

    companion object {
        private const val PREFS_NAME = "aurora_browser_prefs"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_AD_BLOCK = "ad_block"
        private const val KEY_SHOW_MOST_VISITED = "show_most_visited"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_P2P_DOWNLOAD_ENGINE = "p2p_download_engine"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DOWNLOAD_SUBDIRECTORY = "download_subdirectory"
        private const val KEY_START_PAGE_URL = "start_page_url"
        private const val KEY_TRANSLATION_ENGINE = "translation_engine"
        private const val KEY_HLS_CONCURRENCY = "hls_concurrency"
        private const val KEY_SITE_SETTINGS = "site_settings"
        private const val KEY_CUSTOM_MAGNET_SOURCES = "custom_magnet_sources"
        private const val KEY_SELECTED_MAGNET_SOURCE_IDS = "selected_magnet_source_ids"
        private const val KEY_MAGNET_SEARCH_HISTORY = "magnet_search_history"
        private const val KEY_LAST_MAGNET_SEARCH_QUERY = "last_magnet_search_query"
        private const val KEY_LAST_MAGNET_SEARCH_OUTCOMES = "last_magnet_search_outcomes"
        private const val KEY_LAST_MAGNET_SOURCE_ID = "last_magnet_source_id"
        private const val KEY_MAGNET_SOURCE_HEALTH = "magnet_source_health"
        private val ORIGINAL_DEFAULT_MAGNET_SOURCE_IDS = setOf(
            "0magnet",
            "eztv",
            "btdirectory",
            "btsow",
            "1337x",
            "tpb",
            "bitsearch",
            "torlock",
            "torrentfunk",
            "solidtorrents",
            "idope",
            "btdb",
            "nyaa",
            "torrentgalaxy",
            "magnetdl",
        )
        private val PREVIOUS_DEFAULT_MAGNET_SOURCE_IDS = ORIGINAL_DEFAULT_MAGNET_SOURCE_IDS + setOf(
            "bt4g",
            "limetorrents",
            "glodls",
            "torrentdownloads",
            "yourbittorrent",
            "extto",
            "kickasstorrents",
            "torrentproject",
            "skytorrents",
            "academictorrents",
        )
        private const val KEY_BAIDU_APP_ID = "baidu_app_id"
        private const val KEY_BAIDU_SECRET = "baidu_secret"
        private const val KEY_YOUDAO_APP_KEY = "youdao_app_key"
        private const val KEY_YOUDAO_APP_SECRET = "youdao_app_secret"
        private const val KEY_CUSTOM_HOME_IMAGE = "custom_home_image"
        private const val DEFAULT_DOWNLOAD_SUBDIRECTORY = "Aurora"
        private const val DEFAULT_START_PAGE_URL = "about:blank"
        private const val DEFAULT_HLS_CONCURRENCY = 4
        private const val MIN_HLS_CONCURRENCY = 1
        private const val MAX_HLS_CONCURRENCY = 8
        private const val MAX_RECENT_SEARCHES = 50
        private const val MAX_CUSTOM_MAGNET_SOURCES = 30
        private const val MAX_MAGNET_SEARCH_HISTORY = 50
        private const val MAX_MAGNET_SOURCE_HEALTH = 80
    }
}
