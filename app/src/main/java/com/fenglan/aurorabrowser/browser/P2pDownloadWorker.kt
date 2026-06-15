package com.fenglan.aurorabrowser.browser

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.fenglan.aurorabrowser.LibraryActivity
import com.fenglan.aurorabrowser.R
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.model.LibraryMode
import com.frostwire.jlibtorrent.AnnounceEntry
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionHandle
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.settings_pack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class P2pDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val store = BrowserStore.getInstance(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "P2P_${System.currentTimeMillis()}"
        val outputSubdirectory = inputData.getString(KEY_OUTPUT_SUBDIRECTORY) ?: "Aurora"
        val isPrivate = inputData.getBoolean(KEY_IS_PRIVATE, false)
        val referer = inputData.getString(KEY_REFERER)?.takeIf { it.isNotBlank() }
        val userAgent = inputData.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }
        val cookieHeader = inputData.getString(KEY_COOKIE)?.takeIf { it.isNotBlank() }
        if (recordId <= 0) {
            return@withContext Result.failure()
        }
        val outputDir = p2pOutputDir(applicationContext, outputSubdirectory, title, isPrivate)
        outputDir.mkdirs()
        setForeground(createForegroundInfo(title, 0, "准备 P2P 下载"))

        val session = SessionManager()
        try {
            store.updateDownload(recordId, "下载中", localUri = Uri.fromFile(outputDir).toString(), progressPercent = 0)
            startP2pSession(session)
            val magnet = TorrentTools.extractValidMagnet(sourceUrl)
            val infoHash = if (magnet != null) {
                val hash = TorrentTools.extractInfoHash(magnet)
                val cachedTorrent = hash?.let { resolveCachedTorrentFile(it, recordId) }
                if (cachedTorrent != null) {
                    val info = TorrentInfo(cachedTorrent)
                    session.download(info, outputDir)
                    info.infoHash()
                } else {
                    session.download(TorrentTools.withPublicTrackers(magnet), outputDir)
                    hash?.let(::Sha1Hash)
                }
            } else {
                val torrentFile = resolveTorrentFile(sourceUrl, recordId, referer, userAgent, cookieHeader)
                val info = TorrentInfo(torrentFile)
                session.download(info, outputDir)
                info.infoHash()
            }

            var lastName = title
            var trackersAdded = false
            var lastPeerRefreshAt = 0L
            var lastStoredProgress = -1
            var lastStoredDownloaded = -1L
            var lastStoredStateText = ""
            var lastStoredAt = 0L
            while (!isStopped && store.getDownloadStatus(recordId) !in setOf("已暂停", "已取消")) {
                val handle = findActiveTorrent(session, infoHash)
                if (handle == null) {
                    val dhtText = dhtStatusText(session)
                    val waitingText = "等待元数据 · $dhtText"
                    val nowElapsed = SystemClock.elapsedRealtime()
                    if (waitingText != lastStoredStateText || nowElapsed - lastStoredAt >= PROGRESS_STORE_INTERVAL_MS) {
                        store.updateDownload(
                            id = recordId,
                            status = "下载中",
                            localUri = Uri.fromFile(outputDir).toString(),
                            progressPercent = 0,
                            errorMessage = waitingText,
                        )
                        lastStoredStateText = waitingText
                        lastStoredAt = nowElapsed
                    }
                    setForeground(createForegroundInfo(lastName, 0, "等待元数据 · $dhtText"))
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                if (!trackersAdded) {
                    addPublicTrackers(handle)
                    forcePeerDiscovery(session, handle, infoHash)
                    trackersAdded = true
                    lastPeerRefreshAt = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()
                if (now - lastPeerRefreshAt >= PEER_REFRESH_INTERVAL_MS) {
                    forcePeerDiscovery(session, handle, infoHash)
                    lastPeerRefreshAt = now
                }

                val status = handle.status()

                val progress = (status.progress() * 100f).toInt().coerceIn(0, 99)
                val downloaded = status.totalWantedDone().takeIf { it > 0 } ?: status.totalDone()
                val total = status.totalWanted().takeIf { it > 0 }
                lastName = status.name().takeIf { it.isNotBlank() } ?: lastName
                val downloadRate = maxOf(
                    status.downloadRate().toLong(),
                    status.downloadPayloadRate().toLong(),
                    session.downloadRate(),
                )
                val speedText = formatSpeed(downloadRate)
                val trackerText = status.currentTracker()
                    .takeIf { it.isNotBlank() }
                    ?.let { "tracker: ${it.substringAfter("://", it).substringBefore('/').take(36)}" }
                val stateText = listOf(
                    status.state().name.lowercase().replace('_', ' '),
                    "${status.numSeeds()} 种子/${status.numPeers()} 节点/${status.numConnections()} 连接",
                    dhtStatusText(session),
                    speedText,
                    trackerText,
                ).filterNotNull().joinToString(" · ")

                val nowElapsed = SystemClock.elapsedRealtime()
                val progressChanged = progress != lastStoredProgress
                val bytesChangedEnough = downloaded - lastStoredDownloaded >= PROGRESS_STORE_BYTES_STEP || lastStoredDownloaded < 0L
                val stateChanged = stateText != lastStoredStateText
                val timeElapsed = nowElapsed - lastStoredAt >= PROGRESS_STORE_INTERVAL_MS
                if (progressChanged || bytesChangedEnough || stateChanged && timeElapsed || timeElapsed) {
                    store.updateDownloadResult(
                        id = recordId,
                        title = lastName,
                        mimeType = "application/x-bittorrent",
                        status = "下载中",
                        localUri = Uri.fromFile(outputDir).toString(),
                        downloadedBytes = downloaded,
                        fileSizeBytes = total,
                        progressPercent = progress,
                        errorMessage = stateText,
                    )
                    lastStoredProgress = progress
                    lastStoredDownloaded = downloaded
                    lastStoredStateText = stateText
                    lastStoredAt = nowElapsed
                }
                setForeground(createForegroundInfo(lastName, progress, stateText))
                if (status.isFinished() || status.isSeeding() || progress >= 99 && total != null && downloaded >= total) {
                    store.updateDownloadResult(
                        id = recordId,
                        title = lastName,
                        mimeType = "application/x-bittorrent",
                        status = "已完成",
                        localUri = Uri.fromFile(outputDir).toString(),
                        downloadedBytes = downloaded,
                        fileSizeBytes = total ?: downloaded,
                        progressPercent = 100,
                        errorMessage = "P2P 下载完成 · ${status.numSeeds()} 种子/${status.numPeers()} 节点",
                    )
                    setForeground(createForegroundInfo(lastName, 100, "P2P 下载完成"))
                    return@withContext Result.success()
                }
                delay(POLL_INTERVAL_MS)
            }

            if (store.getDownloadStatus(recordId) in setOf("已暂停", "已取消")) {
                Result.success()
            } else {
                store.updateDownload(recordId, "失败", localUri = Uri.fromFile(outputDir).toString(), errorMessage = "P2P 下载已停止")
                Result.failure()
            }
        } catch (error: Throwable) {
            if (store.getDownloadStatus(recordId) in setOf("已暂停", "已取消")) {
                return@withContext Result.success()
            }
            store.updateDownload(
                id = recordId,
                status = "失败",
                localUri = Uri.fromFile(outputDir).toString(),
                errorMessage = error.message ?: "P2P 下载失败",
            )
            Result.failure()
        } finally {
            runCatching { session.stop() }
        }
    }

    private fun startP2pSession(session: SessionManager) {
        val settings = SettingsPack()
            .downloadRateLimit(0)
            .uploadRateLimit(0)
            .activeDownloads(4)
            .activeSeeds(2)
            .activeLimit(8)
            .activeDhtLimit(600)
            .activeTrackerLimit(160)
            .activeLsdLimit(80)
            .connectionsLimit(500)
            .maxPeerlistSize(8_000)
            .maxQueuedDiskBytes(4 * 1024 * 1024)
            .sendBufferWatermark(2 * 1024 * 1024)
            .cacheSize(2_048)
            .tickInterval(500)
            .inactivityTimeout(180)
            .broadcastLSD(true)
            .enableDht(true)
            .seedingOutgoingConnections(true)
            .upnpIgnoreNonRouters(true)
            .listenInterfaces(LISTEN_INTERFACES)
            .alertQueueSize(2_000)

        settings.setString(settings_pack.string_types.user_agent.swigValue(), LIBTORRENT_USER_AGENT)
        settings.setString(settings_pack.string_types.handshake_client_version.swigValue(), LIBTORRENT_CLIENT_VERSION)
        settings.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP_NODES)
        settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.use_dht_as_fallback.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.always_send_user_agent.swigValue(), true)

        session.start(SessionParams(settings))
        runCatching { session.maxConnections(500) }
        runCatching { session.maxPeers(250) }
        runCatching { session.downloadRateLimit(0) }
        runCatching { session.uploadRateLimit(0) }
        runCatching { session.startDht() }
        runCatching { session.reopenNetworkSockets() }
    }

    private fun findActiveTorrent(session: SessionManager, infoHash: Sha1Hash?): TorrentHandle? {
        val exact = infoHash
            ?.let { runCatching { session.find(it) }.getOrNull() }
            ?.takeIf { it.isValid() }
        if (exact != null) {
            return exact
        }
        return runCatching {
            SessionHandle(session.swig()).torrents().firstOrNull { it.isValid() }
        }.getOrNull()
    }

    private fun addPublicTrackers(handle: TorrentHandle) {
        val existing = runCatching {
            handle.trackers().map { it.url().lowercase() }.toSet()
        }.getOrDefault(emptySet())
        TorrentTools.publicTrackers().forEach { tracker ->
            if (tracker.lowercase() !in existing) {
                runCatching { handle.addTracker(AnnounceEntry(tracker)) }
            }
        }
        runCatching { handle.setDownloadLimit(0) }
        runCatching { handle.setUploadLimit(0) }
    }

    private fun forcePeerDiscovery(session: SessionManager, handle: TorrentHandle, infoHash: Sha1Hash?) {
        runCatching { handle.forceReannounce(0, 0, TorrentHandle.IGNORE_MIN_INTERVAL) }
        runCatching { handle.forceDHTAnnounce() }
        runCatching { handle.scrapeTracker() }
        if (infoHash != null) {
            runCatching { session.dhtAnnounce(infoHash) }
        }
    }

    private fun dhtStatusText(session: SessionManager): String {
        val state = if (runCatching { session.isDhtRunning() }.getOrDefault(false)) "DHT" else "DHT未启动"
        val nodes = runCatching { session.dhtNodes() }.getOrDefault(0L)
        return "$state $nodes 节点"
    }

    private fun resolveTorrentFile(
        sourceUrl: String,
        recordId: Long,
        referer: String?,
        userAgent: String?,
        cookieHeader: String?,
    ): File {
        val uri = Uri.parse(sourceUrl)
        val output = File(applicationContext.cacheDir, "torrent_files/p2p_$recordId.torrent").apply {
            parentFile?.mkdirs()
        }
        when (uri.scheme?.lowercase()) {
            "content" -> applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                output.outputStream().use(input::copyTo)
            } ?: error("无法读取种子文件")
            "file" -> File(requireNotNull(uri.path)).copyTo(output, overwrite = true)
            "http", "https" -> {
                val connection = URL(sourceUrl).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", userAgent ?: DEFAULT_USER_AGENT)
                    connection.setRequestProperty("Accept", "application/x-bittorrent,*/*;q=0.8")
                    if (!referer.isNullOrBlank()) {
                        connection.setRequestProperty("Referer", referer)
                    }
                    if (!cookieHeader.isNullOrBlank()) {
                        connection.setRequestProperty("Cookie", cookieHeader)
                    }
                    connection.inputStream.use { input ->
                        output.outputStream().use(input::copyTo)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            else -> error("不支持的种子来源")
        }
        return output
    }

    private fun resolveCachedTorrentFile(infoHash: String, recordId: Long): File? {
        val output = File(applicationContext.cacheDir, "torrent_files/cache_${recordId}_${infoHash}.torrent").apply {
            parentFile?.mkdirs()
        }
        TorrentTools.torrentCacheUrls(infoHash).take(TORRENT_CACHE_LOOKUP_LIMIT).forEach { cacheUrl ->
            if (downloadTorrentCache(cacheUrl, output)) {
                val valid = runCatching { TorrentInfo(output) }.isSuccess
                if (valid) {
                    return output
                }
                output.delete()
            }
        }
        return null
    }

    private fun downloadTorrentCache(cacheUrl: String, output: File): Boolean {
        val temp = File(output.parentFile, "${output.name}.tmp")
        val connection = runCatching { URL(cacheUrl).openConnection() as HttpURLConnection }.getOrNull() ?: return false
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = TORRENT_CACHE_TIMEOUT_MS
            connection.readTimeout = TORRENT_CACHE_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            connection.setRequestProperty("Accept", "application/x-bittorrent,*/*;q=0.8")
            if (connection.responseCode !in 200..299) {
                return false
            }
            connection.inputStream.use { input ->
                temp.outputStream().use(input::copyTo)
            }
            if (temp.length() < MIN_TORRENT_FILE_BYTES || !looksLikeTorrentFile(temp)) {
                temp.delete()
                return false
            }
            temp.copyTo(output, overwrite = true)
            temp.delete()
            true
        } catch (_: Throwable) {
            temp.delete()
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeTorrentFile(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                input.read() == 'd'.code
            }
        }.getOrDefault(false)
    }

    private fun createForegroundInfo(title: String, progress: Int, message: String): ForegroundInfo {
        HlsDownloadWorker.createChannel(applicationContext)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            recordNotificationId(),
            LibraryActivity.createIntent(applicationContext, LibraryMode.DOWNLOADS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(applicationContext, HlsDownloadWorker.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(progress < 100)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return ForegroundInfo(recordNotificationId(), builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun recordNotificationId(): Int {
        val recordId = inputData.getLong(KEY_RECORD_ID, 0L)
        return (NOTIFICATION_ID_BASE + recordId).toInt()
    }

    companion object {
        const val KEY_RECORD_ID = "record_id"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_TITLE = "title"
        const val KEY_OUTPUT_SUBDIRECTORY = "output_subdirectory"
        const val KEY_IS_PRIVATE = "is_private"
        const val KEY_REFERER = "referer"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_COOKIE = "cookie"
        private const val NOTIFICATION_ID_BASE = 3000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val POLL_INTERVAL_MS = 1_500L
        private const val PEER_REFRESH_INTERVAL_MS = 45_000L
        private const val PROGRESS_STORE_INTERVAL_MS = 2_500L
        private const val PROGRESS_STORE_BYTES_STEP = 512L * 1024L
        private const val TORRENT_CACHE_TIMEOUT_MS = 4_000
        private const val TORRENT_CACHE_LOOKUP_LIMIT = 4
        private const val MIN_TORRENT_FILE_BYTES = 64L
        private const val LISTEN_INTERFACES = "0.0.0.0:6881,[::]:6881"
        private const val DHT_BOOTSTRAP_NODES =
            "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.libtorrent.org:25401"
        private const val LIBTORRENT_USER_AGENT = "AuroraBrowser/0.1 libtorrent/1.2"
        private const val LIBTORRENT_CLIENT_VERSION = "AuroraBrowser/0.1"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36"

        fun p2pOutputDir(context: Context, subdirectory: String, title: String, isPrivate: Boolean = false): File {
            return if (isPrivate) {
                val base = BrowserDownloads.getPrivateDir(context, subdirectory)
                File(base, "P2P/${ResourceTools.sanitizeFileName(title)}")
            } else {
                val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads")
                File(File(root, subdirectory), "P2P/${ResourceTools.sanitizeFileName(title)}")
            }
        }

        private fun formatSpeed(bytesPerSecond: Long): String {
            return when {
                bytesPerSecond >= 1_000_000L -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
                bytesPerSecond >= 1_000L -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1_000.0)
                bytesPerSecond > 0 -> "${bytesPerSecond} B/s"
                else -> "--"
            }
        }
    }
}
