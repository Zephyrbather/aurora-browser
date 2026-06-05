package com.fenglan.aurorabrowser.browser

import com.fenglan.aurorabrowser.model.FileCategory
import org.junit.Assert.*
import org.junit.Test

class ResourceToolsTest {

    // classify

    @Test
    fun `classify - m3u8 is stream`() {
        assertEquals(FileCategory.STREAM, ResourceTools.classify("https://example.com/video.m3u8", null))
    }

    @Test
    fun `classify - mpd is stream`() {
        assertEquals(FileCategory.STREAM, ResourceTools.classify("https://example.com/video.mpd", null))
    }

    @Test
    fun `classify - mp4 is video`() {
        assertEquals(FileCategory.VIDEO, ResourceTools.classify("https://example.com/video.mp4", null))
    }

    @Test
    fun `classify - mp3 is audio`() {
        assertEquals(FileCategory.AUDIO, ResourceTools.classify("https://example.com/song.mp3", null))
    }

    @Test
    fun `classify - jpg is image`() {
        assertEquals(FileCategory.IMAGE, ResourceTools.classify("https://example.com/photo.jpg", null))
    }

    @Test
    fun `classify - pdf is document`() {
        assertEquals(FileCategory.DOCUMENT, ResourceTools.classify("https://example.com/doc.pdf", null))
    }

    @Test
    fun `classify - zip is archive`() {
        assertEquals(FileCategory.ARCHIVE, ResourceTools.classify("https://example.com/file.zip", null))
    }

    @Test
    fun `classify - unknown is other`() {
        assertEquals(FileCategory.OTHER, ResourceTools.classify("https://example.com/data.bin", null))
    }

    @Test
    fun `classify - mpegurl mime is stream`() {
        assertEquals(FileCategory.STREAM, ResourceTools.classify("https://example.com/playlist", "application/x-mpegURL"))
    }

    @Test
    fun `classify - video mime with no extension is video`() {
        assertEquals(FileCategory.VIDEO, ResourceTools.classify("https://example.com/stream", "video/mp4"))
    }

    @Test
    fun `classify - audio mime is audio`() {
        assertEquals(FileCategory.AUDIO, ResourceTools.classify("https://example.com/sound", "audio/mpeg"))
    }

    @Test
    fun `classify - image mime is image`() {
        assertEquals(FileCategory.IMAGE, ResourceTools.classify("https://example.com/photo", "image/png"))
    }

    @Test
    fun `classify - ts segment is stream`() {
        assertEquals(FileCategory.STREAM, ResourceTools.classify("https://example.com/segment.ts", null))
    }

    @Test
    fun `classify - m4s segment is stream`() {
        assertEquals(FileCategory.STREAM, ResourceTools.classify("https://example.com/segment.m4s", null))
    }

    // isStream

    @Test
    fun `isStream - m3u8 returns true`() {
        assertTrue(ResourceTools.isStream("https://example.com/video.m3u8", null))
    }

    @Test
    fun `isStream - mp4 returns false`() {
        assertFalse(ResourceTools.isStream("https://example.com/video.mp4", null))
    }

    @Test
    fun `isStream - mpegurl mime returns true`() {
        assertTrue(ResourceTools.isStream("https://example.com/playlist", "application/x-mpegURL"))
    }

    // isDirectVideo

    @Test
    fun `isDirectVideo - mp4 returns true`() {
        assertTrue(ResourceTools.isDirectVideo("https://example.com/video.mp4", null))
    }

    @Test
    fun `isDirectVideo - m3u8 returns false`() {
        assertFalse(ResourceTools.isDirectVideo("https://example.com/video.m3u8", null))
    }

    @Test
    fun `isDirectVideo - video mime returns true`() {
        assertTrue(ResourceTools.isDirectVideo("https://example.com/stream", "video/mp4"))
    }

    // formatBytes

    @Test
    fun `formatBytes - null returns unknown`() {
        assertEquals("大小未知", ResourceTools.formatBytes(null))
    }

    @Test
    fun `formatBytes - negative returns unknown`() {
        assertEquals("大小未知", ResourceTools.formatBytes(-1))
    }

    @Test
    fun `formatBytes - 0 returns bytes`() {
        assertEquals("0B", ResourceTools.formatBytes(0))
    }

    @Test
    fun `formatBytes - 500 returns bytes`() {
        assertEquals("500B", ResourceTools.formatBytes(500))
    }

    @Test
    fun `formatBytes - 1024 returns KB`() {
        assertEquals("1.0KB", ResourceTools.formatBytes(1024))
    }

    @Test
    fun `formatBytes - 1536 returns KB`() {
        assertEquals("1.5KB", ResourceTools.formatBytes(1536))
    }

    @Test
    fun `formatBytes - 1MB returns MB`() {
        assertEquals("1.0MB", ResourceTools.formatBytes(1024 * 1024))
    }

    @Test
    fun `formatBytes - 1GB returns GB`() {
        assertEquals("1.0GB", ResourceTools.formatBytes(1024L * 1024L * 1024L))
    }

    // sanitizeFileName

    @Test
    fun `sanitizeFileName - normal name unchanged`() {
        assertEquals("video.mp4", ResourceTools.sanitizeFileName("video.mp4"))
    }

    @Test
    fun `sanitizeFileName - removes colons`() {
        assertEquals("video_mp4", ResourceTools.sanitizeFileName("video:mp4"))
    }

    @Test
    fun `sanitizeFileName - removes slashes`() {
        assertEquals("path_file", ResourceTools.sanitizeFileName("path/file"))
    }

    @Test
    fun `sanitizeFileName - empty returns placeholder`() {
        val result = ResourceTools.sanitizeFileName("   ")
        assertTrue(result.startsWith("file_"))
    }

    // ensureExtension

    @Test
    fun `ensureExtension - adds extension if missing`() {
        assertEquals("video.mp4", ResourceTools.ensureExtension("video", "mp4"))
    }

    @Test
    fun `ensureExtension - keeps existing extension`() {
        assertEquals("video.mp4", ResourceTools.ensureExtension("video.mp4", "mp4"))
    }

    @Test
    fun `ensureExtension - adds extension to blank name`() {
        val result = ResourceTools.ensureExtension("", "m3u8")
        assertTrue(result.endsWith(".m3u8"))
    }

    // isYouTubePage

    @Test
    fun `isYouTubePage - non-youtube returns false`() {
        assertFalse(ResourceTools.isYouTubePage("https://example.com/video"))
    }

    @Test
    fun `isYouTubePage - null returns false`() {
        assertFalse(ResourceTools.isYouTubePage(null))
    }
}
