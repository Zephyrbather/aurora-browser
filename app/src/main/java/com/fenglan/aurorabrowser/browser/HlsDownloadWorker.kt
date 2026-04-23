package com.fenglan.aurorabrowser.browser

import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fenglan.aurorabrowser.data.BrowserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class HlsDownloadWorker(
    appContext: android.content.Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val store = BrowserStore(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
        val playlistUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "stream_${System.currentTimeMillis()}.m3u8"
        val headers = HlsPackageTools.RequestHeaders(
            referer = inputData.getString(KEY_REFERER),
            userAgent = inputData.getString(KEY_USER_AGENT),
            cookie = inputData.getString(KEY_COOKIE),
            origin = inputData.getString(KEY_ORIGIN),
        )
        val outputSubdirectory = inputData.getString(KEY_OUTPUT_SUBDIRECTORY) ?: "Aurora"

        if (recordId <= 0) {
            return@withContext Result.failure()
        }

        try {
            store.updateDownload(recordId, "下载中", progressPercent = 0)
            val downloaded = HlsPackageTools.downloadPackage(
                context = applicationContext,
                recordId = recordId,
                title = title,
                playlistUrl = playlistUrl,
                headers = headers,
                downloadSubdirectory = outputSubdirectory,
                shouldContinue = {
                    !isStopped && store.getDownloadStatus(recordId) !in setOf("已暂停", "已取消")
                },
            ) { playlistFile, downloadedBytes, done, total ->
                val progress = if (total <= 0) 0 else ((done * 100f) / total).toInt().coerceIn(0, 99)
                store.updateDownload(
                    id = recordId,
                    status = "下载中",
                    localUri = Uri.fromFile(playlistFile).toString(),
                    downloadedBytes = downloadedBytes,
                    fileSizeBytes = null,
                    progressPercent = progress,
                    errorMessage = "$done/$total 分片",
                )
                setProgressAsync(
                    androidx.work.Data.Builder()
                        .putInt("done", done)
                        .putInt("total", total)
                        .build(),
                )
            }
            store.updateDownloadResult(
                id = recordId,
                title = ResourceTools.ensureExtension(title.substringBeforeLast('.', title), "m3u8"),
                mimeType = "application/x-mpegURL",
                status = "已完成",
                localUri = Uri.fromFile(downloaded.playlistFile).toString(),
                downloadedBytes = downloaded.totalBytes,
                fileSizeBytes = downloaded.totalBytes,
                progressPercent = 100,
                errorMessage = "m3u8 · ${downloaded.segmentCount}段",
            )
            Result.success()
        } catch (error: Throwable) {
            if (store.getDownloadStatus(recordId) in setOf("已暂停", "已取消")) {
                return@withContext Result.success()
            }
            if (error is IOException && runAttemptCount < 2) {
                store.updateDownload(recordId, "重试中", progressPercent = null, errorMessage = error.message ?: "流媒体下载重试中")
                return@withContext Result.retry()
            }
            store.updateDownload(recordId, "失败", progressPercent = null, errorMessage = error.message ?: "流媒体下载失败")
            Result.failure()
        }
    }

    companion object {
        const val KEY_RECORD_ID = "record_id"
        const val KEY_URL = "playlist_url"
        const val KEY_TITLE = "title"
        const val KEY_REFERER = "referer"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_COOKIE = "cookie"
        const val KEY_ORIGIN = "origin"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_OUTPUT_SUBDIRECTORY = "output_subdirectory"
    }
}
