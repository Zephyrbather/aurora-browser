package com.fenglan.aurorabrowser.browser

import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fenglan.aurorabrowser.data.BrowserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class HlsExportWorker(
    appContext: android.content.Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val store = BrowserStore.getInstance(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
        val sourceUri = inputData.getString(KEY_SOURCE_URI)
        val title = inputData.getString(KEY_TITLE) ?: "stream_${System.currentTimeMillis()}.mp4"
        val outputSubdirectory = inputData.getString(KEY_OUTPUT_SUBDIRECTORY) ?: "Aurora"

        if (recordId <= 0 || sourceUri.isNullOrBlank()) {
            return@withContext Result.failure()
        }

        val playlistFile = Uri.parse(sourceUri).path?.let(::File)
        if (playlistFile == null || !playlistFile.exists()) {
            store.updateDownload(recordId, "失败", errorMessage = "源 m3u8 文件不存在")
            return@withContext Result.failure()
        }

        try {
            store.updateDownload(recordId, "转码中", progressPercent = 0, errorMessage = "准备转码")
            var lastProgressStage = ""
            var lastProgressPercent = -1
            var lastProgressUpdateAt = 0L
            val converted = HlsPackageTools.convertPackageToMp4(
                context = applicationContext,
                playlistFile = playlistFile,
                outputName = ResourceTools.ensureExtension(title.substringBeforeLast('.', title), "mp4"),
                downloadSubdirectory = outputSubdirectory,
                onProgress = { stage, processedBytes, totalBytes, progressPercent ->
                    val now = SystemClock.elapsedRealtime()
                    val stageChanged = stage != lastProgressStage
                    val percentChangedEnough = progressPercent - lastProgressPercent >= 2
                    val timeElapsedEnough = now - lastProgressUpdateAt >= 700L
                    if (progressPercent >= 100 || stageChanged || percentChangedEnough || timeElapsedEnough) {
                        lastProgressStage = stage
                        lastProgressPercent = progressPercent
                        lastProgressUpdateAt = now
                        store.updateDownload(
                            id = recordId,
                            status = "转码中",
                            downloadedBytes = processedBytes,
                            fileSizeBytes = totalBytes,
                            progressPercent = progressPercent,
                            errorMessage = stage,
                        )
                    }
                },
            )
            store.updateDownloadResult(
                id = recordId,
                title = ResourceTools.ensureExtension(title.substringBeforeLast('.', title), "mp4"),
                mimeType = "video/mp4",
                status = "已完成",
                localUri = converted.uri.toString(),
                downloadedBytes = converted.sizeBytes,
                fileSizeBytes = converted.sizeBytes,
                progressPercent = 100,
                errorMessage = "由 m3u8 转为 MP4",
            )
            HlsPackageTools.deletePackageForUri(sourceUri)
            val originalRecord = store.getDownloads().firstOrNull {
                it.sourceUrl == sourceUri && it.downloadType == "hls"
            }
            if (originalRecord != null) {
                store.deleteDownload(originalRecord.id)
            }
            Result.success()
        } catch (error: Throwable) {
            if (store.getDownloadStatus(recordId) == "已取消") {
                return@withContext Result.success()
            }
            if (error is IOException && runAttemptCount < 1) {
                store.updateDownload(recordId, "重试中", errorMessage = error.message ?: "转码重试中")
                return@withContext Result.retry()
            }
            store.updateDownload(recordId, "失败", errorMessage = error.message ?: "MP4 转码失败")
            Result.failure()
        }
    }

    companion object {
        const val KEY_RECORD_ID = "record_id"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_TITLE = "title"
        const val KEY_OUTPUT_SUBDIRECTORY = "output_subdirectory"
    }
}
