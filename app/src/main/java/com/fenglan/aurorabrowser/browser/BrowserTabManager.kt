package com.fenglan.aurorabrowser.browser

import android.graphics.Bitmap
import android.webkit.WebView
import com.fenglan.aurorabrowser.data.BrowserStore

data class BrowserTab(
    val id: Long,
    var title: String,
    var url: String,
    val webView: WebView,
    var thumbnail: Bitmap? = null,
)

class BrowserTabManager(
    private val store: BrowserStore,
    private val createTabWebView: () -> WebView,
    private val setupWebView: (WebView) -> Unit,
    private val onActiveTabChanged: (tab: BrowserTab) -> Unit,
    private val primaryWebView: WebView,
) {
    val tabs = mutableListOf<BrowserTab>()
    var currentTabId: Long = 0L
        private set
    private var nextTabId: Long = 1L

    fun ensureInitialTab(defaultTitle: String, defaultUrl: String): Boolean {
        if (tabs.isNotEmpty()) return false
        val restored = restoreTabs(defaultTitle, defaultUrl)
        if (!restored) {
            val firstTab = BrowserTab(
                id = nextTabId++,
                title = defaultTitle,
                url = defaultUrl,
                webView = primaryWebView,
            )
            tabs += firstTab
            currentTabId = firstTab.id
        }
        return true
    }

    fun currentWebView(): WebView {
        return tabs.firstOrNull { it.id == currentTabId }?.webView ?: primaryWebView
    }

    fun tabForWebView(webView: WebView?): BrowserTab? {
        return tabs.firstOrNull { it.webView == webView }
    }

    fun createNewTab(defaultTitle: String, defaultUrl: String) {
        val webView = createTabWebView()
        val newTab = BrowserTab(
            id = nextTabId++,
            title = defaultTitle,
            url = defaultUrl,
            webView = webView,
        )
        tabs += newTab
        setupWebView(webView)
        currentTabId = newTab.id
        updateWebViewVisibility(showHome = true)
        persistTabs()
    }

    fun switchToTab(tabId: Long) {
        val target = tabs.firstOrNull { it.id == tabId } ?: return
        if (target.id == currentTabId) return
        currentTabId = tabId
        updateWebViewVisibility(showHome = target.url == "about:blank")
        onActiveTabChanged(target)
        persistTabs()
    }

    fun closeTab(tabId: Long, defaultTitle: String, defaultUrl: String, disposeWebView: (BrowserTab) -> Unit) {
        if (tabs.size <= 1) {
            closeAllTabs(defaultTitle, defaultUrl, disposeWebView)
            return
        }
        val currentIndex = tabs.indexOfFirst { it.id == tabId }.takeIf { it >= 0 } ?: return
        val closing = tabs.removeAt(currentIndex)
        val closingCurrentTab = closing.id == currentTabId
        disposeWebView(closing)
        if (!closingCurrentTab) {
            persistTabs()
            return
        }
        val nextIndex = (currentIndex - 1).coerceAtLeast(0)
        val target = tabs[nextIndex]
        currentTabId = target.id
        onActiveTabChanged(target)
        updateWebViewVisibility(showHome = target.url == defaultUrl)
        persistTabs()
    }

    fun closeAllTabs(defaultTitle: String, defaultUrl: String, disposeWebView: (BrowserTab) -> Unit) {
        tabs.toList().forEach { tab ->
            if (tab.webView != primaryWebView) {
                disposeWebView(tab)
            }
        }
        tabs.clear()
        val newTab = BrowserTab(
            id = nextTabId++,
            title = defaultTitle,
            url = defaultUrl,
            webView = primaryWebView,
        )
        tabs += newTab
        currentTabId = newTab.id
        updateWebViewVisibility(showHome = true)
        persistTabs()
    }

    fun updateTabTitle(url: String?, title: String?) {
        val tab = tabForWebView(null) ?: return
        // title is updated via the tab reference directly by the WebChromeClient
    }

    fun syncTabUrl(view: WebView?, url: String?) {
        tabForWebView(view)?.url = url ?: tabForWebView(view)?.url ?: "about:blank"
    }

    fun updateWebViewVisibility(showHome: Boolean) {
        tabs.forEach { tab ->
            tab.webView.visibility = if (tab.id == currentTabId && !showHome) WebView.VISIBLE else WebView.INVISIBLE
        }
    }

    fun tabCount(): Int = tabs.size

    private fun restoreTabs(defaultTitle: String, defaultUrl: String): Boolean {
        val (tabStates, activeId) = store.loadTabs()
        if (tabStates.isEmpty()) return false
        tabStates.forEachIndexed { index, state ->
            val webView = if (index == 0) primaryWebView else createTabWebView()
            val tab = BrowserTab(
                id = state.id,
                title = state.title,
                url = state.url,
                webView = webView,
            )
            tabs += tab
            if (tab.id >= nextTabId) nextTabId = tab.id + 1
            setupWebView(webView)
            if (state.url.isNotBlank() && state.url != defaultUrl && state.url != "about:blank") {
                webView.loadUrl(state.url)
            } else {
                webView.loadUrl(defaultUrl)
            }
        }
        currentTabId = activeId
        val activeTab = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
        onActiveTabChanged(activeTab)
        updateWebViewVisibility(showHome = activeTab.url == defaultUrl)
        return true
    }

    fun persistTabs() {
        val tabStates = tabs.map { tab ->
            BrowserStore.TabState(
                id = tab.id,
                title = tab.title,
                url = tab.webView.url?.takeIf { it != "about:blank" } ?: tab.url,
            )
        }
        store.saveTabs(tabStates, currentTabId)
    }
}
