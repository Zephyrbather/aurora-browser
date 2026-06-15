package com.fenglan.aurorabrowser.browser

import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class LocalPlaylistServer(
    private val rootDir: File,
) {
    private val serverSocket = ServerSocket(0)
    private val executor = Executors.newFixedThreadPool(4)
    @Volatile private var running = true

    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}/"

    init {
        thread(name = "AuroraHlsServer", isDaemon = true) {
            while (running) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                executor.execute { handle(socket) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val requestText = readRequest(input)
            val requestLine = requestText.lineSequence().firstOrNull().orEmpty()
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            val headers = requestText.lineSequence()
                .drop(1)
                .takeWhile { it.isNotBlank() }
                .mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) {
                        null
                    } else {
                        line.substring(0, index).trim().lowercase(Locale.US) to line.substring(index + 1).trim()
                    }
                }
                .toMap()

            val targetFile = resolveTargetFile(path)
            if (targetFile == null || !targetFile.exists() || !targetFile.isFile) {
                writeHeaders(client.getOutputStream(), 404, "text/plain", 0, null)
                return
            }

            val totalLength = targetFile.length()
            val rangeHeader = headers["range"]
            val range = parseRange(rangeHeader, totalLength)
            val output = client.getOutputStream()
            if (range != null) {
                val (start, end) = range
                val contentLength = end - start + 1
                writeHeaders(
                    output = output,
                    code = 206,
                    mimeType = mimeTypeFor(targetFile),
                    contentLength = contentLength,
                    contentRange = "bytes $start-$end/$totalLength",
                )
                targetFile.inputStream().use { stream ->
                    stream.skip(start)
                    stream.copyRangeTo(output, contentLength)
                }
            } else {
                writeHeaders(output, 200, mimeTypeFor(targetFile), totalLength, null)
                targetFile.inputStream().use { it.copyTo(output) }
            }
            output.flush()
        }
    }

    private fun readRequest(input: BufferedInputStream): String {
        val buffer = ByteArray(8192)
        val builder = StringBuilder()
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            builder.append(String(buffer, 0, read, StandardCharsets.UTF_8))
            if (builder.contains("\r\n\r\n") || builder.contains("\n\n")) {
                break
            }
        }
        return builder.toString()
    }

    private fun resolveTargetFile(rawPath: String): File? {
        val cleanPath = URLDecoder.decode(rawPath.substringBefore('?').removePrefix("/"), "UTF-8")
            .ifBlank { "index.m3u8" }
        val target = File(rootDir, cleanPath).canonicalFile
        val canonicalRoot = rootDir.canonicalFile
        return target.takeIf { it.path.startsWith(canonicalRoot.path) }
    }

    private fun parseRange(value: String?, totalLength: Long): Pair<Long, Long>? {
        val raw = value?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=") ?: return null
        val start = raw.substringBefore('-').trim().toLongOrNull() ?: 0L
        val end = raw.substringAfter('-', "").trim().toLongOrNull() ?: (totalLength - 1)
        if (start < 0 || end < start || end >= totalLength) {
            return null
        }
        return start to end
    }

    private fun mimeTypeFor(file: File): String {
        return when (file.extension.lowercase(Locale.US)) {
            "m3u8" -> "application/x-mpegURL"
            "ts" -> "video/mp2t"
            "m4s" -> "video/iso.segment"
            "mp4" -> "video/mp4"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
    }

    private fun writeHeaders(
        output: OutputStream,
        code: Int,
        mimeType: String,
        contentLength: Long,
        contentRange: String?,
    ) {
        val status = when (code) {
            200 -> "HTTP/1.1 200 OK"
            206 -> "HTTP/1.1 206 Partial Content"
            else -> "HTTP/1.1 $code Not Found"
        }
        val headers = buildString {
            appendLine(status)
            appendLine("Content-Type: $mimeType")
            appendLine("Content-Length: $contentLength")
            appendLine("Accept-Ranges: bytes")
            if (contentRange != null) {
                appendLine("Content-Range: $contentRange")
            }
            appendLine("Connection: close")
            appendLine()
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
    }

    private fun java.io.InputStream.copyRangeTo(output: OutputStream, remainingBytes: Long) {
        var remaining = remainingBytes
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) {
                break
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}
