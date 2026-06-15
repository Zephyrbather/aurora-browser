package com.fenglan.aurorabrowser.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.fenglan.aurorabrowser.LibraryActivity
import com.fenglan.aurorabrowser.R
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.model.LibraryMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

private fun formatSpeed(bytesPerSecond: Long): String {
    return when {
        bytesPerSecond >= 1_000_000L -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
        bytesPerSecond >= 1_000L -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1_000.0)
        bytesPerSecond > 0 -> "${bytesPerSecond} B/s"
        else -> "--"
    }
}

class HlsDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val store = BrowserStore.getInstance(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
        val playlistUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "stream_${System.currentTimeMillis()}.m3u8"
        Log.d("AuroraHLS", "Worker START recordId=$recordId url=${playlistUrl.take(120)} attempt=$runAttemptCount")
        var headers = HlsPackageTools.RequestHeaders(
            referer = inputData.getString(KEY_REFERER),
            userAgent = inputData.getString(KEY_USER_AGENT),
            cookie = inputData.getString(KEY_COOKIE),
            origin = inputData.getString(KEY_ORIGIN),
        )
        val outputSubdirectory = inputData.getString(KEY_OUTPUT_SUBDIRECTORY) ?: "Aurora"
        val isPrivate = inputData.getBoolean(KEY_IS_PRIVATE, false)

        if (recordId <= 0) {
            return@withContext Result.failure()
        }

        val cachedPlaylistPath = inputData.getString(KEY_CACHED_PLAYLIST)
        val pageUrl = inputData.getString(KEY_PAGE_URL)
        Log.d("AuroraHLS", "Worker cachedPlaylistPath=$cachedPlaylistPath pageUrl=$pageUrl")

        var resolvedUrl = playlistUrl
        var resolvedCached = cachedPlaylistPath?.let { File(it) }

        if (pageUrl != null) {
            val freshResource = store.getResourcesForPage(pageUrl)
                .filter { ResourceTools.isStream(it.url, it.mimeType) }
                .maxByOrNull { it.capturedAt }
            if (freshResource != null && freshResource.url != playlistUrl) {
                Log.d("AuroraHLS", "Found fresher stream URL: ${freshResource.url.take(120)}")
                resolvedUrl = freshResource.url
                val freshCache = BrowserDownloads.m3u8CacheFile(applicationContext, freshResource.url)
                if (freshCache.exists() && freshCache.length() > 0) {
                    resolvedCached = freshCache
                } else {
                    resolvedCached = null
                }
                if (!freshResource.referer.isNullOrBlank()) {
                    headers = headers.copy(referer = freshResource.referer)
                }
            }
        }

        setForeground(createForegroundInfo(title, 0))

        try {
            store.updateDownload(recordId, "下载中", progressPercent = 0)
            val startTime = System.currentTimeMillis()
            var lastBytes = 0L
            var lastTime = startTime
            val downloaded = HlsPackageTools.downloadPackage(
                context = applicationContext,
                recordId = recordId,
                title = title,
                playlistUrl = resolvedUrl,
                headers = headers,
                downloadSubdirectory = outputSubdirectory,
                cachedPlaylistFile = resolvedCached,
                segmentConcurrency = inputData.getInt(KEY_SEGMENT_CONCURRENCY, 4),
                isPrivate = isPrivate,
                shouldContinue = {
                    !isStopped && store.getDownloadStatus(recordId) !in setOf("已暂停", "已取消")
                },
            ) { playlistFile, downloadedBytes, done, total ->
                val progress = if (total <= 0) 0 else ((done * 100f) / total).toInt().coerceIn(0, 99)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime).coerceAtLeast(1L)
                val deltaBytes = downloadedBytes - lastBytes
                val instantSpeed = if (deltaBytes > 0) deltaBytes * 1000L / elapsed else 0L
                lastBytes = downloadedBytes
                lastTime = now
                val speedText = formatSpeed(instantSpeed)
                store.updateDownload(
                    id = recordId,
                    status = "下载中",
                    localUri = Uri.fromFile(playlistFile).toString(),
                    downloadedBytes = downloadedBytes,
                    fileSizeBytes = null,
                    progressPercent = progress,
                    errorMessage = "$done/$total 分片 · $speedText",
                    clearFileSizeBytes = true,
                )
                setProgressAsync(
                    androidx.work.Data.Builder()
                        .putInt("done", done)
                        .putInt("total", total)
                        .putLong("speed", instantSpeed)
                        .build(),
                )
            }
            val totalElapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val avgSpeedText = formatSpeed(downloaded.totalBytes * 1000L / totalElapsed)
            store.updateDownloadResult(
                id = recordId,
                title = ResourceTools.ensureExtension(title.substringBeforeLast('.', title), "m3u8"),
                mimeType = "application/x-mpegURL",
                status = "已完成",
                localUri = Uri.fromFile(downloaded.playlistFile).toString(),
                downloadedBytes = downloaded.totalBytes,
                fileSizeBytes = downloaded.totalBytes,
                progressPercent = 100,
                errorMessage = "m3u8 · ${downloaded.segmentCount}段 · 平均 $avgSpeedText",
            )
            setForeground(createForegroundInfo(title, 100))
            Result.success()
        } catch (error: Throwable) {
            Log.e("AuroraHLS", "Worker FAIL recordId=$recordId attempt=$runAttemptCount error=${error.message}", error)
            if (store.getDownloadStatus(recordId) in setOf("已暂停", "已取消")) {
                return@withContext Result.success()
            }
            if (error is IOException && runAttemptCount < 2) {
                store.updateDownload(recordId, "重试中", errorMessage = error.message ?: "流媒体下载重试中")
                return@withContext Result.retry()
            }
            val msg = if (error.message?.contains("404") == true || error.message?.contains("403") == true) {
                "流媒体链接已过期，请重新打开网页后立即下载"
            } else {
                error.message ?: "流媒体下载失败"
            }
            store.updateDownload(recordId, "失败", errorMessage = msg)
            Result.failure()
        }
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val intent = LibraryActivity.createIntent(applicationContext, LibraryMode.DOWNLOADS)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (progress in 1..99) "下载中 $progress%" else if (progress == 100) "下载完成" else "准备下载")
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(progress < 100)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val notification = builder.build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val CHANNEL_ID = "aurora_downloads"
        const val NOTIFICATION_ID = 1001
        const val KEY_RECORD_ID = "record_id"
        const val KEY_URL = "playlist_url"
        const val KEY_TITLE = "title"
        const val KEY_REFERER = "referer"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_COOKIE = "cookie"
        const val KEY_ORIGIN = "origin"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_OUTPUT_SUBDIRECTORY = "output_subdirectory"
        const val KEY_CACHED_PLAYLIST = "cached_playlist"
        const val KEY_PAGE_URL = "page_url"
        const val KEY_SEGMENT_CONCURRENCY = "segment_concurrency"
        const val KEY_IS_PRIVATE = "is_private"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "下载任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示下载任务进度"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
