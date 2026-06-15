package com.fenglan.aurorabrowser.browser

import com.fenglan.aurorabrowser.model.DEFAULT_HOME_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationStateToolsTest {
    @Test
    fun `shouldClearSearchInputsForUrl only clears built in home`() {
        assertTrue(BrowserNavigationStateTools.shouldClearSearchInputsForUrl(DEFAULT_HOME_URL))
        assertTrue(BrowserNavigationStateTools.shouldClearSearchInputsForUrl("  $DEFAULT_HOME_URL  "))
        assertFalse(BrowserNavigationStateTools.shouldClearSearchInputsForUrl("https://example.com"))
        assertFalse(BrowserNavigationStateTools.shouldClearSearchInputsForUrl(null))
    }

    @Test
    fun `resolveTabDisplayState keeps saved url for frozen tab with blank webview`() {
        val state = BrowserNavigationStateTools.resolveTabDisplayState(
            savedUrl = "https://example.com/page",
            savedTitle = "Saved title",
            webViewUrl = DEFAULT_HOME_URL,
            webViewTitle = "",
            frozen = true,
        )

        assertEquals("https://example.com/page", state.url)
        assertEquals("Saved title", state.title)
        assertTrue(state.isRestoring)
    }

    @Test
    fun `resolveTabDisplayState treats empty saved url as home during restore`() {
        val state = BrowserNavigationStateTools.resolveTabDisplayState(
            savedUrl = "",
            savedTitle = "New tab",
            webViewUrl = null,
            webViewTitle = null,
            frozen = false,
        )

        assertEquals(DEFAULT_HOME_URL, state.url)
        assertEquals("New tab", state.title)
        assertTrue(state.isRestoring)
    }

    @Test
    fun `resolveTabDisplayState prefers live webview url and title for active loaded tab`() {
        val state = BrowserNavigationStateTools.resolveTabDisplayState(
            savedUrl = "https://old.example",
            savedTitle = "Old",
            webViewUrl = "https://new.example",
            webViewTitle = "New",
            frozen = false,
        )

        assertEquals("https://new.example", state.url)
        assertEquals("New", state.title)
        assertFalse(state.isRestoring)
    }
}
