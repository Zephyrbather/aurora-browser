package com.fenglan.aurorabrowser.browser

import com.fenglan.aurorabrowser.model.DEFAULT_HOME_URL

object BrowserNavigationStateTools {
    fun shouldClearSearchInputsForUrl(url: String?): Boolean {
        return url?.trim() == DEFAULT_HOME_URL
    }

    fun resolveTabDisplayState(
        savedUrl: String,
        savedTitle: String,
        webViewUrl: String?,
        webViewTitle: String?,
        frozen: Boolean,
    ): TabDisplayState {
        val isRestoring = frozen || webViewUrl.isNullOrBlank() || webViewUrl == DEFAULT_HOME_URL
        val url = if (isRestoring) {
            savedUrl.takeIf { it.isNotBlank() && it != DEFAULT_HOME_URL } ?: DEFAULT_HOME_URL
        } else {
            webViewUrl ?: savedUrl
        }
        val title = if (isRestoring) {
            savedTitle
        } else {
            webViewTitle?.takeIf { it.isNotBlank() } ?: savedTitle
        }
        return TabDisplayState(url, title, isRestoring)
    }
}

data class TabDisplayState(
    val url: String,
    val title: String,
    val isRestoring: Boolean,
)
