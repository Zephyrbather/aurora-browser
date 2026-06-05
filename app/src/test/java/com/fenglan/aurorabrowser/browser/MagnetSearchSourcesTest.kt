package com.fenglan.aurorabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetSearchSourcesTest {
    @Test
    fun `buildSearchUrl replaces query placeholder`() {
        val source = MagnetSearchSource("demo", "Demo", "https://example.com/search?q={query}")

        assertEquals("https://example.com/search?q=ubuntu+iso", source.buildSearchUrl("ubuntu iso"))
    }

    @Test
    fun `buildSearchUrl replaces legacy placeholder`() {
        val source = MagnetSearchSource("demo", "Demo", "https://example.com/search/%s")

        assertEquals("https://example.com/search/ubuntu+iso", source.buildSearchUrl("ubuntu iso"))
    }

    @Test
    fun `buildSearchUrl replaces path and slug placeholders`() {
        val pathSource = MagnetSearchSource("demo", "Demo", "https://example.com/search/{query%20}")
        val slugSource = MagnetSearchSource("demo", "Demo", "https://example.com/torrents/{query-}.html")

        assertEquals("https://example.com/search/Ubuntu%20ISO", pathSource.buildSearchUrl("Ubuntu ISO"))
        assertEquals("https://example.com/torrents/ubuntu-iso.html", slugSource.buildSearchUrl("Ubuntu ISO"))
    }

    @Test
    fun `buildSearchUrls includes fallback templates without duplicates`() {
        val source = MagnetSearchSource(
            id = "demo",
            label = "Demo",
            template = "https://example.com/search?q={query}",
            fallbackTemplates = listOf(
                "https://mirror.example.com/search/{query%20}",
                "https://example.com/search?q={query}",
            ),
        )

        assertEquals(
            listOf(
                "https://example.com/search?q=ubuntu+iso",
                "https://mirror.example.com/search/ubuntu%20iso",
            ),
            source.buildSearchUrls("ubuntu iso"),
        )
    }

    @Test
    fun `built in 0Magnet prefers co domain before challenge-prone mirrors`() {
        val source = MagnetSearchSources.builtIn.first { it.id == "0magnet" }

        assertEquals(
            "https://0magnet.co/search?q=ubuntu+iso",
            source.buildSearchUrls("ubuntu iso").first(),
        )
    }

    @Test
    fun `isValidTemplate accepts https template with placeholder`() {
        assertTrue(MagnetSearchSources.isValidTemplate("https://example.com/search?q={query}"))
    }

    @Test
    fun `isValidTemplate rejects template without placeholder`() {
        assertFalse(MagnetSearchSources.isValidTemplate("https://example.com/search"))
    }

    @Test
    fun `isValidTemplate rejects non http template`() {
        assertFalse(MagnetSearchSources.isValidTemplate("magnet:?xt={query}"))
    }

    @Test
    fun `createCustom trims valid source`() {
        val source = MagnetSearchSources.createCustom(
            id = "custom_demo",
            label = "  Demo  ",
            template = "  https://example.com/search?q={query}  ",
        )

        assertNotNull(source)
        assertEquals("Demo", source?.label)
        assertEquals("https://example.com/search?q={query}", source?.template)
        assertTrue(source?.isCustom == true)
    }
}
