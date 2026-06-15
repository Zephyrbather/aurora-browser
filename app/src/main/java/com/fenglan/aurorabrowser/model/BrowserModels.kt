package com.fenglan.aurorabrowser.model

import java.net.URLEncoder

enum class FileCategory(val value: String, val label: String) {
    ALL("all", "全部"),
    VIDEO("video", "视频"),
    STREAM("stream", "流媒体"),
    SUBTITLE("subtitle", "字幕"),
    AUDIO("audio", "音频"),
    IMAGE("image", "图片"),
    DOCUMENT("document", "文档"),
    ARCHIVE("archive", "压缩包"),
    TORRENT("torrent", "种子"),
    OTHER("other", "其他"),
    ;

    companion object {
        fun fromValue(value: String?): FileCategory {
            return entries.firstOrNull { it.value == value } ?: OTHER
        }
    }
}

enum class SearchEngine(val key: String, val label: String, private val template: String) {
    BING("bing", "Bing", "https://www.bing.com/search?q=%s"),
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    BAIDU("baidu", "Baidu", "https://www.baidu.com/s?wd=%s"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BRAVE("brave", "Brave", "https://search.brave.com/search?q=%s&source=web"),
    PERPLEXITY("perplexity", "Perplexity", "https://www.perplexity.ai/search?q=%s"),
    FELO("felo", "Felo", "https://felo.ai/search?q=%s"),
    PHIND("phind", "Phind", "https://www.phind.com/search?q=%s"),
    ;

    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return template.format(encoded)
    }

    companion object {
        fun fromKey(key: String?): SearchEngine {
            return entries.firstOrNull { it.key == key } ?: BING
        }
    }
}

enum class P2pDownloadEngine(val key: String, val label: String, val summary: String, val actionLabel: String) {
    INTERNAL(
        "internal",
        "内置 libtorrent",
        "使用 Aurora 内置 jlibtorrent 后台下载，进度显示在下载列表",
        "内置下载",
    ),
    EXTERNAL(
        "external",
        "外部 BT 应用",
        "交给系统中的 BT 客户端处理，不同步到 Aurora 下载列表",
        "外部应用",
    ),
    ;

    fun alternate(): P2pDownloadEngine {
        return if (this == INTERNAL) EXTERNAL else INTERNAL
    }

    companion object {
        fun fromKey(key: String?): P2pDownloadEngine {
            return entries.firstOrNull { it.key == key } ?: INTERNAL
        }
    }
}

enum class TranslationEngine(val key: String, val label: String, val requiresCredential: Boolean = false) {
    GOOGLE("google", "谷歌翻译"),
    MICROSOFT("microsoft", "微软翻译"),
    BAIDU("baidu", "百度翻译", true),
    YOUDAO("youdao", "有道翻译", true),
    ;

    companion object {
        fun fromKey(key: String?): TranslationEngine {
            return entries.firstOrNull { it.key == key } ?: GOOGLE
        }
    }
}

enum class BrowserThemeMode(val key: String, val label: String, val isDark: Boolean) {
    LIGHT("light", "极简浅色", false),
    DARK("dark", "极简深色", true),
    EYE_CARE("eye_care", "护眼简约", false),
    PAPER("paper", "纸感简约", false),
    GRAPHITE("graphite", "石墨简约", true),
    TIME_BASED("time_based", "随时间变化", false),
    AURORA("aurora", "极光渐变", true),
    SUNSET("sunset", "日落渐变", false),
    OCEAN("ocean", "海洋渐变", true),
    CHERRY("cherry", "樱花渐变", false),
    NEBULA("nebula", "星云渐变", true),
    CUSTOM_IMAGE("custom_image", "自定义图片", false),
    ;

    companion object {
        fun fromKey(key: String?): BrowserThemeMode {
            return entries.firstOrNull { it.key == key } ?: LIGHT
        }
    }
}

enum class LibraryMode(val value: String, val title: String, val subtitle: String) {
    BOOKMARKS("bookmarks", "书签", "收藏的网址"),
    HISTORY("history", "浏览历史", "可单条删除或全部清空"),
    RESOURCES("resources", "资源嗅探", "捕获网页中的视频和媒体链接"),
    DOWNLOADS("downloads", "下载管理", "按类型查看已下载文件"),
    ;

    companion object {
        fun fromValue(value: String?): LibraryMode {
            return entries.firstOrNull { it.value == value } ?: BOOKMARKS
        }
    }
}

data class BookmarkItem(
    val id: Long,
    val title: String,
    val url: String?,
    val createdAt: Long,
    val folderId: Long,
    val isFolder: Boolean,
)

data class HistoryItem(
    val id: Long,
    val title: String,
    val url: String,
    val visitedAt: Long,
)

data class SearchHistoryItem(
    val query: String,
    val searchedAt: Long,
)

data class TopSiteItem(
    val title: String,
    val url: String,
    val visitCount: Int,
    val visitedAt: Long,
)

data class HomeSiteItem(
    val id: Long,
    val title: String,
    val url: String,
    val createdAt: Long,
)

data class ResourceItem(
    val id: Long,
    val title: String,
    val url: String,
    val pageUrl: String?,
    val mimeType: String?,
    val referer: String?,
    val userAgent: String?,
    val category: FileCategory,
    val isStream: Boolean,
    val capturedAt: Long,
)

data class DownloadItem(
    val id: Long,
    val title: String,
    val sourceUrl: String,
    val pageUrl: String?,
    val mimeType: String?,
    val category: FileCategory,
    val downloadType: String,
    val systemDownloadId: Long?,
    val localUri: String?,
    val status: String,
    val downloadedBytes: Long?,
    val fileSizeBytes: Long?,
    val progressPercent: Int?,
    val errorMessage: String?,
    val createdAt: Long,
    val isPrivate: Boolean = false,
)

data class SavedCredentialItem(
    val id: Long,
    val host: String,
    val pageUrl: String?,
    val username: String,
    val password: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BrowserSettings(
    val nightMode: Boolean = false,
    val desktopMode: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val showMostVisitedSites: Boolean = true,
    val themeMode: BrowserThemeMode = BrowserThemeMode.LIGHT,
    val translationEngine: TranslationEngine = TranslationEngine.MICROSOFT,
)

data class SiteSettings(
    val host: String,
    val desktopMode: Boolean? = null,
    val adBlockEnabled: Boolean? = null,
    val autoTranslate: Boolean = false,
    val autofillEnabled: Boolean = true,
)

data class BrowserUiState(
    val pageTitle: String = "Aurora Browser",
    val currentUrl: String = DEFAULT_HOME_URL,
    val addressBarText: String = "",
    val isLoading: Boolean = false,
    val loadingProgress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isBookmarked: Boolean = false,
    val settings: BrowserSettings = BrowserSettings(),
    val incognito: Boolean = false,
    val blockedRequestCount: Int = 0,
    val findMatchOrdinal: Int = 0,
    val findMatchCount: Int = 0,
)

const val DEFAULT_HOME_URL = "about:blank"

enum class HomeStyle(val key: String, val label: String) {
    DEFAULT("default", "默认样式"),
    MINIMAL("minimal", "极简样式"),
}
