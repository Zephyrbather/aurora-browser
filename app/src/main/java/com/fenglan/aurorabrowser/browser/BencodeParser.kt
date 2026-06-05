package com.fenglan.aurorabrowser.browser

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset

data class TorrentMeta(
    val infoHash: String,
    val name: String,
    val files: List<TorrentFileEntry>,
    val totalSize: Long,
    val announceList: List<String>,
)

data class TorrentFileEntry(
    val path: String,
    val size: Long,
)

object BencodeParser {
    fun parseTorrentMeta(bytes: ByteArray, magnetInfoHash: String? = null): TorrentMeta? {
        return runCatching {
            val dict = parseDict(ByteArrayInputStream(bytes))
            val info = dict["info"] as? Map<String, Any> ?: return null
            val infoHash = magnetInfoHash
                ?: TorrentTools.computeBtih(info)
                ?: return null
            val name = info["name"]?.toString().orEmpty()
            val announceList = buildList {
                (dict["announce-list"] as? List<List<String>>)?.flatten()?.let { addAll(it) }
                if (isEmpty()) {
                    (dict["announce"] as? String)?.let { add(it) }
                }
            }
            val (files, totalSize) = parseFileEntries(info, name)
            TorrentMeta(
                infoHash = infoHash,
                name = name,
                files = files,
                totalSize = totalSize,
                announceList = announceList,
            )
        }.getOrNull()
    }

    private fun parseFileEntries(info: Map<String, Any>, defaultName: String): Pair<List<TorrentFileEntry>, Long> {
        val filesList = info["files"] as? List<Map<String, Any>>
        if (filesList != null) {
            var total = 0L
            val entries = filesList.mapNotNull { file ->
                val pathParts = (file["path"] as? List<String>) ?: return@mapNotNull null
                val size = (file["length"] as? Number)?.toLong() ?: return@mapNotNull null
                total += size
                TorrentFileEntry(path = pathParts.joinToString("/"), size = size)
            }
            return entries to total
        }
        val length = (info["length"] as? Number)?.toLong() ?: 0L
        return listOf(TorrentFileEntry(defaultName, length)) to length
    }

    private fun parseDict(input: InputStream): Map<String, Any> {
        check(input.read().toChar() == 'd')
        val map = mutableMapOf<String, Any>()
        while (true) {
            val peek = input.read()
            if (peek == -1 || peek.toChar() == 'e') break
            val key = parseString(input, peek)
            map[key] = parseValue(input)
        }
        return map
    }

    private fun parseValue(input: InputStream): Any {
        val first = input.read()
        return when (first.toChar()) {
            'd' -> {
                val map = mutableMapOf<String, Any>()
                while (true) {
                    val peek = input.read()
                    if (peek == -1 || peek.toChar() == 'e') break
                    map[parseString(input, peek)] = parseValue(input)
                }
                map
            }
            'l' -> {
                val list = mutableListOf<Any>()
                while (true) {
                    val peek = input.read()
                    if (peek == -1 || peek.toChar() == 'e') break
                    list.add(parseValueWithPeek(input, peek))
                }
                list
            }
            'i' -> {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read().toChar()
                    if (c == 'e') break
                    sb.append(c)
                }
                sb.toString().toLongOrNull() ?: sb.toString()
            }
            else -> parseString(input, first)
        }
    }

    private fun parseValueWithPeek(input: InputStream, first: Int): Any {
        return when (first.toChar()) {
            'd' -> {
                val map = mutableMapOf<String, Any>()
                while (true) {
                    val peek = input.read()
                    if (peek == -1 || peek.toChar() == 'e') break
                    map[parseString(input, peek)] = parseValue(input)
                }
                map
            }
            'l' -> {
                val list = mutableListOf<Any>()
                while (true) {
                    val peek = input.read()
                    if (peek == -1 || peek.toChar() == 'e') break
                    list.add(parseValueWithPeek(input, peek))
                }
                list
            }
            'i' -> {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read().toChar()
                    if (c == 'e') break
                    sb.append(c)
                }
                sb.toString().toLongOrNull() ?: sb.toString()
            }
            else -> parseString(input, first)
        }
    }

    private fun parseString(input: InputStream, firstByte: Int): String {
        val lenStr = StringBuilder().append(firstByte.toChar())
        while (true) {
            val b = input.read()
            if (b == -1) break
            val c = b.toChar()
            if (c == ':') break
            lenStr.append(c)
        }
        val length = lenStr.toString().toIntOrNull() ?: 0
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return bytes.toString(Charset.forName("UTF-8"))
    }
}
