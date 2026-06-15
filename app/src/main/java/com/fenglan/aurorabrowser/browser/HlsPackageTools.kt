package com.fenglan.aurorabrowser.browser

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object HlsPackageTools {
    private const val TAG = "AuroraHLS"

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

    suspend fun downloadPackage(
        context: Context,
        recordId: Long,
        title: String,
        playlistUrl: String,
        headers: RequestHeaders,
        downloadSubdirectory: String = "Aurora",
        cachedPlaylistFile: File? = null,
        segmentConcurrency: Int = DEFAULT_SEGMENT_CONCURRENCY,
        isPrivate: Boolean = false,
        shouldContinue: (() -> Boolean)? = null,
        onProgress: ((playlistFile: File, downloadedBytes: Long, done: Int, total: Int) -> Unit)? = null,
    ): DownloadedPackage {
        Log.d(TAG, "downloadPackage url=$playlistUrl cachedPlaylistFile=${cachedPlaylistFile?.absolutePath} exists=${cachedPlaylistFile?.exists()} size=${cachedPlaylistFile?.length()}")
        val playlist = try {
            Log.d(TAG, "Fetching fresh playlist from server")
            resolvePlaylist(playlistUrl, headers)
        } catch (freshError: Throwable) {
            Log.w(TAG, "Fresh playlist fetch failed: ${freshError.message}, trying cache")
            if (cachedPlaylistFile != null && cachedPlaylistFile.exists() && cachedPlaylistFile.length() > 0) {
                Log.d(TAG, "Falling back to cached playlist")
                parsePlaylist(playlistUrl, cachedPlaylistFile.readText(), headers)
            } else {
                throw freshError
            }
        }
        val baseDir = if (isPrivate) {
            BrowserDownloads.getPrivateDir(context, downloadSubdirectory)
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        }
        val rootDir = File(
            baseDir,
            "${if (isPrivate) "" else "$downloadSubdirectory/"}hls/${ResourceTools.sanitizeFileName(title).substringBeforeLast('.')}_${recordId}",
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
                downloadFile(init.url, headers, target)
            }
            totalBytes += target.length()
        }

        if (playlist.segments.isNotEmpty()) {
            downloadSegmentsParallel(
                playlist = playlist,
                rootDir = rootDir,
                playlistFile = playlistFile,
                headers = headers,
                concurrency = segmentConcurrency,
                shouldContinue = shouldContinue,
                onProgress = { done, bytes ->
                    totalBytes = bytes
                    onProgress?.invoke(playlistFile, totalBytes, done, playlist.segments.size)
                },
            )
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
        val totalSourceBytes = packageSourceBytes(localPackage).coerceAtLeast(1L)
        var outputUri = createOutputUri(context, outputName, "video/mp4", downloadSubdirectory)
        try {
            try {
                onProgress?.invoke("快速封装 MP4", 0L, totalSourceBytes, 1)
                remuxLocalPackageToMp4(context, localPackage, totalSourceBytes, outputUri) { processed, total ->
                    val progress = ((processed * 98f) / total.coerceAtLeast(1L)).toInt() + 1
                    onProgress?.invoke("快速封装 MP4", processed, total, progress.coerceIn(1, 99))
                }
            } catch (_: Throwable) {
                deleteOutputUri(context, outputUri)
                outputUri = createOutputUri(context, outputName, "video/mp4", downloadSubdirectory)
                val sourceExtension = if (localPackage.isFragmentedMp4) "mp4" else "ts"
                val sourceFile = File(context.cacheDir, "aurora_hls_export_${System.currentTimeMillis()}.$sourceExtension")
                try {
                    onProgress?.invoke("兼容合并分片", 0L, totalSourceBytes, 0)
                    writeLocalPackageSource(localPackage, sourceFile) { processed, total ->
                        val progress = ((processed * 35f) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 35)
                        onProgress?.invoke("兼容合并分片", processed, total, progress)
                    }
                    onProgress?.invoke("兼容封装 MP4", 0L, totalSourceBytes, 36)
                    remuxToMp4(context, sourceFile, outputUri) { processed, total ->
                        val progress = 35 + ((processed * 64f) / total.coerceAtLeast(1L)).toInt()
                        onProgress?.invoke("兼容封装 MP4", processed, total, progress.coerceIn(36, 99))
                    }
                } finally {
                    sourceFile.delete()
                }
            }
            finalizePending(context, outputUri)
            val size = context.contentResolver.openFileDescriptor(outputUri, "r")?.use { it.statSize }
            onProgress?.invoke("转码完成", totalSourceBytes, totalSourceBytes, 100)
            return ConvertedFile(uri = outputUri, sizeBytes = size)
        } catch (error: Throwable) {
            deleteOutputUri(context, outputUri)
            throw error
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
        return parsePlaylist(url, text, headers)
    }

    private fun parsePlaylist(baseUrl: String, text: String, headers: RequestHeaders): MediaPlaylist {
        if (text.contains("#EXT-X-STREAM-INF")) {
            val bestVariantUrl = chooseBestVariant(baseUrl, text)
            return resolvePlaylist(bestVariantUrl, headers)
        }
        return parseMediaPlaylist(baseUrl, text)
    }

    private fun parseMediaPlaylist(baseUrl: String, text: String): MediaPlaylist {
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
                        "AES-128" -> EncryptionInfo(
                            keyUrl = resolveUrl(baseUrl, attrs["URI"].orEmpty()),
                            iv = attrs["IV"]?.removePrefix("0x")?.let(::hexToBytes),
                        )
                        else -> null
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    currentDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val initUrl = attrs["URI"]?.let { resolveUrl(baseUrl, it) }
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
                    val resolvedUrl = resolveUrl(baseUrl, line)
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

        lines.forEach { rawLine ->
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
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            remuxExtractorToMp4(context, extractor, sourceFile.length(), outputUri, onProgress)
        } finally {
            extractor.release()
        }
    }

    private fun remuxLocalPackageToMp4(
        context: Context,
        playlist: MediaPlaylist,
        totalBytes: Long,
        outputUri: Uri,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val dataSource = PackageMediaDataSource(packageSourceFiles(playlist))
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(dataSource)
            remuxExtractorToMp4(context, extractor, totalBytes, outputUri, onProgress)
        } finally {
            extractor.release()
            dataSource.close()
        }
    }

    private fun remuxExtractorToMp4(
        context: Context,
        extractor: MediaExtractor,
        sourceBytes: Long,
        outputUri: Uri,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val totalBytes = sourceBytes.coerceAtLeast(1L)
        var processedBytes = 0L
        var lastProgressBytes = 0L
        val progressStepBytes = (totalBytes / 160L).coerceAtLeast(512L * 1024L)
        try {
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

                val buffer = ByteBuffer.allocateDirect(maxBufferSize)
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
                    info.flags = mediaCodecFlagsFor(extractor.sampleFlags)
                    muxer!!.writeSampleData(outputTrackIndex, buffer, info)
                    processedBytes += sampleSize.toLong()
                    val reportedBytes = processedBytes.coerceAtMost(totalBytes)
                    if (reportedBytes - lastProgressBytes >= progressStepBytes || reportedBytes >= totalBytes) {
                        lastProgressBytes = reportedBytes
                        onProgress?.invoke(reportedBytes, totalBytes)
                    }
                    extractor.advance()
                }
                onProgress?.invoke(totalBytes, totalBytes)
            } ?: throw IOException("无法创建 MP4 封装器")
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            muxer?.release()
        }
    }

    private fun packageSourceFiles(playlist: MediaPlaylist): List<File> {
        return buildList {
            playlist.initializationSegment?.let { add(File(it.url)) }
            playlist.segments.forEach { add(File(it.url)) }
        }.filter { it.length() > 0L }
    }

    private fun mediaCodecFlagsFor(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun packageSourceBytes(playlist: MediaPlaylist): Long {
        val initBytes = playlist.initializationSegment?.let { File(it.url).length() } ?: 0L
        return initBytes + playlist.segments.sumOf { File(it.url).length() }
    }

    private data class PackageSourceEntry(
        val file: File,
        val start: Long,
        val length: Long,
    )

    private class PackageMediaDataSource(files: List<File>) : MediaDataSource() {
        private val entries: List<PackageSourceEntry>
        private val totalSize: Long
        private var openEntry: PackageSourceEntry? = null
        private var openFile: RandomAccessFile? = null

        init {
            var offset = 0L
            entries = files.map { file ->
                PackageSourceEntry(file = file, start = offset, length = file.length()).also {
                    offset += it.length
                }
            }
            totalSize = offset
            if (entries.isEmpty() || totalSize <= 0L) {
                throw IOException("本地流媒体包为空")
            }
        }

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L || position >= totalSize || size <= 0) {
                return -1
            }
            var copied = 0
            var cursor = position
            val targetSize = minOf(size.toLong(), totalSize - position).toInt()
            while (copied < targetSize && cursor < totalSize) {
                val entry = findEntry(cursor) ?: break
                val entryOffset = cursor - entry.start
                val bytesToRead = minOf((targetSize - copied).toLong(), entry.length - entryOffset).toInt()
                val file = fileForEntry(entry)
                file.seek(entryOffset)
                val read = file.read(buffer, offset + copied, bytesToRead)
                if (read <= 0) {
                    break
                }
                copied += read
                cursor += read
            }
            return if (copied > 0) copied else -1
        }

        override fun getSize(): Long = totalSize

        @Synchronized
        override fun close() {
            openFile?.close()
            openFile = null
            openEntry = null
        }

        private fun findEntry(position: Long): PackageSourceEntry? {
            var low = 0
            var high = entries.lastIndex
            while (low <= high) {
                val middle = (low + high).ushr(1)
                val entry = entries[middle]
                when {
                    position < entry.start -> high = middle - 1
                    position >= entry.start + entry.length -> low = middle + 1
                    else -> return entry
                }
            }
            return null
        }

        private fun fileForEntry(entry: PackageSourceEntry): RandomAccessFile {
            if (openEntry != entry) {
                openFile?.close()
                openFile = RandomAccessFile(entry.file, "r")
                openEntry = entry
            }
            return openFile ?: RandomAccessFile(entry.file, "r").also {
                openFile = it
                openEntry = entry
            }
        }
    }

    private fun createOutputUri(context: Context, fileName: String, mimeType: String, downloadSubdirectory: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$downloadSubdirectory")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法写入下载目录")
    }

    private fun finalizePending(context: Context, uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null)
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
                if (it < 2) {
                    Thread.sleep(1500L * (it + 1))
                }
            }
        }
        throw IOException(lastError?.message ?: "流媒体下载失败", lastError)
    }

    private fun downloadBytesOnce(url: String, headers: RequestHeaders): ByteArray {
        val connection = openReadableConnection(url, headers)
        try {
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(url: String, headers: RequestHeaders, target: File): Long {
        var lastError: Throwable? = null
        repeat(3) {
            try {
                return downloadFileOnce(url, headers, target)
            } catch (error: Throwable) {
                lastError = error
                if (it < 2) {
                    Thread.sleep(1500L * (it + 1))
                }
            }
        }
        throw IOException(lastError?.message ?: "流媒体下载失败", lastError)
    }

    private fun downloadFileOnce(url: String, headers: RequestHeaders, target: File): Long {
        val connection = openReadableConnection(url, headers)
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                tmp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            replaceFile(tmp, target)
            return target.length()
        } finally {
            connection.disconnect()
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    private fun openReadableConnection(url: String, headers: RequestHeaders): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        val userAgent = headers.userAgent?.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        connection.setRequestProperty("User-Agent", userAgent)
        headers.referer?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
        val cookie = headers.cookie?.takeIf { it.isNotBlank() }
            ?: CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        if (!cookie.isNullOrBlank()) {
            connection.setRequestProperty("Cookie", cookie)
        }
        headers.origin?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Origin", it) }
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        connection.setRequestProperty("Connection", "keep-alive")
        connection.setRequestProperty("Sec-Fetch-Dest", "empty")
        connection.setRequestProperty("Sec-Fetch-Mode", "cors")
        connection.setRequestProperty("Sec-Fetch-Site", "cross-site")
        connection.connect()

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            Log.e(TAG, "HTTP $code for $url")
            throw IOException("下载失败: $code")
        }
        Log.d(TAG, "HTTP $code OK, size=${connection.contentLength} for ${url.take(100)}")
        return connection
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            tmp.outputStream().use { it.write(bytes) }
            replaceFile(tmp, target)
        } finally {
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    private fun replaceFile(source: File, target: File) {
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private suspend fun downloadSegmentsParallel(
        playlist: MediaPlaylist,
        rootDir: File,
        playlistFile: File,
        headers: RequestHeaders,
        concurrency: Int,
        shouldContinue: (() -> Boolean)?,
        onProgress: (done: Int, totalBytes: Long) -> Unit,
    ) = coroutineScope {
        val segmentConcurrency = concurrency.coerceIn(1, 8)
        val semaphore = Semaphore(segmentConcurrency)
        var doneCount = 0
        var totalBytes = 0L
        val segments = playlist.segments

        segments.chunked(segmentConcurrency).forEach { batch ->
            if (shouldContinue?.invoke() == false) {
                throw IOException("任务已停止")
            }
            val jobs = batch.map { segment ->
                async {
                    semaphore.withPermit {
                        val target = File(rootDir, segment.localName)
                        if (!target.exists() || target.length() <= 0L) {
                            val bytes = segment.encryption?.let { encryption ->
                                val encrypted = downloadBytes(segment.url, headers)
                                val keyBytes = downloadBytes(encryption.keyUrl, headers)
                                decryptSegment(encrypted, keyBytes, encryption.iv ?: ivForSequence(segment.sequence))
                            }
                            if (bytes != null) {
                                writeBytesAtomically(target, bytes)
                            } else {
                                downloadFile(segment.url, headers, target)
                            }
                        }
                        target.length()
                    }
                }
            }
            val batchSizes = jobs.awaitAll()
            batchSizes.forEach { size ->
                totalBytes += size
                doneCount += 1
            }
            writeLocalPlaylist(
                playlistFile = playlistFile,
                playlist = playlist.copy(segments = segments.take(doneCount)),
                appendEndList = false,
            )
            onProgress(doneCount, totalBytes)
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
    private const val DEFAULT_SEGMENT_CONCURRENCY = 4
}
