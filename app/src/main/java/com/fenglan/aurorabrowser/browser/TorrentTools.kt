package com.fenglan.aurorabrowser.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

object TorrentTools {
    private val magnetCandidateRegex = Regex("""magnet:\?xt=urn:[^"'<>\s]+""", RegexOption.IGNORE_CASE)
    private val hexBtihRegex = Regex("^[a-fA-F0-9]{40}$")
    private val base32BtihRegex = Regex("^[a-zA-Z2-7]{32}$")
    private val defaultPublicTrackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "https://tracker.gbitt.info/announce",
        "https://tracker.nanoha.org/announce",
        "https://tracker.lilithraws.org/announce",
    )

    fun extractValidMagnet(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) {
            return null
        }
        val candidate = magnetCandidateRegex.find(text)?.value?.trim()
            ?: text.takeIf { it.startsWith("magnet:", ignoreCase = true) }
                ?.substringBefore('\n')
                ?.substringBefore('\r')
                ?.trim()
            ?: return null
        return candidate.takeIf(::isValidMagnetUrl)
    }

    fun isValidMagnetUrl(value: String): Boolean {
        val magnet = value.trim()
        if (!magnet.startsWith("magnet:?", ignoreCase = true)) {
            return false
        }
        return magnetParameterValues(magnet, "xt").any { topic ->
            val normalized = topic.lowercase()
            when {
                normalized.startsWith("urn:btih:") -> isValidBtihHash(topic.substringAfterLast(':'))
                normalized.startsWith("urn:btmh:") -> {
                    val hash = topic.substringAfterLast(':').trim()
                    hash.length >= 32 && hash.any { it != '0' }
                }
                else -> false
            }
        }
    }

    fun isTorrentUrl(value: String?, mimeType: String? = null): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) {
            return false
        }
        val lowerMime = mimeType?.lowercase().orEmpty()
        if (lowerMime.contains("bittorrent") || lowerMime.contains("x-torrent")) {
            return true
        }
        val uri = runCatching { URI(text) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https" && scheme != "content" && scheme != "file") {
            return false
        }
        return uri.path.orEmpty().lowercase().endsWith(".torrent")
    }

    fun titleForMagnet(value: String): String? {
        return magnetParameterValues(value, "dn")
            .firstOrNull()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(MAX_TITLE_LENGTH)
            ?.takeIf { it.isNotBlank() }
    }

    fun extractInfoHash(magnet: String): String? {
        val hash = magnetParameterValues(magnet, "xt")
            .firstOrNull { it.startsWith("urn:btih:", ignoreCase = true) }
            ?.substringAfterLast(':')
            ?.trim()
            ?: return null
        return when {
            hexBtihRegex.matches(hash) -> hash.lowercase()
            base32BtihRegex.matches(hash) -> decodeBase32Btih(hash)
            else -> null
        }
    }

    fun withPublicTrackers(value: String, limit: Int = defaultPublicTrackers.size): String {
        val magnet = extractValidMagnet(value) ?: return value
        val existing = magnetParameterValues(magnet, "tr")
            .map { it.trim().lowercase() }
            .toSet()
        val additions = defaultPublicTrackers
            .filterNot { it.lowercase() in existing }
            .take(limit.coerceAtLeast(0))
        if (additions.isEmpty()) {
            return magnet
        }
        return magnet + additions.joinToString(separator = "") { tracker ->
            "&tr=${encodeUrlComponent(tracker)}"
        }
    }

    fun publicTrackers(): List<String> = defaultPublicTrackers

    fun torrentCacheUrls(infoHash: String): List<String> {
        val hex = infoHash.lowercase()
        return listOf(
            "https://itorrents.org/torrent/$hex.torrent",
            "https://torrage.info/torrent/$hex.torrent",
            "https://torrentproject2.com/torrent/$hex.torrent",
            "https://btcache.me/torrent/$hex",
            "https://torcache.net/torrent/$hex.torrent",
            "https://solidtorrents.net/torrent/$hex",
        )
    }

    fun computeBtih(info: Map<String, Any>): String? {
        return runCatching {
            val encoded = bencodeInfo(info)
            val digest = MessageDigest.getInstance("SHA-1").digest(encoded)
            digest.joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun bencodeInfo(value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        bencode(out, value)
        return out.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun bencode(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is String -> {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.write(bytes.size.toString().toByteArray())
                out.write(':'.code)
                out.write(bytes)
            }
            is Number -> {
                out.write('i'.code)
                out.write(value.toString().toByteArray())
                out.write('e'.code)
            }
            is List<*> -> {
                out.write('l'.code)
                value.forEach { bencode(out, it!!) }
                out.write('e'.code)
            }
            is Map<*, *> -> {
                out.write('d'.code)
                (value as Map<String, Any>).toSortedMap().forEach { (k, v) ->
                    bencode(out, k)
                    bencode(out, v)
                }
                out.write('e'.code)
            }
        }
    }

    fun openExternalDownloader(context: Context, uriString: String, mimeType: String? = null): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (mimeType.isNullOrBlank()) {
                data = uri
            } else {
                setDataAndType(uri, mimeType)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun isValidBtihHash(value: String): Boolean {
        val hash = value.trim()
        if (hash.all { it == '0' }) {
            return false
        }
        return hexBtihRegex.matches(hash) || base32BtihRegex.matches(hash)
    }

    private fun magnetParameterValues(magnet: String, key: String): List<String> {
        val query = magnet.substringAfter("?", "")
        if (query.isBlank()) {
            return emptyList()
        }
        return query
            .split('&', ';')
            .mapNotNull { part ->
                val name = part.substringBefore("=", "").trim()
                if (!name.equals(key, ignoreCase = true)) {
                    return@mapNotNull null
                }
                decodeUrlComponent(part.substringAfter("=", ""))
            }
    }

    private fun decodeUrlComponent(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun encodeUrlComponent(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun decodeBase32Btih(value: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val bytes = mutableListOf<Byte>()
        value.uppercase().forEach { char ->
            val index = alphabet.indexOf(char)
            if (index < 0) {
                return null
            }
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bytes += ((buffer shr (bitsLeft - 8)) and 0xff).toByte()
                bitsLeft -= 8
            }
        }
        return bytes.takeIf { it.size == 20 }?.joinToString("") { "%02x".format(it) }
    }

    private const val MAX_TITLE_LENGTH = 160
}
