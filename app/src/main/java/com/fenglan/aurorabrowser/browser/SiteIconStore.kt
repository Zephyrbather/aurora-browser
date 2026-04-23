package com.fenglan.aurorabrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class SiteIconStore(context: Context) {
    private val iconDir = File(context.filesDir, "site_icons").apply { mkdirs() }
    private val memoryCache = object : LruCache<String, Bitmap>(ICON_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun load(url: String): Bitmap? {
        val file = fileForUrl(url) ?: return null
        if (!file.exists()) {
            return null
        }
        val key = file.absolutePath
        memoryCache.get(key)?.let { return it }
        return BitmapFactory.decodeFile(file.absolutePath)?.also { bitmap ->
            memoryCache.put(key, bitmap)
        }
    }

    fun save(url: String, bitmap: Bitmap): Boolean {
        val file = fileForUrl(url) ?: return false
        return runCatching {
            file.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            memoryCache.put(file.absolutePath, bitmap)
            true
        }.getOrDefault(false)
    }

    fun fetchAndSave(
        pageUrl: String,
        iconUrl: String?,
        referer: String?,
        userAgent: String?,
    ): Boolean {
        val targets = buildCandidates(pageUrl, iconUrl)
        targets.forEach { target ->
            val bitmap = runCatching { downloadBitmap(target, referer, userAgent) }.getOrNull()
            if (bitmap != null && save(pageUrl, bitmap)) {
                return true
            }
        }
        return false
    }

    private fun buildCandidates(pageUrl: String, iconUrl: String?): List<String> {
        val pageUri = runCatching { Uri.parse(pageUrl) }.getOrNull()
        val root = if (pageUri?.scheme != null && pageUri.host != null) {
            "${pageUri.scheme}://${pageUri.host}${pageUri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()}"
        } else {
            null
        }
        return buildList {
            iconUrl?.takeIf { it.isNotBlank() }?.let { add(resolveUrl(pageUrl, it)) }
            root?.let { add("$it/favicon.ico") }
            root?.let { add("$it/apple-touch-icon.png") }
            root?.let { add("$it/apple-touch-icon-precomposed.png") }
        }.distinct()
    }

    private fun downloadBitmap(targetUrl: String, referer: String?, userAgent: String?): Bitmap {
        val connection = URL(targetUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            referer?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
            userAgent?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("User-Agent", it) }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("图标下载失败: ${connection.responseCode}")
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("图标解析失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveUrl(pageUrl: String, iconUrl: String): String {
        return runCatching { URL(URL(pageUrl), iconUrl).toString() }.getOrDefault(iconUrl)
    }

    private fun fileForUrl(url: String): File? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US)?.replace(Regex("[^a-z0-9._-]"), "_")
            ?: return null
        return File(iconDir, "$host.png")
    }

    companion object {
        private const val ICON_CACHE_SIZE_KB = 4 * 1024
    }
}
