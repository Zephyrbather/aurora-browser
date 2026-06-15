package com.fenglan.aurorabrowser.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class PrivateDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val store = BrowserStore.getInstance(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
        val downloadUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "download_${System.currentTimeMillis()}"
        val referer = inputData.getString(KEY_REFERER)
        val userAgent = inputData.getString(KEY_USER_AGENT)
        val cookie = inputData.getString(KEY_COOKIE)
        val origin = inputData.getString(KEY_ORIGIN)
        val subdirectory = inputData.getString(KEY_OUTPUT_SUBDIRECTORY) ?: "Aurora"
        val declaredSize = inputData.getLong(KEY_DECLARED_SIZE, -1L).takeIf { it > 0 }

        if (recordId <= 0) return@withContext Result.failure()

        val notifManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(notifManager)
        setForeground(buildForegroundInfo(notifManager, title, 0))

        val outputDir = BrowserDownloads.getPrivateDir(applicationContext, subdirectory)
        val sanitized = BrowserDownloads.sanitizeFileName(title)
        val outputFile = File(outputDir, sanitized)
        outputDir.mkdirs()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                referer?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Referer", it) }
                userAgent?.takeIf { it.isNotBlank() }?.let { setRequestProperty("User-Agent", it) }
                cookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
                origin?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Origin", it) }
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                store.updateDownload(recordId, "失败", errorMessage = "HTTP ${connection.responseCode}")
                return@withContext Result.failure()
            }
            val totalBytes = connection.contentLength.toLong().takeIf { it > 0 } ?: declaredSize
            var downloaded = 0L
            var lastNotify = 0L
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 1000) {
                            lastNotify = now
                            val progress = if (totalBytes != null && totalBytes > 0) {
                                (downloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
                            } else null
                            store.updateDownload(
                                id = recordId,
                                status = "下载中",
                                downloadedBytes = downloaded,
                                fileSizeBytes = totalBytes,
                                progressPercent = progress,
                            )
                            setForeground(buildForegroundInfo(notifManager, title, progress ?: 0))
                        }
                    }
                }
            }
            val finalUri = android.net.Uri.fromFile(outputFile).toString()
            store.updateDownloadResult(
                id = recordId,
                title = title,
                mimeType = connection.contentType,
                status = "已完成",
                localUri = finalUri,
                downloadedBytes = downloaded,
                fileSizeBytes = totalBytes ?: downloaded,
                progressPercent = 100,
                errorMessage = null,
            )
            setForeground(buildForegroundInfo(notifManager, title, 100))
            Result.success()
        } catch (e: Exception) {
            val status = store.getDownloadStatus(recordId)
            if (status != "已取消") {
                store.updateDownload(recordId, "失败", errorMessage = e.message?.take(200))
            }
            outputFile.delete()
            Result.failure()
        } finally {
            connection?.disconnect()
        }
    }

    private fun ensureNotificationChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "隐私下载",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundInfo(manager: NotificationManager, title: String, progress: Int): ForegroundInfo {
        val intent = LibraryActivity.createIntent(applicationContext, LibraryMode.DOWNLOADS)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText("隐私下载中 $progress%")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "aurora_private_download"
        const val NOTIFICATION_ID = 0x50525644
        const val KEY_RECORD_ID = "record_id"
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_REFERER = "referer"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_COOKIE = "cookie"
        const val KEY_ORIGIN = "origin"
        const val KEY_OUTPUT_SUBDIRECTORY = "output_subdirectory"
        const val KEY_DECLARED_SIZE = "declared_size"
    }
}
