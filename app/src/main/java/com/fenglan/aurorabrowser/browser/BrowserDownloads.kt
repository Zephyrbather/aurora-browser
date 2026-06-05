package com.fenglan.aurorabrowser.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fenglan.aurorabrowser.VideoPlayerActivity
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.model.DownloadItem
import com.fenglan.aurorabrowser.model.FileCategory
import com.fenglan.aurorabrowser.model.P2pDownloadEngine
import android.util.Log
import com.fenglan.aurorabrowser.model.ResourceItem
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object BrowserDownloads {
    private const val TAG = "AuroraHLS"
    private val EXPORT_ACTIVE_STATUSES = setOf("已排队", "转码中", "重试中", "等待中")
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activePlaylistCaches = Collections.synchronizedSet(mutableSetOf<String>())

    data class HlsVariantOption(
        val url: String,
        val label: String,
        val bandwidth: Int,
        val resolution: String?,
    )

    fun enqueue(
        context: Context,
        store: BrowserStore,
        url: String,
        title: String?,
        pageUrl: String?,
        mimeType: String?,
        contentDisposition: String?,
        referer: String?,
        userAgent: String?,
        declaredSize: Long? = null,
    ): String {
        val downloadSubdirectory = BrowserPreferences(context).getDownloadSubdirectory()
        val category = ResourceTools.classify(url, mimeType)
        val isHls = url.lowercase().contains(".m3u8") || mimeType?.lowercase()?.contains("mpegurl") == true
        val magnet = TorrentTools.extractValidMagnet(url)
        val resolvedReferer = referer?.takeIf { it.isNotBlank() } ?: pageUrl?.takeIf { it.isNotBlank() }
        val cookieHeader = resolveCookieHeader(url, resolvedReferer)
        val originHeader = resolveOriginHeader(resolvedReferer) ?: resolveOriginHeader(url)
        if (magnet != null || TorrentTools.isTorrentUrl(url, mimeType)) {
            return enqueueP2pDownload(
                context = context,
                store = store,
                sourceUrl = magnet ?: url,
                title = title,
                pageUrl = pageUrl,
                isMagnet = magnet != null,
                referer = resolvedReferer,
                userAgent = userAgent,
                cookieHeader = cookieHeader,
                engine = BrowserPreferences(context).getP2pDownloadEngine(),
            )
        }
        val fileName = ResourceTools.guessFileName(
            url = url,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            fallbackTitle = title,
            preferFallbackTitle = isHls,
        )

        return if (isHls) {
            val cleanName = ResourceTools.ensureExtension(fileName.substringBeforeLast('.', fileName), "m3u8")
            store.upsertResource(
                title = cleanName,
                url = url,
                pageUrl = pageUrl,
                mimeType = "application/x-mpegURL",
                referer = resolvedReferer,
                userAgent = userAgent,
                category = FileCategory.STREAM,
                isStream = true,
            )
            val cacheFile = m3u8CacheFile(context, url)
            val hasCache = cacheFile.exists() && cacheFile.length() > 0
            val estimatedSize = if (hasCache) estimateHlsSize(context, url) else null
            val recordId = store.insertDownload(
                title = cleanName,
                sourceUrl = url,
                pageUrl = pageUrl,
                mimeType = "application/x-mpegURL",
                category = FileCategory.STREAM,
                downloadType = "hls",
                systemDownloadId = null,
                localUri = null,
                status = "已排队",
                downloadedBytes = 0L,
                fileSizeBytes = estimatedSize ?: declaredSize,
                progressPercent = 0,
                errorMessage = null,
            )
            enqueueHlsWork(
                context = context,
                recordId = recordId,
                url = url,
                title = cleanName,
                referer = resolvedReferer,
                userAgent = userAgent,
                cookieHeader = cookieHeader,
                originHeader = originHeader,
                mimeType = mimeType,
                downloadSubdirectory = downloadSubdirectory,
                cachedPlaylistPath = cacheFile.takeIf { hasCache }?.absolutePath,
                pageUrl = pageUrl,
                segmentConcurrency = BrowserPreferences(context).getHlsConcurrency(),
            )
            "已加入流媒体下载队列"
        } else {
            val systemId = enqueueSystemDownload(
                context = context,
                url = url,
                fileName = fileName,
                description = pageUrl ?: url,
                mimeType = mimeType,
                referer = resolvedReferer,
                userAgent = userAgent,
                originHeader = originHeader,
                cookieHeader = cookieHeader,
                subdirectory = downloadSubdirectory,
            )
            store.insertDownload(
                title = fileName,
                sourceUrl = url,
                pageUrl = pageUrl,
                mimeType = mimeType,
                category = category,
                downloadType = "progressive",
                systemDownloadId = systemId,
                localUri = null,
                status = "下载中",
                downloadedBytes = 0L,
                fileSizeBytes = declaredSize,
                progressPercent = 0,
                errorMessage = null,
            )
            "已加入下载队列"
        }
    }

    fun enqueueFromResource(
        context: Context,
        store: BrowserStore,
        item: com.fenglan.aurorabrowser.model.ResourceItem,
        overrideTitle: String? = null,
    ): String {
        return enqueue(
            context = context,
            store = store,
            url = item.url,
            title = overrideTitle ?: item.title,
            pageUrl = item.pageUrl,
            mimeType = item.mimeType,
            contentDisposition = null,
            referer = item.referer ?: item.pageUrl,
            userAgent = item.userAgent,
            declaredSize = null,
        )
    }

    fun enqueueMagnetDownload(
        context: Context,
        store: BrowserStore,
        magnet: String,
        title: String? = null,
        pageUrl: String? = null,
        engine: P2pDownloadEngine = BrowserPreferences(context).getP2pDownloadEngine(),
    ): String {
        val normalized = TorrentTools.extractValidMagnet(magnet) ?: return "无效磁力链接"
        return enqueueP2pDownload(
            context = context,
            store = store,
            sourceUrl = normalized,
            title = title,
            pageUrl = pageUrl,
            isMagnet = true,
            referer = pageUrl,
            userAgent = null,
            cookieHeader = null,
            engine = engine,
        )
    }

    fun openP2pWithExternalApp(context: Context, sourceUrl: String, mimeType: String? = null): Boolean {
        val magnet = TorrentTools.extractValidMagnet(sourceUrl)
        val normalized = magnet ?: sourceUrl.takeIf { TorrentTools.isTorrentUrl(it, mimeType) } ?: return false
        val resolvedMimeType = when {
            magnet != null -> null
            mimeType.isNullOrBlank() -> "application/x-bittorrent"
            else -> mimeType
        }
        return TorrentTools.openExternalDownloader(context, normalized, resolvedMimeType)
    }

    private fun enqueueP2pDownload(
        context: Context,
        store: BrowserStore,
        sourceUrl: String,
        title: String? = null,
        pageUrl: String? = null,
        isMagnet: Boolean,
        referer: String?,
        userAgent: String?,
        cookieHeader: String?,
        engine: P2pDownloadEngine,
    ): String {
        if (engine == P2pDownloadEngine.EXTERNAL) {
            val opened = openP2pWithExternalApp(context, sourceUrl)
            return if (opened) "已交给外部 BT 应用" else "未找到可处理此链接的 BT 应用"
        }
        val fallbackTitle = if (isMagnet) TorrentTools.titleForMagnet(sourceUrl) else null
        val displayTitle = ResourceTools.sanitizeFileName(
            title?.takeIf { it.isNotBlank() }
                ?: fallbackTitle
                ?: sourceUrl.substringAfterLast('/').substringBefore('?').ifBlank { "P2P_${System.currentTimeMillis()}" },
        )
        val downloadSubdirectory = BrowserPreferences(context).getDownloadSubdirectory()
        val recordId = store.insertDownload(
            title = displayTitle,
            sourceUrl = sourceUrl,
            pageUrl = pageUrl,
            mimeType = "application/x-bittorrent",
            category = FileCategory.TORRENT,
            downloadType = "p2p",
            systemDownloadId = null,
            localUri = null,
            status = "已排队",
            downloadedBytes = 0L,
            fileSizeBytes = null,
            progressPercent = 0,
            errorMessage = if (isMagnet) "等待磁力元数据" else "等待种子解析",
        )
        enqueueP2pWork(
            context = context,
            recordId = recordId,
            sourceUrl = sourceUrl,
            title = displayTitle,
            outputSubdirectory = downloadSubdirectory,
            referer = referer,
            userAgent = userAgent,
            cookieHeader = cookieHeader,
        )
        return "已加入 P2P 下载队列"
    }

    fun exportHlsToMp4(context: Context, store: BrowserStore, item: DownloadItem): String {
        val downloadSubdirectory = BrowserPreferences(context).getDownloadSubdirectory()
        val localUri = item.localUri?.takeIf { it.isNotBlank() }
            ?: return "源文件不存在"
        val outputTitle = ResourceTools.ensureExtension(item.title.substringBeforeLast('.', item.title), "mp4")
        val existingExports = store.getDownloads().filter {
            it.sourceUrl == item.sourceUrl && it.downloadType == "hls_export"
        }
        if (existingExports.any { it.status in EXPORT_ACTIVE_STATUSES }) {
            return "MP4 转码已在队列中"
        }
        if (existingExports.any { it.status.startsWith("已完成") && !it.localUri.isNullOrBlank() }) {
            return "MP4 文件已存在"
        }
        val recordId = store.insertDownload(
            title = outputTitle,
            sourceUrl = item.sourceUrl,
            pageUrl = item.pageUrl,
            mimeType = "video/mp4",
            category = FileCategory.VIDEO,
            downloadType = "hls_export",
            systemDownloadId = null,
            localUri = null,
            status = "已排队",
            downloadedBytes = 0L,
            fileSizeBytes = null,
            progressPercent = 0,
            errorMessage = "源文件: ${item.title}",
        )
        enqueueHlsExportWork(
            context = context,
            recordId = recordId,
            sourceUri = localUri,
            title = outputTitle,
            outputSubdirectory = downloadSubdirectory,
        )
        return "已加入 MP4 转码队列"
    }

    fun saveHtmlDocument(
        context: Context,
        store: BrowserStore,
        title: String?,
        pageUrl: String,
        html: String,
    ): String {
        val downloadSubdirectory = BrowserPreferences(context).getDownloadSubdirectory()
        val fileName = ResourceTools.ensureExtension(
            ResourceTools.sanitizeFileName(title?.takeIf { it.isNotBlank() } ?: Uri.parse(pageUrl).host ?: "page"),
            "html",
        )
        val outputUri = createMediaStoreDownloadUri(context, fileName, "text/html", downloadSubdirectory)
            ?: return "网页保存失败"

        return runCatching {
            context.contentResolver.openOutputStream(outputUri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(html)
            } ?: error("无法写入网页文件")
            finalizePendingDownload(context, outputUri)
            val size = context.contentResolver.openFileDescriptor(outputUri, "r")?.use { it.statSize }
            store.insertDownload(
                title = fileName,
                sourceUrl = pageUrl,
                pageUrl = pageUrl,
                mimeType = "text/html",
                category = FileCategory.DOCUMENT,
                downloadType = "html",
                systemDownloadId = null,
                localUri = outputUri.toString(),
                status = "已完成",
                downloadedBytes = size,
                fileSizeBytes = size,
                progressPercent = 100,
                errorMessage = "网页 HTML",
            )
            "网页 HTML 已保存"
        }.getOrElse { error ->
            runCatching { context.contentResolver.delete(outputUri, null, null) }
            error.message ?: "网页保存失败"
        }
    }

    fun pauseDownload(context: Context, store: BrowserStore, item: DownloadItem): String {
        return when (item.downloadType) {
            "hls", "p2p" -> {
                WorkManager.getInstance(context).cancelAllWorkByTag(downloadWorkTag(item.id))
                store.updateDownload(
                    id = item.id,
                    status = "已暂停",
                    localUri = item.localUri,
                    downloadedBytes = item.downloadedBytes,
                    fileSizeBytes = item.fileSizeBytes,
                    progressPercent = item.progressPercent,
                    errorMessage = "已暂停",
                )
                "下载已暂停"
            }

            "progressive" -> {
                item.systemDownloadId?.let {
                    val manager = context.getSystemService(DownloadManager::class.java)
                    runCatching { manager.remove(it) }
                }
                store.updateDownloadRequest(
                    id = item.id,
                    systemDownloadId = null,
                    status = "已暂停",
                    localUri = null,
                    downloadedBytes = item.downloadedBytes,
                    fileSizeBytes = item.fileSizeBytes,
                    progressPercent = item.progressPercent,
                    errorMessage = "已暂停，继续时将重新开始",
                    clearLocalUri = true,
                )
                "下载已暂停"
            }

            else -> "当前任务不支持暂停"
        }
    }

    fun resumeDownload(context: Context, store: BrowserStore, item: DownloadItem): String {
        return when (item.downloadType) {
            "hls", "p2p" -> {
                if (item.downloadType == "hls") {
                    val resource = resolveResourceForDownload(store, item)
                    val referer = resource?.referer ?: item.pageUrl
                    val userAgent = resource?.userAgent
                    val cookieHeader = resolveCookieHeader(item.sourceUrl, referer)
                    val originHeader = resolveOriginHeader(referer) ?: resolveOriginHeader(item.sourceUrl)
                    enqueueHlsWork(
                        context = context,
                        recordId = item.id,
                        url = item.sourceUrl,
                        title = item.title,
                        referer = referer,
                        userAgent = userAgent,
                        cookieHeader = cookieHeader,
                        originHeader = originHeader,
                        mimeType = item.mimeType,
                        downloadSubdirectory = BrowserPreferences(context).getDownloadSubdirectory(),
                        pageUrl = item.pageUrl,
                        segmentConcurrency = BrowserPreferences(context).getHlsConcurrency(),
                    )
                } else {
                    enqueueP2pWork(
                        context = context,
                        recordId = item.id,
                        sourceUrl = item.sourceUrl,
                        title = item.title,
                        outputSubdirectory = BrowserPreferences(context).getDownloadSubdirectory(),
                        referer = item.pageUrl,
                        userAgent = null,
                        cookieHeader = resolveCookieHeader(item.sourceUrl, item.pageUrl),
                    )
                }
                store.updateDownload(
                    id = item.id,
                    status = "下载中",
                    localUri = item.localUri,
                    downloadedBytes = item.downloadedBytes,
                    fileSizeBytes = item.fileSizeBytes,
                    progressPercent = item.progressPercent ?: 0,
                    errorMessage = "继续下载",
                )
                "已继续下载"
            }

            "progressive" -> {
                val resource = resolveResourceForDownload(store, item)
                val referer = resource?.referer ?: item.pageUrl
                val userAgent = resource?.userAgent
                val cookieHeader = resolveCookieHeader(item.sourceUrl, referer)
                val originHeader = resolveOriginHeader(referer) ?: resolveOriginHeader(item.sourceUrl)
                val systemId = enqueueSystemDownload(
                    context = context,
                    url = item.sourceUrl,
                    fileName = item.title,
                    description = item.pageUrl ?: item.sourceUrl,
                    mimeType = item.mimeType,
                    referer = referer,
                    userAgent = userAgent,
                    originHeader = originHeader,
                    cookieHeader = cookieHeader,
                    subdirectory = BrowserPreferences(context).getDownloadSubdirectory(),
                )
                store.updateDownloadRequest(
                    id = item.id,
                    systemDownloadId = systemId,
                    status = "下载中",
                    localUri = null,
                    downloadedBytes = 0L,
                    fileSizeBytes = item.fileSizeBytes,
                    progressPercent = 0,
                    errorMessage = "重新开始下载",
                    clearLocalUri = true,
                )
                "已重新开始下载"
            }

            else -> "当前任务不支持继续"
        }
    }

    fun retryDownload(context: Context, store: BrowserStore, item: DownloadItem): String {
        if (!canRetry(item)) {
            return "当前任务不支持重新下载"
        }
        return when (item.downloadType) {
            "hls", "p2p" -> {
                WorkManager.getInstance(context).cancelAllWorkByTag(downloadWorkTag(item.id))
                if (item.downloadType == "hls") {
                    val resource = resolveResourceForDownload(store, item)
                    val referer = resource?.referer ?: item.pageUrl
                    val userAgent = resource?.userAgent
                    enqueueHlsWork(
                        context = context,
                        recordId = item.id,
                        url = item.sourceUrl,
                        title = item.title,
                        referer = referer,
                        userAgent = userAgent,
                        cookieHeader = resolveCookieHeader(item.sourceUrl, referer),
                        originHeader = resolveOriginHeader(referer) ?: resolveOriginHeader(item.sourceUrl),
                        mimeType = item.mimeType,
                        downloadSubdirectory = BrowserPreferences(context).getDownloadSubdirectory(),
                        pageUrl = item.pageUrl,
                        segmentConcurrency = BrowserPreferences(context).getHlsConcurrency(),
                    )
                } else {
                    enqueueP2pWork(
                        context = context,
                        recordId = item.id,
                        sourceUrl = item.sourceUrl,
                        title = item.title,
                        outputSubdirectory = BrowserPreferences(context).getDownloadSubdirectory(),
                        referer = item.pageUrl,
                        userAgent = null,
                        cookieHeader = resolveCookieHeader(item.sourceUrl, item.pageUrl),
                    )
                }
                store.updateDownload(
                    id = item.id,
                    status = "下载中",
                    localUri = item.localUri,
                    downloadedBytes = item.downloadedBytes ?: 0L,
                    fileSizeBytes = item.fileSizeBytes,
                    progressPercent = item.progressPercent ?: 0,
                    errorMessage = "重新下载",
                )
                "已重新下载"
            }

            "progressive" -> {
                val manager = context.getSystemService(DownloadManager::class.java)
                item.systemDownloadId?.let { runCatching { manager.remove(it) } }
                val resource = resolveResourceForDownload(store, item)
                val referer = resource?.referer ?: item.pageUrl
                val userAgent = resource?.userAgent
                val systemId = enqueueSystemDownload(
                    context = context,
                    url = item.sourceUrl,
                    fileName = item.title,
                    description = item.pageUrl ?: item.sourceUrl,
                    mimeType = item.mimeType,
                    referer = referer,
                    userAgent = userAgent,
                    originHeader = resolveOriginHeader(referer) ?: resolveOriginHeader(item.sourceUrl),
                    cookieHeader = resolveCookieHeader(item.sourceUrl, referer),
                    subdirectory = BrowserPreferences(context).getDownloadSubdirectory(),
                )
                store.updateDownloadRequest(
                    id = item.id,
                    systemDownloadId = systemId,
                    status = "下载中",
                    localUri = null,
                    downloadedBytes = 0L,
                    fileSizeBytes = item.fileSizeBytes,
                    progressPercent = 0,
                    errorMessage = "重新下载",
                    clearLocalUri = true,
                )
                "已重新下载"
            }

            "hls_export" -> {
                val source = store.getDownloads().firstOrNull {
                    it.downloadType == "hls" &&
                        it.sourceUrl == item.sourceUrl &&
                        it.status.startsWith("已完成") &&
                        !it.localUri.isNullOrBlank()
                } ?: return "源 m3u8 文件不存在"
                WorkManager.getInstance(context).cancelAllWorkByTag(downloadWorkTag(item.id))
                enqueueHlsExportWork(
                    context = context,
                    recordId = item.id,
                    sourceUri = source.localUri.orEmpty(),
                    title = item.title,
                    outputSubdirectory = BrowserPreferences(context).getDownloadSubdirectory(),
                )
                store.updateDownload(
                    id = item.id,
                    status = "已排队",
                    localUri = null,
                    downloadedBytes = 0L,
                    fileSizeBytes = null,
                    progressPercent = 0,
                    errorMessage = "重新转码",
                    clearLocalUri = true,
                    clearFileSizeBytes = true,
                )
                "已重新转码"
            }

            else -> "当前任务不支持重新下载"
        }
    }

    fun cancelDownload(context: Context, store: BrowserStore, item: DownloadItem): String {
        when (item.downloadType) {
            "hls", "hls_export", "p2p" -> {
                WorkManager.getInstance(context).cancelAllWorkByTag(downloadWorkTag(item.id))
                if (!item.localUri.isNullOrBlank()) {
                    deleteLocalFile(context, item)
                }
                store.updateDownload(
                    id = item.id,
                    status = "已取消",
                    localUri = null,
                    downloadedBytes = 0L,
                    fileSizeBytes = item.fileSizeBytes,
                    progressPercent = 0,
                    errorMessage = "已取消",
                    clearLocalUri = true,
                )
            }

            "progressive" -> {
                item.systemDownloadId?.let {
                    val manager = context.getSystemService(DownloadManager::class.java)
                    runCatching { manager.remove(it) }
                }
                store.updateDownloadRequest(
                    id = item.id,
                    systemDownloadId = null,
                    status = "已取消",
                    localUri = null,
                    downloadedBytes = 0L,
                    fileSizeBytes = item.fileSizeBytes,
                    progressPercent = 0,
                    errorMessage = "已取消",
                    clearLocalUri = true,
                )
            }
        }
        return "已取消下载"
    }

    fun deleteDownload(context: Context, store: BrowserStore, item: DownloadItem, deleteFile: Boolean): String {
        val manager = context.getSystemService(DownloadManager::class.java)
        val isCompleted = item.status.startsWith("已完成")

        if ((item.downloadType == "hls" || item.downloadType == "hls_export" || item.downloadType == "p2p") && !isCompleted) {
            WorkManager.getInstance(context).cancelAllWorkByTag(downloadWorkTag(item.id))
        }

        if (item.systemDownloadId != null && !deleteFile && !isCompleted) {
            runCatching { manager.remove(item.systemDownloadId) }
        }

        val fileDeleted = if (deleteFile) {
            if (item.localUri.isNullOrBlank() && item.systemDownloadId != null) {
                runCatching { manager.remove(item.systemDownloadId) > 0 }.getOrDefault(false)
            } else {
                deleteLocalFile(context, item)
            }
        } else {
            false
        }

        store.deleteDownload(item.id)
        return when {
            deleteFile && item.localUri.isNullOrBlank() -> "记录已删除"
            deleteFile && fileDeleted -> "记录和文件已删除"
            deleteFile -> "记录已删除，文件可能未完全删除"
            else -> "记录已删除"
        }
    }

    fun refreshSystemDownloads(context: Context, store: BrowserStore) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val tracked = store.getDownloads(systemOnly = true)
        tracked.forEach { item ->
            val systemId = item.systemDownloadId ?: return@forEach
            val cursor = manager.query(DownloadManager.Query().setFilterById(systemId))
            cursor.use {
                if (!it.moveToFirst()) {
                    return@forEach
                }
                val statusCode = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val downloadedBytes = readLongOrNull(it, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytes = readLongOrNull(it, DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val progress = if (downloadedBytes != null && bytes != null && bytes > 0) {
                    ((downloadedBytes * 100f) / bytes).toInt().coerceIn(0, 100)
                } else {
                    null
                }
                val uri = manager.getUriForDownloadedFile(systemId)?.toString()
                    ?: readStringOrNull(it, DownloadManager.COLUMN_LOCAL_URI)
                when (statusCode) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        store.updateDownloadBySystemId(
                            systemId,
                            "已完成",
                            uri,
                            bytes,
                            bytes,
                            100,
                            null,
                            clearErrorMessage = true,
                        )
                    }

                    DownloadManager.STATUS_FAILED -> {
                        store.updateDownloadBySystemId(systemId, "失败", uri, downloadedBytes, bytes, progress, "系统下载失败($reason)")
                    }

                    DownloadManager.STATUS_PAUSED -> {
                        store.updateDownloadBySystemId(systemId, "已暂停", uri, downloadedBytes, bytes, progress, "暂停($reason)")
                    }

                    DownloadManager.STATUS_PENDING -> {
                        store.updateDownloadBySystemId(
                            systemId,
                            "等待中",
                            uri,
                            downloadedBytes,
                            bytes,
                            progress,
                            null,
                            clearErrorMessage = true,
                        )
                    }

                    DownloadManager.STATUS_RUNNING -> {
                        store.updateDownloadBySystemId(
                            systemId,
                            "下载中",
                            uri,
                            downloadedBytes,
                            bytes,
                            progress,
                            null,
                            clearErrorMessage = true,
                        )
                    }
                }
            }
        }
    }

    fun openDownload(context: Context, item: DownloadItem): Boolean {
        val localUri = item.localUri ?: return false
        if (item.downloadType == "hls") {
            return runCatching {
                context.startActivity(
                    VideoPlayerActivity.createIntent(
                        context = context,
                        title = item.title,
                        playlistUri = localUri,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
        }
        val uri = Uri.parse(localUri)
        val mime = item.mimeType ?: defaultMimeForCategory(item.category)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun defaultMimeForCategory(category: FileCategory): String {
        return when (category) {
            FileCategory.VIDEO, FileCategory.STREAM -> "video/*"
            FileCategory.AUDIO -> "audio/*"
            FileCategory.IMAGE -> "image/*"
            FileCategory.SUBTITLE -> "text/*"
            FileCategory.DOCUMENT -> "application/*"
            FileCategory.TORRENT -> "application/x-bittorrent"
            FileCategory.ARCHIVE, FileCategory.OTHER, FileCategory.ALL -> "*/*"
        }
    }

    fun canPause(item: DownloadItem): Boolean {
        return item.downloadType != "hls_export" &&
            item.status in setOf("下载中", "等待中", "重试中", "已排队")
    }

    fun canResume(item: DownloadItem): Boolean {
        return item.downloadType != "hls_export" && item.status == "已暂停"
    }

    fun canRetry(item: DownloadItem): Boolean {
        return item.status == "失败" && item.downloadType in setOf("hls", "progressive", "hls_export", "p2p")
    }

    fun canCancel(item: DownloadItem): Boolean {
        return item.status in setOf("下载中", "等待中", "重试中", "已暂停", "已排队", "转码中")
    }

    private fun readLongOrNull(cursor: Cursor, columnName: String): Long? {
        val columnIndex = cursor.getColumnIndex(columnName)
        if (columnIndex == -1 || cursor.isNull(columnIndex)) {
            return null
        }
        return cursor.getLong(columnIndex)
    }

    private fun readStringOrNull(cursor: Cursor, columnName: String): String? {
        val columnIndex = cursor.getColumnIndex(columnName)
        if (columnIndex == -1 || cursor.isNull(columnIndex)) {
            return null
        }
        return cursor.getString(columnIndex)
    }

    private fun deleteLocalFile(context: Context, item: DownloadItem): Boolean {
        val value = item.localUri?.takeIf { it.isNotBlank() } ?: return false
        val uri = Uri.parse(value)
        if (item.downloadType == "hls") {
            return HlsPackageTools.deletePackageForUri(item.localUri)
        }
        if (item.downloadType == "p2p") {
            return when (uri.scheme?.lowercase()) {
                "file" -> runCatching { File(requireNotNull(uri.path)).deleteRecursively() }.getOrDefault(false)
                else -> false
            }
        }
        val deletedBySystem = if (item.systemDownloadId != null) {
            val manager = context.getSystemService(DownloadManager::class.java)
            runCatching { manager.remove(item.systemDownloadId) > 0 }.getOrDefault(false)
        } else {
            false
        }
        if (deletedBySystem) {
            return true
        }
        return when (uri.scheme?.lowercase()) {
            "content" -> runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

            "file" -> runCatching { java.io.File(requireNotNull(uri.path)).delete() }.getOrDefault(false)
            else -> false
        }
    }

    private fun enqueueHlsWork(
        context: Context,
        recordId: Long,
        url: String,
        title: String,
        referer: String?,
        userAgent: String?,
        cookieHeader: String?,
        originHeader: String?,
        mimeType: String?,
        downloadSubdirectory: String,
        cachedPlaylistPath: String? = null,
        pageUrl: String? = null,
        segmentConcurrency: Int = 4,
    ) {
        val data = Data.Builder()
            .putLong(HlsDownloadWorker.KEY_RECORD_ID, recordId)
            .putString(HlsDownloadWorker.KEY_URL, url)
            .putString(HlsDownloadWorker.KEY_TITLE, title)
            .putString(HlsDownloadWorker.KEY_REFERER, referer)
            .putString(HlsDownloadWorker.KEY_USER_AGENT, userAgent)
            .putString(HlsDownloadWorker.KEY_COOKIE, cookieHeader)
            .putString(HlsDownloadWorker.KEY_ORIGIN, originHeader)
            .putString(HlsDownloadWorker.KEY_MIME_TYPE, mimeType)
            .putString(HlsDownloadWorker.KEY_OUTPUT_SUBDIRECTORY, downloadSubdirectory)
            .putInt(HlsDownloadWorker.KEY_SEGMENT_CONCURRENCY, segmentConcurrency.coerceIn(1, 8))
        if (!cachedPlaylistPath.isNullOrBlank()) {
            data.putString(HlsDownloadWorker.KEY_CACHED_PLAYLIST, cachedPlaylistPath)
        }
        if (!pageUrl.isNullOrBlank()) {
            data.putString(HlsDownloadWorker.KEY_PAGE_URL, pageUrl)
        }
        val request = OneTimeWorkRequestBuilder<HlsDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(downloadWorkTag(recordId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(10))
            .setInputData(data.build())
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun enqueueHlsExportWork(
        context: Context,
        recordId: Long,
        sourceUri: String,
        title: String,
        outputSubdirectory: String,
    ) {
        val request = OneTimeWorkRequestBuilder<HlsExportWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .addTag(downloadWorkTag(recordId))
            .setInputData(
                Data.Builder()
                    .putLong(HlsExportWorker.KEY_RECORD_ID, recordId)
                    .putString(HlsExportWorker.KEY_SOURCE_URI, sourceUri)
                    .putString(HlsExportWorker.KEY_TITLE, title)
                    .putString(HlsExportWorker.KEY_OUTPUT_SUBDIRECTORY, outputSubdirectory)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun enqueueP2pWork(
        context: Context,
        recordId: Long,
        sourceUrl: String,
        title: String,
        outputSubdirectory: String,
        referer: String? = null,
        userAgent: String? = null,
        cookieHeader: String? = null,
    ) {
        val request = OneTimeWorkRequestBuilder<P2pDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(downloadWorkTag(recordId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(10))
            .setInputData(
                Data.Builder()
                    .putLong(P2pDownloadWorker.KEY_RECORD_ID, recordId)
                    .putString(P2pDownloadWorker.KEY_SOURCE_URL, sourceUrl)
                    .putString(P2pDownloadWorker.KEY_TITLE, title)
                    .putString(P2pDownloadWorker.KEY_OUTPUT_SUBDIRECTORY, outputSubdirectory)
                    .putString(P2pDownloadWorker.KEY_REFERER, referer)
                    .putString(P2pDownloadWorker.KEY_USER_AGENT, userAgent)
                    .putString(P2pDownloadWorker.KEY_COOKIE, cookieHeader)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun enqueueSystemDownload(
        context: Context,
        url: String,
        fileName: String,
        description: String,
        mimeType: String?,
        referer: String?,
        userAgent: String?,
        originHeader: String?,
        cookieHeader: String?,
        subdirectory: String,
    ): Long {
        val manager = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription(description)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$subdirectory/$fileName")

        referer?.let { request.addRequestHeader("Referer", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        originHeader?.let { request.addRequestHeader("Origin", it) }
        cookieHeader?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }

        return manager.enqueue(request)
    }

    private fun createMediaStoreDownloadUri(
        context: Context,
        fileName: String,
        mimeType: String,
        subdirectory: String,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subdirectory")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun finalizePendingDownload(context: Context, uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null)
    }

    private fun downloadWorkTag(recordId: Long): String = "aurora_download_$recordId"

    private fun resolveResourceForDownload(store: BrowserStore, item: DownloadItem): ResourceItem? {
        return store.getResourceByUrl(item.sourceUrl)
    }

    fun cacheM3u8Playlist(
        context: Context,
        url: String,
        referer: String?,
        userAgent: String?,
    ) {
        val cookie = CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        val origin = resolveOriginHeader(referer) ?: resolveOriginHeader(url)
        val cacheFile = m3u8CacheFile(context, url)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Log.d(TAG, "cacheM3u8Playlist SKIP already cached size=${cacheFile.length()} url=${url.take(100)}")
            return
        }
        if (!activePlaylistCaches.add(url)) {
            return
        }
        Log.d(TAG, "cacheM3u8Playlist START url=${url.take(120)}")
        cacheScope.launch {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 8000
                    connection.readTimeout = 10000
                    val ua = userAgent?.takeIf { it.isNotBlank() }
                        ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    connection.setRequestProperty("User-Agent", ua)
                    referer?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
                    cookie?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
                    origin?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Origin", it) }
                    connection.setRequestProperty("Accept", "*/*")
                    connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                    connection.setRequestProperty("Sec-Fetch-Dest", "empty")
                    connection.setRequestProperty("Sec-Fetch-Mode", "cors")
                    connection.setRequestProperty("Sec-Fetch-Site", "cross-site")
                    connection.connect()
                    val code = connection.responseCode
                    Log.d(TAG, "cacheM3u8Playlist HTTP $code url=${url.take(100)}")
                    if (code in 200..299) {
                        cacheFile.parentFile?.mkdirs()
                        val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.part")
                        try {
                            connection.inputStream.use { input ->
                                FileOutputStream(tmpFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (!tmpFile.renameTo(cacheFile)) {
                                tmpFile.copyTo(cacheFile, overwrite = true)
                                tmpFile.delete()
                            }
                        } finally {
                            if (tmpFile.exists()) {
                                tmpFile.delete()
                            }
                        }
                        Log.d(TAG, "cacheM3u8Playlist OK size=${cacheFile.length()}")
                    }
                } finally {
                    connection.disconnect()
                }
            }.onFailure {
                Log.e(TAG, "cacheM3u8Playlist FAIL ${it.message} url=${url.take(100)}")
            }.also {
                activePlaylistCaches.remove(url)
            }
        }
    }

    fun m3u8CacheFile(context: Context, url: String): File {
        return File(File(context.cacheDir, "hls_playlists"), "m3u8_${url.hashCode()}.tmp")
    }

    fun estimateHlsSize(context: Context, url: String): Long? {
        val cacheFile = m3u8CacheFile(context, url)
        if (!cacheFile.exists() || cacheFile.length() <= 0) return null
        return runCatching {
            val text = cacheFile.readText()
            var totalDuration = 0.0
            var bandwidth = 3_000_000L
            var targetDuration = 10
            text.lineSequence().forEach { line ->
                when {
                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        targetDuration = line.substringAfter(':').trim().toIntOrNull() ?: 10
                    line.startsWith("#EXTINF:") -> {
                        val dur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                        if (dur != null && dur > 0) totalDuration += dur
                    }
                }
            }
            if (totalDuration > 0) {
                (totalDuration * bandwidth / 8).toLong().coerceAtLeast(1024 * 1024)
            } else {
                val segCount = text.lineSequence().count { !it.startsWith("#") && it.isNotBlank() }
                if (segCount > 0) (segCount.toLong() * targetDuration * bandwidth / 8) else null
            }
        }.getOrNull()
    }

    fun loadHlsVariants(
        context: Context,
        url: String,
        referer: String?,
        userAgent: String?,
    ): List<HlsVariantOption> {
        val cacheText = m3u8CacheFile(context, url)
            .takeIf { it.exists() && it.length() > 0 }
            ?.readText()
        val playlistText = cacheText ?: fetchPlaylistText(url, referer, userAgent)
        return parseHlsVariants(url, playlistText)
    }

    private fun parseHlsVariants(masterUrl: String, text: String): List<HlsVariantOption> {
        if (!text.contains("#EXT-X-STREAM-INF")) {
            return emptyList()
        }
        val lines = text.lines()
        return buildList {
            for (index in lines.indices) {
                val line = lines[index].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                    continue
                }
                val attrs = parseAttributes(line.substringAfter(':'))
                val nextLine = lines.getOrNull(index + 1)?.trim().orEmpty()
                if (nextLine.isBlank() || nextLine.startsWith("#")) {
                    continue
                }
                val bandwidth = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0
                val resolution = attrs["RESOLUTION"]
                val quality = resolution?.substringAfter('x')?.takeIf { it.isNotBlank() }?.let { "${it}p" }
                val mbps = if (bandwidth > 0) String.format(Locale.US, "%.1f Mbps", bandwidth / 1_000_000.0) else "码率未知"
                add(
                    HlsVariantOption(
                        url = URL(URL(masterUrl), nextLine).toString(),
                        label = listOfNotNull(quality, resolution, mbps).joinToString(" · "),
                        bandwidth = bandwidth,
                        resolution = resolution,
                    ),
                )
            }
        }.distinctBy { it.url }.sortedByDescending { it.bandwidth }
    }

    private fun fetchPlaylistText(url: String, referer: String?, userAgent: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.setRequestProperty(
                "User-Agent",
                userAgent?.takeIf { it.isNotBlank() }
                    ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            referer?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Referer", it) }
            resolveCookieHeader(url, referer)?.let { connection.setRequestProperty("Cookie", it) }
            connection.setRequestProperty("Accept", "*/*")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("playlist http ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(raw).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2].trim().removeSurrounding("\"")
        }
        return result
    }

    private fun resolveCookieHeader(url: String, referer: String?): String? {
        val cookieManager = CookieManager.getInstance()
        return cookieManager.getCookie(url)
            ?.takeIf { it.isNotBlank() }
            ?: referer?.let(cookieManager::getCookie)?.takeIf { it.isNotBlank() }
    }

    private fun resolveOriginHeader(url: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$port"
    }
}
