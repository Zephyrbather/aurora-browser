package com.fenglan.aurorabrowser.browser

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

object HlsPackageTools {
    data class RequestHeaders(
        val referer: String?,
        val userAgent: String?,
        val cookie: String?,
        val origin: String?,
    )

    data class DownloadedPackage(
        val rootDir: File,
        val playlistFile: File,
        val totalBytes: Long,
        val segmentCount: Int,
    )

    data class ConvertedFile(
        val uri: Uri,
        val sizeBytes: Long?,
    )

    data class MediaPlaylist(
        val initializationSegment: InitializationSegment?,
        val segments: List<MediaSegment>,
        val isFragmentedMp4: Boolean,
        val targetDuration: Int,
    )

    data class InitializationSegment(
        val url: String,
        val localName: String,
    )

    data class MediaSegment(
        val url: String,
        val sequence: Long,
        val encryption: EncryptionInfo?,
        val durationSeconds: Double,
        val localName: String,
    )

    data class EncryptionInfo(
        val keyUrl: String,
        val iv: ByteArray?,
    )

    fun downloadPackage(
        context: Context,
        recordId: Long,
        title: String,
        playlistUrl: String,
        headers: RequestHeaders,
        downloadSubdirectory: String = "Aurora",
        shouldContinue: (() -> Boolean)? = null,
        onProgress: ((playlistFile: File, downloadedBytes: Long, done: Int, total: Int) -> Unit)? = null,
    ): DownloadedPackage {
        val playlist = resolvePlaylist(playlistUrl, headers)
        val rootDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            "$downloadSubdirectory/hls/${ResourceTools.sanitizeFileName(title).substringBeforeLast('.')}_${recordId}",
        ).apply {
            mkdirs()
        }
        val playlistFile = File(rootDir, PLAYLIST_FILE_NAME)

        var totalBytes = 0L
        playlist.initializationSegment?.let { init ->
            if (shouldContinue?.invoke() == false) {
                throw IOException("任务已停止")
            }
            val target = File(rootDir, init.localName)
            if (!target.exists() || target.length() <= 0L) {
                val bytes = downloadBytes(init.url, headers)
                target.outputStream().use { it.write(bytes) }
            }
            totalBytes += target.length()
        }

        playlist.segments.forEachIndexed { index, segment ->
            if (shouldContinue?.invoke() == false) {
                throw IOException("任务已停止")
            }
            val target = File(rootDir, segment.localName)
            if (!target.exists() || target.length() <= 0L) {
                val encrypted = downloadBytes(segment.url, headers)
                val bytes = segment.encryption?.let { encryption ->
                    val keyBytes = downloadBytes(encryption.keyUrl, headers)
                    decryptSegment(encrypted, keyBytes, encryption.iv ?: ivForSequence(segment.sequence))
                } ?: encrypted
                target.outputStream().use { it.write(bytes) }
            }
            totalBytes += target.length()
            writeLocalPlaylist(
                playlistFile = playlistFile,
                playlist = playlist.copy(segments = playlist.segments.take(index + 1)),
                appendEndList = false,
            )
            onProgress?.invoke(playlistFile, totalBytes, index + 1, playlist.segments.size)
        }

        writeLocalPlaylist(
            playlistFile = playlistFile,
            playlist = playlist,
            appendEndList = true,
        )
        totalBytes += playlistFile.length()

