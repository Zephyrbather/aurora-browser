package com.fenglan.aurorabrowser.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.model.BrowserThemeMode
import com.fenglan.aurorabrowser.model.BookmarkItem
import com.fenglan.aurorabrowser.model.DEFAULT_HOME_URL
import com.fenglan.aurorabrowser.model.BrowserUiState
import com.fenglan.aurorabrowser.model.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val store = BrowserStore.getInstance(application)
    private val preferences = BrowserPreferences(application)

    private val _uiState = MutableStateFlow(BrowserUiState(settings = preferences.getSettings()))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    fun setAddressBarText(text: String) {
        _uiState.update { it.copy(addressBarText = text) }
    }

    fun onPageStarted(url: String?) {
        val resolvedUrl = url ?: _uiState.value.currentUrl
        _uiState.update {
            it.copy(
                currentUrl = resolvedUrl,
                addressBarText = displayAddress(resolvedUrl),
                isLoading = true,
                loadingProgress = 0,
                blockedRequestCount = 0,
            )
        }
        refreshBookmarkState(resolvedUrl)
    }

    fun onProgressChanged(progress: Int) {
        val current = _uiState.value.loadingProgress
        if (progress == current) return
        if (progress !in intArrayOf(0, 100) && current !in intArrayOf(0, 100) && kotlin.math.abs(progress - current) < 5) return
        _uiState.update { it.copy(loadingProgress = progress, isLoading = progress in 0..99) }
    }

    fun onTitleChanged(title: String?) {
        _uiState.update { it.copy(pageTitle = title?.takeIf(String::isNotBlank) ?: it.pageTitle) }
    }

    fun onPageFinished(url: String?, title: String?) {
        val resolvedUrl = url ?: _uiState.value.currentUrl
        val resolvedTitle = title?.takeIf(String::isNotBlank)
            ?: if (resolvedUrl == DEFAULT_HOME_URL) "Aurora Browser" else resolvedUrl
        _uiState.update {
            it.copy(
                currentUrl = resolvedUrl,
                addressBarText = displayAddress(resolvedUrl),
                pageTitle = resolvedTitle,
                isLoading = false,
                loadingProgress = 100,
            )
        }
        refreshBookmarkState(resolvedUrl)
        if (!_uiState.value.incognito && resolvedUrl != DEFAULT_HOME_URL) {
            viewModelScope.launch(Dispatchers.IO) {
                store.addHistory(resolvedTitle, resolvedUrl)
            }
        }
    }

    fun showTab(url: String?, title: String?, canGoBack: Boolean, canGoForward: Boolean) {
        val resolvedUrl = url?.takeIf { it.isNotBlank() } ?: DEFAULT_HOME_URL
        val resolvedTitle = title?.takeIf(String::isNotBlank)
            ?: if (resolvedUrl == DEFAULT_HOME_URL) "Aurora Browser" else resolvedUrl
        _uiState.update {
            it.copy(
                currentUrl = resolvedUrl,
                addressBarText = displayAddress(resolvedUrl),
                pageTitle = resolvedTitle,
                isLoading = false,
                loadingProgress = if (resolvedUrl == DEFAULT_HOME_URL) 0 else 100,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                findMatchOrdinal = 0,
                findMatchCount = 0,
            )
        }
        refreshBookmarkState(resolvedUrl)
    }

    fun updateNavigationState(canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun saveBookmark(title: String, url: String, folderId: Long, onSaved: ((BookmarkItem) -> Unit)? = null) {
        if (url.isBlank()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = store.upsertBookmark(title, url, folderId)
            _uiState.update { current -> current.copy(isBookmarked = true) }
            saved?.let { item ->
                onSaved?.invoke(item)
            }
        }
    }

    fun removeBookmark(url: String, onRemoved: (() -> Unit)? = null) {
        if (url.isBlank()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteBookmarkByUrl(url)
            _uiState.update { current -> current.copy(isBookmarked = false) }
            onRemoved?.invoke()
        }
    }

    fun setNightMode(enabled: Boolean) {
        preferences.setNightMode(enabled)
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    nightMode = enabled,
                    themeMode = if (enabled) BrowserThemeMode.DARK else BrowserThemeMode.LIGHT,
                ),
            )
        }
    }

    fun setDesktopMode(enabled: Boolean) {
        preferences.setDesktopMode(enabled)
        _uiState.update { it.copy(settings = it.settings.copy(desktopMode = enabled)) }
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        preferences.setAdBlockEnabled(enabled)
        _uiState.update { it.copy(settings = it.settings.copy(adBlockEnabled = enabled)) }
    }

    fun setShowMostVisitedSites(enabled: Boolean) {
        preferences.setShowMostVisitedSites(enabled)
        _uiState.update { it.copy(settings = it.settings.copy(showMostVisitedSites = enabled)) }
    }

    fun setThemeMode(mode: BrowserThemeMode) {
        preferences.setThemeMode(mode)
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    themeMode = mode,
                    nightMode = BrowserThemeTools.isEffectivelyDark(mode),
                ),
            )
        }
    }

    fun setTranslationEngine(engine: TranslationEngine) {
        preferences.setTranslationEngine(engine)
        _uiState.update { it.copy(settings = it.settings.copy(translationEngine = engine)) }
    }

    fun setIncognito(enabled: Boolean) {
        _uiState.update { it.copy(incognito = enabled) }
    }

    fun incrementBlockedCount() {
        _uiState.update { it.copy(blockedRequestCount = it.blockedRequestCount + 1) }
    }

    fun updateFindResult(activeOrdinal: Int, totalMatches: Int) {
        _uiState.update { it.copy(findMatchOrdinal = activeOrdinal, findMatchCount = totalMatches) }
    }

    fun clearFindResult() {
        _uiState.update { it.copy(findMatchOrdinal = 0, findMatchCount = 0) }
    }

    fun captureResource(
        url: String,
        title: String?,
        pageUrl: String?,
        mimeType: String?,
        referer: String?,
        userAgent: String?,
    ) {
        if (!ResourceTools.isInteresting(url, mimeType)) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            store.upsertResource(
                title = title ?: url,
                url = url,
                pageUrl = pageUrl,
                mimeType = mimeType,
                referer = referer,
                userAgent = userAgent,
                category = ResourceTools.classify(url, mimeType),
                isStream = ResourceTools.isStream(url, mimeType),
            )
        }
    }

    private fun refreshBookmarkState(url: String?) {
        if (url.isNullOrBlank()) {
            _uiState.update { it.copy(isBookmarked = false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val bookmarked = store.isBookmarked(url)
            _uiState.update { it.copy(isBookmarked = bookmarked) }
        }
    }

    private fun displayAddress(url: String): String {
        return if (url == DEFAULT_HOME_URL) "" else url
    }
}
