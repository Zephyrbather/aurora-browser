package com.fenglan.aurorabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetSearchClientTest {
    private val source = MagnetSearchSource("demo", "Demo", "https://example.com/search?q={query}")

    @Test
    fun `parseResults extracts magnet links`() {
        val html = """
            <a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567">Ubuntu ISO</a>
        """.trimIndent()

        val results = MagnetSearchClient.parseResults(source, "https://example.com/search?q=ubuntu", html)

        assertEquals(1, results.size)
        assertEquals("Ubuntu ISO", results.first().title)
        assertTrue(results.first().copyUrl.startsWith("magnet:?xt="))
    }

    @Test
    fun `parseResults ignores invalid magnet links`() {
        val html = """
            <a href="magnet:">Empty magnet</a>
            <a href="magnet:?tr=https://tracker.example/announce">Tracker only</a>
            <a href="magnet:?xt=urn:btih:abc123">Short hash</a>
        """.trimIndent()

        val results = MagnetSearchClient.parseResults(source, "https://example.com/search?q=ubuntu", html)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `parseResults extracts valid magnets outside anchor hrefs`() {
        val html = """
            <button data-clipboard-text="magnet:?xt=urn:btih:abcdefabcdefabcdefabcdefabcdefabcdefabcd&amp;dn=Ubuntu%20ISO">Copy</button>
        """.trimIndent()

        val results = MagnetSearchClient.parseResults(source, "https://example.com/search?q=ubuntu", html)

        assertEquals(1, results.size)
        assertEquals("Ubuntu ISO", results.first().title)
    }

    @Test
    fun `parseResults extracts size from result row`() {
        val html = """
            <table>
                <tr>
                    <td><a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567">Ubuntu ISO</a></td>
                    <td>Size 1.25 GB</td>
                    <td>120 seeders</td>
                </tr>
            </table>
        """.trimIndent()

        val results = MagnetSearchClient.parseResults(source, "https://example.com/search?q=ubuntu", html)

        assertEquals(1, results.size)
        assertEquals("1.25 GB", results.first().sizeText)
    }

    @Test
    fun `extractSizeText supports Chinese labels and binary units`() {
        val size = MagnetSearchClient.extractSizeText("<span>文件大小：734,5 MiB</span>")

        assertEquals("734.5 MiB", size)
    }

    @Test
    fun `parseResults resolves torrent detail links`() {
        val html = """
            <a href="/torrent/123/ubuntu-iso/">Ubuntu ISO torrent</a>
        """.trimIndent()

        val results = MagnetSearchClient.parseResults(source, "https://example.com/search?q=ubuntu", html)

        assertEquals(1, results.size)
        assertEquals("https://example.com/torrent/123/ubuntu-iso/", results.first().openUrl)
    }

    @Test
    fun `parseResults attaches row magnet to detail result`() {
        val html = """
            <table>
                <tr>
                    <td><a href="/torrent/123/ubuntu-iso/">Ubuntu ISO torrent</a></td>
                    <td><button data-copy="magnet:?xt=urn:btih:abcdefabcdefabcdefabcdefabcdefabcdefabcd&amp;dn=Ubuntu%20ISO">复制</button></td>
                    <td>Size 2.5 GB</td>
                </tr>
            </table>
        """.trimIndent()

        val results = MagnetSearchClient.parseResults(source, "https://example.com/search?q=ubuntu", html)

        assertEquals(1, results.size)
        assertEquals("https://example.com/torrent/123/ubuntu-iso/", results.first().openUrl)
        assertEquals("2.5 GB", results.first().sizeText)
        assertTrue(results.first().copyUrl.startsWith("magnet:?xt="))
    }

    @Test
    fun `parseResults recognizes 0Magnet short detail links`() {
        val magnetSource = MagnetSearchSource("0magnet", "0Magnet", "https://0magnet.co/search?q={query}")
        val html = """
            <a href="/!kkhz">Ubuntu ISO</a>
        """.trimIndent()

        val results = MagnetSearchClient.parseResults(magnetSource, "https://0magnet.co/search?q=ubuntu", html)

        assertEquals(1, results.size)
        assertEquals("https://0magnet.co/!kkhz", results.first().openUrl)
    }

    @Test
    fun `parseResults ignores navigation links`() {
        val html = """
            <a href="/about">About</a>
            <a href="javascript:void(0)">Menu</a>
        """.trimIndent()

        val results = MagnetSearchClient.parseResults(source, "https://example.com/search?q=ubuntu", html)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `looksLikeChallengePage ignores normal attribution text`() {
        val html = """
            <html><body>
            <a href="/!kkhz">Ubuntu ISO</a>
            <footer>Cloudflare attribution is mentioned in analytics text.</footer>
            </body></html>
        """.trimIndent()

        assertFalse(MagnetSearchClient.looksLikeChallengePage(html))
    }

    @Test
    fun `looksLikeChallengePage detects explicit challenge pages`() {
        val html = """
            <html><head><title>Just a moment...</title></head>
            <body><script>window._cf_chl_opt = {};</script></body></html>
        """.trimIndent()

        assertTrue(MagnetSearchClient.looksLikeChallengePage(html))
    }
}
