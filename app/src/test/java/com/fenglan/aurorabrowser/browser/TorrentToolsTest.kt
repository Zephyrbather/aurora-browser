package com.fenglan.aurorabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentToolsTest {
    @Test
    fun `extractValidMagnet accepts btih magnet and title`() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Ubuntu%20ISO"

        assertEquals(magnet, TorrentTools.extractValidMagnet(magnet))
        assertEquals("Ubuntu ISO", TorrentTools.titleForMagnet(magnet))
    }

    @Test
    fun `extractValidMagnet finds magnet inside copied text`() {
        val text = "复制：magnet:?xt=urn:btih:abcdefabcdefabcdefabcdefabcdefabcdefabcd&dn=Demo"

        assertNotNull(TorrentTools.extractValidMagnet(text))
    }

    @Test
    fun `extractValidMagnet rejects tracker-only and short hash magnets`() {
        assertEquals(null, TorrentTools.extractValidMagnet("magnet:?tr=https://tracker.example/announce"))
        assertEquals(null, TorrentTools.extractValidMagnet("magnet:?xt=urn:btih:abc123"))
    }

    @Test
    fun `isTorrentUrl detects torrent files and mime type`() {
        assertTrue(TorrentTools.isTorrentUrl("https://example.com/file.torrent?download=1"))
        assertTrue(TorrentTools.isTorrentUrl("https://example.com/download", "application/x-bittorrent"))
        assertFalse(TorrentTools.isTorrentUrl("https://example.com/file.zip"))
    }

    @Test
    fun `withPublicTrackers appends trackers without duplicating existing values`() {
        val existingTracker = TorrentTools.publicTrackers().first()
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&tr=$existingTracker"

        val enhanced = TorrentTools.withPublicTrackers(magnet, limit = 2)

        assertEquals(1, Regex(Regex.escape(existingTracker)).findAll(enhanced).count())
        assertTrue(enhanced.contains("&tr="))
    }
}
