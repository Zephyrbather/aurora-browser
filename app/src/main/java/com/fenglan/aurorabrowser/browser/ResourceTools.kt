package com.fenglan.aurorabrowser.browser

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.fenglan.aurorabrowser.model.FileCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ResourceTools {
    private val streamExtensions = setOf("m3u8", "mpd")
    private val videoExtensions = setOf("mp4", "webm", "mkv", "mov", "m4v")
    private val segmentExtensions = setOf("ts", "m4s", "cmfa", "cmfv")
    private val subtitleExtensions = setOf("vtt", "srt", "ass", "ssa")
    private val audioExtensions = setOf("mp3", "aac", "m4a", "wav", "ogg", "flac")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
    private val documentExtensions = setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz")
    private val torrentExtensions = setOf("torrent")
    private val filteredExtensions = setOf("bin", "dat", "tmp", "exe", "dll", "so")
    private val genericMediaBaseNames = setOf(
        "index", "master", "playlist", "stream", "video", "media",
        "play", "download", "downloadfile", "file", "default",
    )
    private val videoHostSuffixes = listOf(
        "googlevideo.com", "youtube.com", "youtu.be",
        "vimeocdn.com", "akamaihd.net", "fastly.net",
        "cdninstagram.com", "fbcdn.net", "tiktokcdn.com",
        "twimg.com", "bilibili.com", "bilivideo.com",
    )

    fun isYouTubePage(url: String?): Boolean {
        val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }?.lowercase() ?: return false
        return host == "www.youtube.com" || host == "m.youtube.com" || host == "youtube.com" || host == "youtu.be"
    }

    fun isVideoHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        return videoHostSuffixes.any { host.endsWith(it) }
    }

    fun decodeYouTubeCipher(cipher: String): String? {
        val params = linkedMapOf<String, String>()
        cipher.split("&").forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val key = pair.substring(0, eq)
                val value = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                params[key] = value
            }
        }
        val url = params["url"] ?: return null
        val sig = params["s"] ?: params["sig"] ?: params["signature"]
        return if (!sig.isNullOrBlank() && !url.contains("sig=") && !url.contains("signature=")) {
            val sigParam = if (params["sp"] != null) params["sp"] else "sig"
            "$url&$sigParam=$sig"
        } else {
            url
        }
    }

    fun isInteresting(url: String, mimeType: String?): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("blob:") || lower.startsWith("data:")) return false
        if (extensionFromUrl(url) in segmentExtensions) return false
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.endsWith("/*")) return false
        if (mime.contains("video/mp2t") || mime.contains("iso.segment")) return false
        if (lower.contains("&range=") || lower.contains("?range=")) return false
        val ext = extensionFromUrl(url)
        if (ext in filteredExtensions && !mime.startsWith("video/") && !mime.startsWith("audio/")) return false
        if (classify(url, mimeType) != FileCategory.OTHER) return true
        if (isVideoHost(url) && ext.isEmpty()) return true
        return mime.startsWith("video/") || mime.startsWith("audio/") ||
            mime.contains("mpegurl") || mime.contains("dash") ||
            mime.contains("text/vtt") || mime.contains("subrip")
    }

    fun isStream(url: String, mimeType: String?): Boolean {
        val ext = extensionFromUrl(url)
        val mime = mimeType?.lowercase().orEmpty()
        return ext in streamExtensions || mime.contains("mpegurl") || mime.contains("dash")
    }

    fun isDirectVideo(url: String, mimeType: String?): Boolean {
        val ext = extensionFromUrl(url)
        val mime = mimeType?.lowercase().orEmpty()
        val specificMime = mime.isNotBlank() && !mime.endsWith("/*")
        return ext in videoExtensions || (specificMime && mime.startsWith("video/")) || (ext.isEmpty() && isVideoHost(url))
    }

    fun isDownloadableVideo(url: String, mimeType: String?): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("blob:") || lower.startsWith("data:")) return false
        if (extensionFromUrl(url) in segmentExtensions) return false
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.endsWith("/*")) return false
        if (mime.startsWith("image/") || mime.contains("font") || mime.contains("text/css")) return false
        return isStream(url, mimeType) || isDirectVideo(url, mimeType)
    }

    fun classify(url: String, mimeType: String?): FileCategory {
        val ext = extensionFromUrl(url)
        val mime = mimeType?.lowercase().orEmpty()
        val specificMime = mime.isNotBlank() && !mime.endsWith("/*")
        return when {
            TorrentTools.extractValidMagnet(url) != null || ext in torrentExtensions || mime.contains("bittorrent") || mime.contains("x-torrent") -> FileCategory.TORRENT
            ext in streamExtensions || mime.contains("mpegurl") || mime.contains("dash") -> FileCategory.STREAM
            ext in segmentExtensions || mime.contains("mp2t") || mime.contains("iso.segment") -> FileCategory.STREAM
            ext in videoExtensions || (specificMime && mime.startsWith("video/")) -> FileCategory.VIDEO
            ext in subtitleExtensions || mime.contains("text/vtt") || mime.contains("subrip") -> FileCategory.SUBTITLE
            ext in audioExtensions || (specificMime && mime.startsWith("audio/")) -> FileCategory.AUDIO
            ext in imageExtensions || (specificMime && mime.startsWith("image/")) -> FileCategory.IMAGE
            ext in documentExtensions || mime == "application/pdf" -> FileCategory.DOCUMENT
            ext in archiveExtensions || mime.contains("zip") || mime.contains("archive") -> FileCategory.ARCHIVE
            ext.isEmpty() && isVideoHost(url) -> FileCategory.VIDEO
            else -> FileCategory.OTHER
        }
    }

    fun guessFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        fallbackTitle: String?,
        preferFallbackTitle: Boolean = false,
    ): String {
        val raw = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val rawBaseName = raw.substringBeforeLast('.', raw).lowercase(Locale.US)
        val useFallbackTitle = !fallbackTitle.isNullOrBlank() && (
            preferFallbackTitle || raw.isBlank() || raw == "downloadfile" ||
                rawBaseName in genericMediaBaseNames
            )
        val sanitized = sanitizeFileName(
            if (useFallbackTitle) requireNotNull(fallbackTitle)
            else if (raw.isBlank() || raw == "downloadfile") fallbackTitle ?: "download_${System.currentTimeMillis()}"
            else raw,
        )
        if (sanitized.contains('.')) return sanitized
        val mimeExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val urlExtension = extensionFromUrl(url)
        val pickedExtension = mimeExtension ?: urlExtension.takeIf { it.isNotBlank() }
        return if (pickedExtension != null) "$sanitized.$pickedExtension" else sanitized
    }

    fun ensureExtension(fileName: String, extension: String): String {
        val normalized = sanitizeFileName(fileName).ifBlank { "stream_${System.currentTimeMillis()}" }
        return if (normalized.lowercase().endsWith(".$extension")) normalized else "$normalized.$extension"
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "大小未知"
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1fKB", bytes / 1024f)
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1fMB", bytes / 1024f / 1024f)
        return String.format(Locale.US, "%.1fGB", bytes / 1024f / 1024f / 1024f)
    }

    fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private val UNSAFE_FILENAME_REGEX = Regex("[\\\\/:*?\"<>|]")

    fun sanitizeFileName(input: String): String {
        return input.replace(UNSAFE_FILENAME_REGEX, "_").trim().ifBlank {
            "file_${System.currentTimeMillis()}"
        }
    }

    private fun extensionFromUrl(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?')
        return clean.substringAfterLast('.', "").lowercase(Locale.US)
    }
}