        return DownloadedPackage(
            rootDir = rootDir,
            playlistFile = playlistFile,
            totalBytes = totalBytes,
            segmentCount = playlist.segments.size,
        )
    }

    fun convertPackageToMp4(
        context: Context,
        playlistFile: File,
        outputName: String,
        downloadSubdirectory: String = "Aurora",
        onProgress: ((stage: String, processedBytes: Long, totalBytes: Long, progressPercent: Int) -> Unit)? = null,
    ): ConvertedFile {
        val localPackage = parseLocalPlaylist(playlistFile)
        val sourceExtension = if (localPackage.isFragmentedMp4) "mp4" else "ts"
        val sourceFile = File(context.cacheDir, "aurora_hls_export_${System.currentTimeMillis()}.$sourceExtension")
        val totalSourceBytes = packageSourceBytes(localPackage).coerceAtLeast(1L)
        try {
            onProgress?.invoke("合并分片", 0L, totalSourceBytes, 0)
            writeLocalPackageSource(localPackage, sourceFile) { processed, total ->
                val progress = ((processed * 45f) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 45)
                onProgress?.invoke("合并分片", processed, total, progress)
            }
            val outputUri = createOutputUri(context, outputName, "video/mp4", downloadSubdirectory)
            try {
                onProgress?.invoke("封装 MP4", 0L, totalSourceBytes, 46)
                remuxToMp4(context, sourceFile, outputUri) { processed, total ->
                    val progress = 45 + ((processed * 55f) / total.coerceAtLeast(1L)).toInt()
                    onProgress?.invoke("封装 MP4", processed, total, progress.coerceIn(45, 99))
                }
                finalizePending(context, outputUri)
            } catch (error: Throwable) {
                deleteOutputUri(context, outputUri)
                throw error
            }
            val size = context.contentResolver.openFileDescriptor(outputUri, "r")?.use { it.statSize }
            onProgress?.invoke("转码完成", totalSourceBytes, totalSourceBytes, 100)
            return ConvertedFile(uri = outputUri, sizeBytes = size)
        } finally {
            sourceFile.delete()
        }
    }

    fun deletePackageForUri(uriString: String?): Boolean {
        val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return false
        if (uri.scheme?.lowercase() != "file") {
            return false
        }
        val playlistFile = uri.path?.let(::File) ?: return false
        val rootDir = if (playlistFile.isDirectory) playlistFile else playlistFile.parentFile
        return rootDir?.deleteRecursively() == true
    }

    private fun resolvePlaylist(url: String, headers: RequestHeaders): MediaPlaylist {
        val text = downloadText(url, headers)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val bestVariantUrl = chooseBestVariant(url, text)
            return resolvePlaylist(bestVariantUrl, headers)
        }

        var currentSequence = 0L
        var currentEncryption: EncryptionInfo? = null
        var currentDuration = 0.0
        var initializationSegment: InitializationSegment? = null
        val segments = mutableListOf<MediaSegment>()

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    currentSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    currentEncryption = when (attrs["METHOD"]?.uppercase(Locale.US)) {
                        "AES-128" -> {
                            EncryptionInfo(
                                keyUrl = resolveUrl(url, attrs["URI"].orEmpty()),
                                iv = attrs["IV"]?.removePrefix("0x")?.let(::hexToBytes),
                            )
                        }

                        else -> null
                    }
                }

                line.startsWith("#EXTINF:") -> {
                    currentDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val initUrl = attrs["URI"]?.let { resolveUrl(url, it) }
                    if (!initUrl.isNullOrBlank()) {
                        val ext = extensionForUrl(initUrl).ifBlank { "mp4" }
                        initializationSegment = InitializationSegment(
                            url = initUrl,
                            localName = "init.$ext",
                        )
                    }
                }

                line.isBlank() || line.startsWith("#") -> Unit

                else -> {
                    val resolvedUrl = resolveUrl(url, line)
                    val extension = extensionForUrl(resolvedUrl).ifBlank { "ts" }
                    segments += MediaSegment(
                        url = resolvedUrl,
                        sequence = currentSequence,
                        encryption = currentEncryption,
                        durationSeconds = currentDuration.takeIf { it > 0.0 } ?: 6.0,
                        localName = "seg_${index.toString().padStart(5, '0')}.$extension",
                    )
                    currentSequence += 1
                    currentDuration = 0.0
                }
            }
        }

        if (segments.isEmpty()) {
            throw IOException("未解析到流媒体分片")
        }

        val isFragmentedMp4 = initializationSegment != null || segments.first().localName.endsWith(".m4s")
        val targetDuration = ceil(segments.maxOf { it.durationSeconds }).toInt().coerceAtLeast(1)
        return MediaPlaylist(
            initializationSegment = initializationSegment,
            segments = segments,
            isFragmentedMp4 = isFragmentedMp4,
            targetDuration = targetDuration,
        )
    }

    private fun writeLocalPlaylist(playlistFile: File, playlist: MediaPlaylist, appendEndList: Boolean) {
        playlistFile.writeText(buildLocalPlaylist(playlist, appendEndList))
    }

    private fun buildLocalPlaylist(playlist: MediaPlaylist, appendEndList: Boolean): String {
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-PLAYLIST-TYPE:${if (appendEndList) "VOD" else "EVENT"}")
            appendLine("#EXT-X-TARGETDURATION:${playlist.targetDuration}")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            playlist.initializationSegment?.let {
                appendLine("#EXT-X-MAP:URI=\"${it.localName}\"")
            }
            playlist.segments.forEach { segment ->
                appendLine("#EXTINF:${"%.3f".format(Locale.US, segment.durationSeconds)},")
                appendLine(segment.localName)
            }
            if (appendEndList) {
                appendLine("#EXT-X-ENDLIST")
            }
        }
    }

    private fun parseLocalPlaylist(playlistFile: File): MediaPlaylist {
        val lines = playlistFile.readLines()
        var initializationSegment: InitializationSegment? = null
        var currentDuration = 0.0
        val segments = mutableListOf<MediaSegment>()

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val localName = attrs["URI"]?.removeSurrounding("\"").orEmpty()
                    if (localName.isNotBlank()) {
                        initializationSegment = InitializationSegment(
                            url = File(playlistFile.parentFile, localName).absolutePath,
                            localName = localName,
                        )
                    }
                }

                line.startsWith("#EXTINF:") -> {
                    currentDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }

                line.isBlank() || line.startsWith("#") -> Unit

                else -> {
                    val localFile = File(playlistFile.parentFile, line)
                    segments += MediaSegment(
                        url = localFile.absolutePath,
                        sequence = segments.size.toLong(),
                        encryption = null,
                        durationSeconds = currentDuration.takeIf { it > 0.0 } ?: 6.0,
                        localName = line,
                    )
                    currentDuration = 0.0
                }
            }
        }

        if (segments.isEmpty()) {
            throw IOException("本地流媒体包为空")
        }

        return MediaPlaylist(
            initializationSegment = initializationSegment,
            segments = segments,
            isFragmentedMp4 = initializationSegment != null || segments.first().localName.endsWith(".m4s"),
            targetDuration = ceil(segments.maxOf { it.durationSeconds }).toInt().coerceAtLeast(1),
        )
    }

    private fun writeLocalPackageSource(
        playlist: MediaPlaylist,
        sourceFile: File,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val totalBytes = packageSourceBytes(playlist).coerceAtLeast(1L)
        var processedBytes = 0L
        sourceFile.outputStream().buffered().use { outputStream ->
            playlist.initializationSegment?.let { init ->
                val initFile = File(init.url)
                initFile.inputStream().use { it.copyTo(outputStream) }
                processedBytes += initFile.length()
                onProgress?.invoke(processedBytes, totalBytes)
            }
            playlist.segments.forEach { segment ->
                val segmentFile = File(segment.url)
                segmentFile.inputStream().use { it.copyTo(outputStream) }
                processedBytes += segmentFile.length()
                onProgress?.invoke(processedBytes, totalBytes)
            }
            outputStream.flush()
        }
    }

    private fun remuxToMp4(
        context: Context,
        sourceFile: File,
        outputUri: Uri,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val totalBytes = sourceFile.length().coerceAtLeast(1L)
        var processedBytes = 0L
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            context.contentResolver.openFileDescriptor(outputUri, "rw")?.use { descriptor ->
                muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val selectedTracks = linkedMapOf<Int, Int>()
                var maxBufferSize = 1024 * 1024

                for (trackIndex in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(trackIndex)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        extractor.selectTrack(trackIndex)
                        selectedTracks[trackIndex] = muxer!!.addTrack(format)
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            maxBufferSize = maxOf(maxBufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                    }
                }

                if (selectedTracks.isEmpty()) {
                    throw IOException("未解析到可封装的音视频轨道")
                }

                muxer!!.start()
                muxerStarted = true

                val buffer = ByteBuffer.allocate(maxBufferSize)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val inputTrackIndex = extractor.sampleTrackIndex
                    if (inputTrackIndex < 0) {
                        break
                    }
                    val outputTrackIndex = selectedTracks[inputTrackIndex]
                    if (outputTrackIndex == null) {
                        extractor.advance()
                        continue
                    }
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        break
                    }
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer!!.writeSampleData(outputTrackIndex, buffer, info)
                    processedBytes += sampleSize.toLong()
                    onProgress?.invoke(processedBytes.coerceAtMost(totalBytes), totalBytes)
                    extractor.advance()
                }
            } ?: throw IOException("无法创建 MP4 封装器")
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            muxer?.release()
            extractor.release()
        }
    }

    private fun packageSourceBytes(playlist: MediaPlaylist): Long {
        val initBytes = playlist.initializationSegment?.let { File(it.url).length() } ?: 0L
        return initBytes + playlist.segments.sumOf { File(it.url).length() }
    }

    private fun createOutputUri(context: Context, fileName: String, mimeType: String, downloadSubdirectory: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$downloadSubdirectory")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法写入下载目录")
    }

    private fun finalizePending(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    private fun deleteOutputUri(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun chooseBestVariant(masterUrl: String, text: String): String {
        val lines = text.lines()
        var bestBandwidth = -1
        var bestUrl: String? = null
        for (index in lines.indices) {
            val line = lines[index].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                continue
            }
            val attrs = parseAttributes(line.substringAfter(':'))
            val bandwidth = attrs["BANDWIDTH"]?.toIntOrNull() ?: -1
            val nextLine = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (nextLine.isNotBlank() && !nextLine.startsWith("#") && bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth
                bestUrl = resolveUrl(masterUrl, nextLine)
            }
        }
        return bestUrl ?: throw IOException("无法解析主播放列表")
    }

    private fun downloadText(url: String, headers: RequestHeaders): String {
        return String(downloadBytes(url, headers))
    }

    private fun downloadBytes(url: String, headers: RequestHeaders): ByteArray {
        var lastError: Throwable? = null
        repeat(3) {
            try {
                return downloadBytesOnce(url, headers)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IOException(lastError?.message ?: "流媒体下载失败", lastError)
    }

    private fun downloadBytesOnce(url: String, headers: RequestHeaders): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            headers.referer?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
            headers.userAgent?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("User-Agent", it) }
            headers.cookie?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
            headers.origin?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Origin", it) }
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            connection.setRequestProperty("Connection", "keep-alive")
            connection.connect()

            if (connection.responseCode !in 200..299) {
                throw IOException("下载失败: ${connection.responseCode}")
            }

            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        return URL(URL(base), relative).toString()
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val regex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        regex.findAll(raw).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim().removeSurrounding("\"")
            result[key] = value
        }
        return result
    }

    private fun decryptSegment(data: ByteArray, keyBytes: ByteArray, iv: ByteArray): ByteArray {
        val key = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding")
        }.getOrElse {
            Cipher.getInstance("AES/CBC/NoPadding")
        }
        return runCatching {
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)
            cipher.doFinal(data)
        }.getOrElse {
            val fallback = Cipher.getInstance("AES/CBC/NoPadding")
            fallback.init(Cipher.DECRYPT_MODE, key, ivSpec)
            fallback.doFinal(data)
        }
    }

    private fun ivForSequence(sequence: Long): ByteArray {
        return ByteArray(16).apply {
            for (index in 0 until 8) {
                this[15 - index] = ((sequence shr (index * 8)) and 0xff).toByte()
            }
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        val clean = value.trim().lowercase(Locale.US)
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun extensionForUrl(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?')
        return clean.substringAfterLast('.', "").lowercase(Locale.US)
    }

    private const val PLAYLIST_FILE_NAME = "index.m3u8"
}
