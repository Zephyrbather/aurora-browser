package com.fenglan.aurorabrowser.browser

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
    private val audioExtensions = setOf("mp3", "aac", "m4a", "wav", "ogg", "flac")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
    private val documentExtensions = setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz")
    private val genericMediaBaseNames = setOf(
        "index",
        "master",
        "playlist",
        "stream",
        "video",
        "media",
        "play",
        "download",
        "downloadfile",
        "file",
        "default",
    )

    fun isInteresting(url: String, mimeType: String?): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("blob:") || lower.startsWith("data:")) {
            return false
        }
        if (extensionFromUrl(url) in segmentExtensions) {
            return false
        }
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.contains("video/mp2t") || mime.contains("iso.segment")) {
            return false
        }
        if (classify(url, mimeType) != FileCategory.OTHER) {
            return true
        }
        return mime.let {
            it.startsWith("video/") || it.startsWith("audio/") || it.contains("mpegurl") || it.contains("dash")
        }
    }

    fun isStream(url: String, mimeType: String?): Boolean {
        val ext = extensionFromUrl(url)
        val mime = mimeType?.lowercase().orEmpty()
        return ext in streamExtensions || mime.contains("mpegurl") || mime.contains("dash")
    }

    fun isDirectVideo(url: String, mimeType: String?): Boolean {
        val ext = extensionFromUrl(url)
        val mime = mimeType?.lowercase().orEmpty()
        return ext in videoExtensions || mime.startsWith("video/")
    }

    fun isDownloadableVideo(url: String, mimeType: String?): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("blob:") || lower.startsWith("data:")) {
            return false
        }
        if (extensionFromUrl(url) in segmentExtensions) {
            return false
        }
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.startsWith("image/") || mime.contains("font") || mime.contains("text/css")) {
            return false
        }
        return isStream(url, mimeType) || isDirectVideo(url, mimeType)
    }

    fun classify(url: String, mimeType: String?): FileCategory {
        val ext = extensionFromUrl(url)
        val mime = mimeType?.lowercase().orEmpty()
        return when {
            ext in streamExtensions || mime.contains("mpegurl") || mime.contains("dash") -> FileCategory.STREAM
            ext in segmentExtensions || mime.contains("mp2t") || mime.contains("iso.segment") -> FileCategory.STREAM
            ext in videoExtensions || mime.startsWith("video/") -> FileCategory.VIDEO
            ext in audioExtensions || mime.startsWith("audio/") -> FileCategory.AUDIO
            ext in imageExtensions || mime.startsWith("image/") -> FileCategory.IMAGE
            ext in documentExtensions || mime == "application/pdf" -> FileCategory.DOCUMENT
            ext in archiveExtensions || mime.contains("zip") || mime.contains("archive") -> FileCategory.ARCHIVE
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
            preferFallbackTitle ||
                raw.isBlank() ||
                raw == "downloadfile" ||
                rawBaseName in genericMediaBaseNames
            )
        val sanitized = sanitizeFileName(
            if (useFallbackTitle) {
                requireNotNull(fallbackTitle)
            } else if (raw.isBlank() || raw == "downloadfile") {
                fallbackTitle ?: "download_${System.currentTimeMillis()}"
            } else {
                raw
            },
        )
        if (sanitized.contains('.')) {
            return sanitized
        }

        val mimeExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val urlExtension = extensionFromUrl(url)
        val pickedExtension = mimeExtension ?: urlExtension.takeIf { it.isNotBlank() }
        return if (pickedExtension != null) {
            "$sanitized.$pickedExtension"
        } else {
            sanitized
        }
    }

    fun ensureExtension(fileName: String, extension: String): String {
        val normalized = sanitizeFileName(fileName).ifBlank { "stream_${System.currentTimeMillis()}" }
        return if (normalized.lowercase().endsWith(".$extension")) {
            normalized
        } else {
            "$normalized.$extension"
        }
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) {
            return "大小未知"
        }
        if (bytes < 1024) {
            return "${bytes}B"
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1fKB", bytes / 1024f)
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1fMB", bytes / 1024f / 1024f)
        }
        return String.format(Locale.US, "%.1fGB", bytes / 1024f / 1024f / 1024f)
    }

    fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    fun sanitizeFileName(input: String): String {
        return input.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank {
            "file_${System.currentTimeMillis()}"
        }
    }

    private fun extensionFromUrl(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?')
        return clean.substringAfterLast('.', "").lowercase(Locale.US)
    }
}
