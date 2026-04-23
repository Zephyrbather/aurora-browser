package com.fenglan.aurorabrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fenglan.aurorabrowser.model.BookmarkItem
import com.fenglan.aurorabrowser.model.DEFAULT_HOME_URL
import com.fenglan.aurorabrowser.model.DownloadItem
import com.fenglan.aurorabrowser.model.FileCategory
import com.fenglan.aurorabrowser.model.HomeSiteItem
import com.fenglan.aurorabrowser.model.HistoryItem
import com.fenglan.aurorabrowser.model.ResourceItem
import com.fenglan.aurorabrowser.model.SavedCredentialItem
import com.fenglan.aurorabrowser.model.TopSiteItem
import java.net.URI

class BrowserStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_BOOKMARKS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT,
                folder_id INTEGER NOT NULL DEFAULT 0,
                is_folder INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_folder_id ON $TABLE_BOOKMARKS(folder_id)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                visited_at INTEGER NOT NULL,
                visit_count INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_RESOURCES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                page_url TEXT,
                mime_type TEXT,
                referer TEXT,
                user_agent TEXT,
                category TEXT NOT NULL,
                is_stream INTEGER NOT NULL,
                captured_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_DOWNLOADS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                source_url TEXT NOT NULL,
                page_url TEXT,
                mime_type TEXT,
                category TEXT NOT NULL,
                download_type TEXT NOT NULL,
                system_download_id INTEGER,
                local_uri TEXT,
                status TEXT NOT NULL,
                downloaded_bytes INTEGER,
                file_size_bytes INTEGER,
                progress_percent INTEGER,
                error_message TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_HOME_SITES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        createCredentialsTable(db)
        createPerformanceIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching {
                db.execSQL(
                    """
                    ALTER TABLE $TABLE_HISTORY
                    ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 1
                    """.trimIndent(),
                )
            }
        }
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_HOME_SITES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    url TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 4) {
            runCatching {
                db.execSQL(
                    """
                    ALTER TABLE $TABLE_DOWNLOADS
                    ADD COLUMN downloaded_bytes INTEGER
                    """.trimIndent(),
                )
            }
            runCatching {
                db.execSQL(
                    """
                    ALTER TABLE $TABLE_DOWNLOADS
                    ADD COLUMN progress_percent INTEGER
                    """.trimIndent(),
                )
            }
        }
        if (oldVersion < 5) {
            migrateBookmarksTable(db)
        }
        if (oldVersion < 6) {
            createCredentialsTable(db)
        }
        if (oldVersion < 7) {
            createPerformanceIndexes(db)
        }
    }

    fun getBookmarks(folderId: Long = ROOT_BOOKMARK_FOLDER_ID): List<BookmarkItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_BOOKMARKS,
            null,
            "folder_id = ?",
            arrayOf(folderId.toString()),
            null,
            null,
            "is_folder DESC, created_at DESC, title COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        BookmarkItem(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                            folderId = readLongOrNull(cursor, "folder_id") ?: ROOT_BOOKMARK_FOLDER_ID,
                            isFolder = readIntOrNull(cursor, "is_folder") == 1,
                        ),
                    )
                }
            }
        }
    }

    fun getBookmarkChildCounts(folderIds: Collection<Long>): Map<Long, Int> = synchronized(this) {
        val ids = folderIds.distinct().filter { it != ROOT_BOOKMARK_FOLDER_ID }
        if (ids.isEmpty()) {
            return@synchronized emptyMap()
        }
        val placeholders = ids.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            """
            SELECT folder_id, COUNT(*) AS item_count
            FROM $TABLE_BOOKMARKS
            WHERE folder_id IN ($placeholders)
            GROUP BY folder_id
            """.trimIndent(),
            ids.map { it.toString() }.toTypedArray(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getLong(cursor.getColumnIndexOrThrow("folder_id")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("item_count")),
                    )
                }
            }
        }
    }

    fun isBookmarked(url: String): Boolean = synchronized(this) {
        readableDatabase.query(
            TABLE_BOOKMARKS,
            arrayOf("id"),
            "url = ? AND is_folder = 0",
            arrayOf(url),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    fun getBookmarkByUrl(url: String): BookmarkItem? = synchronized(this) {
        readableDatabase.query(
            TABLE_BOOKMARKS,
            null,
            "url = ? AND is_folder = 0",
            arrayOf(url),
            null,
            null,
            "created_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return@synchronized null
            }
            BookmarkItem(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                folderId = readLongOrNull(cursor, "folder_id") ?: ROOT_BOOKMARK_FOLDER_ID,
                isFolder = readIntOrNull(cursor, "is_folder") == 1,
            )
        }
    }

    fun getBookmarkFolders(parentId: Long = ROOT_BOOKMARK_FOLDER_ID): List<BookmarkItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_BOOKMARKS,
            null,
            "folder_id = ? AND is_folder = 1",
            arrayOf(parentId.toString()),
            null,
            null,
            "created_at DESC, title COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        BookmarkItem(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                            folderId = readLongOrNull(cursor, "folder_id") ?: ROOT_BOOKMARK_FOLDER_ID,
                            isFolder = true,
                        ),
                    )
                }
            }
        }
    }

    fun getAllBookmarkFolders(): List<BookmarkItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_BOOKMARKS,
            null,
            "is_folder = 1",
            null,
            null,
            null,
            "folder_id ASC, created_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        BookmarkItem(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                            folderId = readLongOrNull(cursor, "folder_id") ?: ROOT_BOOKMARK_FOLDER_ID,
                            isFolder = true,
                        ),
                    )
                }
            }
        }
    }

    fun getBookmarkPath(folderId: Long): List<BookmarkItem> = synchronized(this) {
        if (folderId == ROOT_BOOKMARK_FOLDER_ID) {
            return@synchronized emptyList()
        }
        val foldersById = getAllBookmarkFolders().associateBy { it.id }
        buildList {
            var cursorId = folderId
            while (cursorId != ROOT_BOOKMARK_FOLDER_ID) {
                val node = foldersById[cursorId] ?: break
                add(0, node)
                cursorId = node.folderId
            }
        }
    }

    fun createBookmarkFolder(title: String, parentId: Long): BookmarkItem? = synchronized(this) {
        val normalizedTitle = title.trim().ifBlank { "新建文件夹" }
        readableDatabase.query(
            TABLE_BOOKMARKS,
            null,
            "folder_id = ? AND is_folder = 1 AND title = ?",
            arrayOf(parentId.toString(), normalizedTitle),
            null,
            null,
            null,
            "1",
        ).use { existing ->
            if (existing.moveToFirst()) {
                return@synchronized BookmarkItem(
                    id = existing.getLong(existing.getColumnIndexOrThrow("id")),
                    title = existing.getString(existing.getColumnIndexOrThrow("title")),
                    url = existing.getString(existing.getColumnIndexOrThrow("url")),
                    createdAt = existing.getLong(existing.getColumnIndexOrThrow("created_at")),
                    folderId = readLongOrNull(existing, "folder_id") ?: ROOT_BOOKMARK_FOLDER_ID,
                    isFolder = true,
                )
            }
        }
        val createdAt = System.currentTimeMillis()
        val id = writableDatabase.insert(
            TABLE_BOOKMARKS,
            null,
            ContentValues().apply {
                put("title", normalizedTitle)
                put("url", "")
                put("folder_id", parentId)
                put("is_folder", 1)
                put("created_at", createdAt)
            },
        )
        return@synchronized BookmarkItem(
            id = id,
            title = normalizedTitle,
            url = "",
            createdAt = createdAt,
            folderId = parentId,
            isFolder = true,
        )
    }

    fun upsertBookmark(title: String, url: String, folderId: Long): BookmarkItem? = synchronized(this) {
        val normalizedTitle = title.ifBlank { url }.trim().ifBlank { url }
        val createdAt = System.currentTimeMillis()
        val existing = getBookmarkByUrl(url)
        val bookmarkId = existing?.id ?: writableDatabase.insert(
            TABLE_BOOKMARKS,
            null,
            ContentValues().apply {
                put("title", normalizedTitle)
                put("url", url)
                put("folder_id", folderId)
                put("is_folder", 0)
                put("created_at", createdAt)
            },
        )
        writableDatabase.update(
            TABLE_BOOKMARKS,
            ContentValues().apply {
                put("title", normalizedTitle)
                put("url", url)
                put("folder_id", folderId)
                put("is_folder", 0)
                put("created_at", existing?.createdAt ?: createdAt)
            },
            "id = ?",
            arrayOf(bookmarkId.toString()),
        )
        return getBookmarkById(bookmarkId)
    }

    fun renameBookmarkFolder(id: Long, title: String) = synchronized(this) {
        writableDatabase.update(
            TABLE_BOOKMARKS,
            ContentValues().apply {
                put("title", title.trim().ifBlank { "新建文件夹" })
            },
            "id = ? AND is_folder = 1",
            arrayOf(id.toString()),
        )
    }

    fun deleteBookmark(id: Long) = synchronized(this) {
        val item = getBookmarkById(id) ?: return@synchronized
        if (item.isFolder) {
            deleteBookmarkFolderRecursive(id)
        } else {
            writableDatabase.delete(TABLE_BOOKMARKS, "id = ?", arrayOf(id.toString()))
        }
    }

    fun deleteBookmarkByUrl(url: String) = synchronized(this) {
        writableDatabase.delete(TABLE_BOOKMARKS, "url = ? AND is_folder = 0", arrayOf(url))
    }

    fun getBookmarkParentId(folderId: Long): Long = synchronized(this) {
        if (folderId == ROOT_BOOKMARK_FOLDER_ID) {
            return@synchronized ROOT_BOOKMARK_FOLDER_ID
        }
        readableDatabase.query(
            TABLE_BOOKMARKS,
            arrayOf("folder_id"),
            "id = ? AND is_folder = 1",
            arrayOf(folderId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return@synchronized ROOT_BOOKMARK_FOLDER_ID
            }
            readLongOrNull(cursor, "folder_id") ?: ROOT_BOOKMARK_FOLDER_ID
        }
    }

    fun addHistory(title: String, url: String) = synchronized(this) {
        if (url.isBlank() || url == DEFAULT_HOME_URL) {
            return@synchronized
        }
        val now = System.currentTimeMillis()
        readableDatabase.query(
            TABLE_HISTORY,
            arrayOf("id", "visit_count"),
            "url = ?",
            arrayOf(url),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val visitCountColumn = cursor.getColumnIndex("visit_count")
                val visitCount = if (visitCountColumn >= 0 && !cursor.isNull(visitCountColumn)) {
                    cursor.getInt(visitCountColumn)
                } else {
                    1
                }
                writableDatabase.update(
                    TABLE_HISTORY,
                    ContentValues().apply {
                        put("title", title.ifBlank { url })
                        put("visited_at", now)
                        put("visit_count", visitCount + 1)
                    },
                    "id = ?",
                    arrayOf(id.toString()),
                )
            } else {
                writableDatabase.insert(
                    TABLE_HISTORY,
                    null,
                    ContentValues().apply {
                        put("title", title.ifBlank { url })
                        put("url", url)
                        put("visited_at", now)
                        put("visit_count", 1)
                    },
                )
            }
        }
    }

    fun getHistory(): List<HistoryItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_HISTORY,
            arrayOf("id", "title", "url", "visited_at"),
            "url != ?",
            arrayOf(DEFAULT_HOME_URL),
            null,
            null,
            "visited_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HistoryItem(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                            visitedAt = cursor.getLong(cursor.getColumnIndexOrThrow("visited_at")),
                        ),
                    )
                }
            }
        }
    }

    fun deleteHistory(id: Long) = synchronized(this) {
        writableDatabase.delete(TABLE_HISTORY, "id = ?", arrayOf(id.toString()))
    }

    fun clearHistory() = synchronized(this) {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }

    fun getTopSites(limit: Int = 18): List<TopSiteItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_HISTORY,
            arrayOf("title", "url", "visited_at", "visit_count"),
            "url != ?",
            arrayOf(DEFAULT_HOME_URL),
            null,
            null,
            "visited_at DESC",
        ).use { cursor ->
            val sitesByKey = linkedMapOf<String, TopSiteAggregate>()
            while (cursor.moveToNext()) {
                val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
                val url = cursor.getString(cursor.getColumnIndexOrThrow("url"))
                val visitedAt = cursor.getLong(cursor.getColumnIndexOrThrow("visited_at"))
                val visitCount = cursor.getInt(cursor.getColumnIndexOrThrow("visit_count"))
                val siteKey = siteKeyForUrl(url)
                val existing = sitesByKey[siteKey]
                if (existing == null) {
                    sitesByKey[siteKey] = TopSiteAggregate(
                        title = title,
                        url = url,
                        visitCount = visitCount,
                        visitedAt = visitedAt,
                    )
                } else {
                    sitesByKey[siteKey] = existing.copy(
                        visitCount = existing.visitCount + visitCount,
                    )
                }
            }
            sitesByKey.values
                .sortedWith(
                    compareByDescending<TopSiteAggregate> { it.visitCount }
                        .thenByDescending { it.visitedAt },
                )
                .take(limit.coerceAtLeast(1))
                .map {
                    TopSiteItem(
                        title = it.title,
                        url = it.url,
                        visitCount = it.visitCount,
                        visitedAt = it.visitedAt,
                    )
                }
        }
    }

    fun getHomeSites(): List<HomeSiteItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_HOME_SITES,
            null,
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HomeSiteItem(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        ),
                    )
                }
            }
        }
    }

    fun upsertHomeSite(title: String, url: String) = synchronized(this) {
        writableDatabase.insertWithOnConflict(
            TABLE_HOME_SITES,
            null,
            ContentValues().apply {
                put("title", title.ifBlank { url })
                put("url", url)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteHomeSite(id: Long) = synchronized(this) {
        writableDatabase.delete(TABLE_HOME_SITES, "id = ?", arrayOf(id.toString()))
    }

    fun upsertResource(
        title: String,
        url: String,
        pageUrl: String?,
        mimeType: String?,
        referer: String?,
        userAgent: String?,
        category: FileCategory,
        isStream: Boolean,
    ) = synchronized(this) {
        writableDatabase.insertWithOnConflict(
            TABLE_RESOURCES,
            null,
            ContentValues().apply {
                put("title", title.ifBlank { url })
                put("url", url)
                put("page_url", pageUrl)
                put("mime_type", mimeType)
                put("referer", referer)
                put("user_agent", userAgent)
                put("category", category.value)
                put("is_stream", if (isStream) 1 else 0)
                put("captured_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getResources(): List<ResourceItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_RESOURCES,
            null,
            null,
            null,
            null,
            null,
            "captured_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ResourceItem(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                            pageUrl = cursor.getString(cursor.getColumnIndexOrThrow("page_url")),
                            mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                            referer = cursor.getString(cursor.getColumnIndexOrThrow("referer")),
                            userAgent = cursor.getString(cursor.getColumnIndexOrThrow("user_agent")),
                            category = FileCategory.fromValue(cursor.getString(cursor.getColumnIndexOrThrow("category"))),
                            isStream = cursor.getInt(cursor.getColumnIndexOrThrow("is_stream")) == 1,
                            capturedAt = cursor.getLong(cursor.getColumnIndexOrThrow("captured_at")),
                        ),
                    )
                }
            }
        }
    }

    fun getResourcesForPage(pageUrl: String): List<ResourceItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_RESOURCES,
            null,
            "page_url = ?",
            arrayOf(pageUrl),
            null,
            null,
            "captured_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ResourceItem(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                            url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                            pageUrl = cursor.getString(cursor.getColumnIndexOrThrow("page_url")),
                            mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                            referer = cursor.getString(cursor.getColumnIndexOrThrow("referer")),
                            userAgent = cursor.getString(cursor.getColumnIndexOrThrow("user_agent")),
                            category = FileCategory.fromValue(cursor.getString(cursor.getColumnIndexOrThrow("category"))),
                            isStream = cursor.getInt(cursor.getColumnIndexOrThrow("is_stream")) == 1,
                            capturedAt = cursor.getLong(cursor.getColumnIndexOrThrow("captured_at")),
                        ),
                    )
                }
            }
        }
    }

    fun getResourceByUrl(url: String): ResourceItem? = synchronized(this) {
        readableDatabase.query(
            TABLE_RESOURCES,
            null,
            "url = ?",
            arrayOf(url),
            null,
            null,
            "captured_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return@synchronized null
            }
            ResourceItem(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                pageUrl = cursor.getString(cursor.getColumnIndexOrThrow("page_url")),
                mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                referer = cursor.getString(cursor.getColumnIndexOrThrow("referer")),
                userAgent = cursor.getString(cursor.getColumnIndexOrThrow("user_agent")),
                category = FileCategory.fromValue(cursor.getString(cursor.getColumnIndexOrThrow("category"))),
                isStream = cursor.getInt(cursor.getColumnIndexOrThrow("is_stream")) == 1,
                capturedAt = cursor.getLong(cursor.getColumnIndexOrThrow("captured_at")),
            )
        }
    }

    fun deleteResource(id: Long) = synchronized(this) {
        writableDatabase.delete(TABLE_RESOURCES, "id = ?", arrayOf(id.toString()))
    }

    fun clearResources() = synchronized(this) {
        writableDatabase.delete(TABLE_RESOURCES, null, null)
    }

    fun deleteResourcesForPage(pageUrl: String) = synchronized(this) {
        writableDatabase.delete(TABLE_RESOURCES, "page_url = ?", arrayOf(pageUrl))
    }

    fun insertDownload(
        title: String,
        sourceUrl: String,
        pageUrl: String?,
        mimeType: String?,
        category: FileCategory,
        downloadType: String,
        systemDownloadId: Long?,
        localUri: String?,
        status: String,
        downloadedBytes: Long?,
        fileSizeBytes: Long?,
        progressPercent: Int?,
        errorMessage: String?,
    ): Long = synchronized(this) {
        writableDatabase.insert(
            TABLE_DOWNLOADS,
            null,
            ContentValues().apply {
                put("title", title)
                put("source_url", sourceUrl)
                put("page_url", pageUrl)
                put("mime_type", mimeType)
                put("category", category.value)
                put("download_type", downloadType)
                put("system_download_id", systemDownloadId)
                put("local_uri", localUri)
                put("status", status)
                put("downloaded_bytes", downloadedBytes)
                put("file_size_bytes", fileSizeBytes)
                put("progress_percent", progressPercent)
                put("error_message", errorMessage)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    fun getDownloads(): List<DownloadItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_DOWNLOADS,
            null,
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(readDownload(cursor))
                }
            }
        }
    }

    fun getDownloadStatus(id: Long): String? = synchronized(this) {
        readableDatabase.query(
            TABLE_DOWNLOADS,
            arrayOf("status"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return@synchronized null
            }
            cursor.getString(cursor.getColumnIndexOrThrow("status"))
        }
    }

    fun updateDownload(
        id: Long,
        status: String,
        localUri: String? = null,
        downloadedBytes: Long? = null,
        fileSizeBytes: Long? = null,
        progressPercent: Int? = null,
        errorMessage: String? = null,
    ) = synchronized(this) {
        writableDatabase.update(
            TABLE_DOWNLOADS,
            ContentValues().apply {
                put("status", status)
                put("local_uri", localUri)
                put("downloaded_bytes", downloadedBytes)
                put("file_size_bytes", fileSizeBytes)
                put("progress_percent", progressPercent)
                put("error_message", errorMessage)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun updateDownloadResult(
        id: Long,
        title: String,
        mimeType: String?,
        status: String,
        localUri: String?,
        downloadedBytes: Long?,
        fileSizeBytes: Long?,
        progressPercent: Int?,
        errorMessage: String?,
    ) = synchronized(this) {
        writableDatabase.update(
            TABLE_DOWNLOADS,
            ContentValues().apply {
                put("title", title)
                put("mime_type", mimeType)
                put("status", status)
                put("local_uri", localUri)
                put("downloaded_bytes", downloadedBytes)
                put("file_size_bytes", fileSizeBytes)
                put("progress_percent", progressPercent)
                put("error_message", errorMessage)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun updateDownloadBySystemId(
        systemDownloadId: Long,
        status: String,
        localUri: String? = null,
        downloadedBytes: Long? = null,
        fileSizeBytes: Long? = null,
        progressPercent: Int? = null,
        errorMessage: String? = null,
    ) = synchronized(this) {
        writableDatabase.update(
            TABLE_DOWNLOADS,
            ContentValues().apply {
                put("status", status)
                put("local_uri", localUri)
                put("downloaded_bytes", downloadedBytes)
                put("file_size_bytes", fileSizeBytes)
                put("progress_percent", progressPercent)
                put("error_message", errorMessage)
            },
            "system_download_id = ?",
            arrayOf(systemDownloadId.toString()),
        )
    }

    fun updateDownloadRequest(
        id: Long,
        systemDownloadId: Long?,
        status: String,
        localUri: String? = null,
        downloadedBytes: Long? = null,
        fileSizeBytes: Long? = null,
        progressPercent: Int? = null,
        errorMessage: String? = null,
    ) = synchronized(this) {
        writableDatabase.update(
            TABLE_DOWNLOADS,
            ContentValues().apply {
                put("system_download_id", systemDownloadId)
                put("status", status)
                put("local_uri", localUri)
                put("downloaded_bytes", downloadedBytes)
                put("file_size_bytes", fileSizeBytes)
                put("progress_percent", progressPercent)
                put("error_message", errorMessage)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun deleteDownload(id: Long) = synchronized(this) {
        writableDatabase.delete(TABLE_DOWNLOADS, "id = ?", arrayOf(id.toString()))
    }

    fun clearDownloads() = synchronized(this) {
        writableDatabase.delete(TABLE_DOWNLOADS, null, null)
    }

    fun saveCredential(
        host: String,
        pageUrl: String?,
        username: String,
        password: String,
    ): SavedCredentialItem? = synchronized(this) {
        val normalizedHost = host.trim().lowercase()
        val normalizedUsername = username.trim()
        val normalizedPassword = password.trim()
        if (normalizedHost.isBlank() || normalizedUsername.isBlank() || normalizedPassword.isBlank()) {
            return@synchronized null
        }
        val now = System.currentTimeMillis()
        val existing = getCredentialByHostAndUsername(normalizedHost, normalizedUsername)
        val id = existing?.id ?: writableDatabase.insert(
            TABLE_CREDENTIALS,
            null,
            ContentValues().apply {
                put("host", normalizedHost)
                put("page_url", pageUrl)
                put("username", normalizedUsername)
                put("password", normalizedPassword)
                put("created_at", now)
                put("updated_at", now)
            },
        )
        writableDatabase.update(
            TABLE_CREDENTIALS,
            ContentValues().apply {
                put("host", normalizedHost)
                put("page_url", pageUrl)
                put("username", normalizedUsername)
                put("password", normalizedPassword)
                put("created_at", existing?.createdAt ?: now)
                put("updated_at", now)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
        return@synchronized getCredentialById(id)
    }

    fun getSavedCredentials(): List<SavedCredentialItem> = synchronized(this) {
        readableDatabase.query(
            TABLE_CREDENTIALS,
            null,
            null,
            null,
            null,
            null,
            "updated_at DESC, id DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    readCredential(cursor)?.let(::add)
                }
            }
        }
    }

    fun getCredentialsForHost(host: String): List<SavedCredentialItem> = synchronized(this) {
        val candidates = linkedSetOf<String>()
        val normalized = host.trim().lowercase().removePrefix("www.")
        if (normalized.isNotBlank()) {
            candidates += normalized
            candidates += "www.$normalized"
        }
        if (candidates.isEmpty()) {
            return@synchronized emptyList()
        }
        val placeholders = candidates.joinToString(",") { "?" }
        readableDatabase.query(
            TABLE_CREDENTIALS,
            null,
            "host IN ($placeholders)",
            candidates.toTypedArray(),
            null,
            null,
            "updated_at DESC, id DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    readCredential(cursor)?.let(::add)
                }
            }
        }
    }

    fun deleteCredential(id: Long) = synchronized(this) {
        writableDatabase.delete(TABLE_CREDENTIALS, "id = ?", arrayOf(id.toString()))
    }

    companion object {
        private const val DATABASE_NAME = "aurora_browser.db"
        private const val DATABASE_VERSION = 7

        private const val TABLE_BOOKMARKS = "bookmarks"
        private const val TABLE_HISTORY = "history"
        private const val TABLE_RESOURCES = "resources"
        private const val TABLE_DOWNLOADS = "downloads"
        private const val TABLE_HOME_SITES = "home_sites"
        private const val TABLE_CREDENTIALS = "credentials"
        const val ROOT_BOOKMARK_FOLDER_ID = 0L
        private val IPV4_HOST_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }

    private fun createPerformanceIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_visited_at ON $TABLE_HISTORY(visited_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_top_sites ON $TABLE_HISTORY(visit_count DESC, visited_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_resources_page_captured ON $TABLE_RESOURCES(page_url, captured_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_resources_captured_at ON $TABLE_RESOURCES(captured_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_created_at ON $TABLE_DOWNLOADS(created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_home_sites_created_at ON $TABLE_HOME_SITES(created_at DESC)")
    }

    private fun createCredentialsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CREDENTIALS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host TEXT NOT NULL,
                page_url TEXT,
                username TEXT NOT NULL,
                password TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_credentials_host_username ON $TABLE_CREDENTIALS(host, username)",
        )
    }

    private fun migrateBookmarksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ${TABLE_BOOKMARKS}_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT,
                folder_id INTEGER NOT NULL DEFAULT 0,
                is_folder INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO ${TABLE_BOOKMARKS}_new (id, title, url, folder_id, is_folder, created_at)
            SELECT id, title, url, 0, 0, created_at FROM $TABLE_BOOKMARKS
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE $TABLE_BOOKMARKS")
        db.execSQL("ALTER TABLE ${TABLE_BOOKMARKS}_new RENAME TO $TABLE_BOOKMARKS")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_folder_id ON $TABLE_BOOKMARKS(folder_id)")
    }

    private fun getBookmarkById(id: Long): BookmarkItem? {
        readableDatabase.query(
            TABLE_BOOKMARKS,
            null,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return BookmarkItem(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                folderId = readLongOrNull(cursor, "folder_id") ?: ROOT_BOOKMARK_FOLDER_ID,
                isFolder = readIntOrNull(cursor, "is_folder") == 1,
            )
        }
    }

    private fun deleteBookmarkFolderRecursive(folderId: Long) {
        val childIds = mutableListOf<Long>()
        readableDatabase.query(
            TABLE_BOOKMARKS,
            arrayOf("id", "is_folder"),
            "folder_id = ?",
            arrayOf(folderId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val isFolder = readIntOrNull(cursor, "is_folder") == 1
                if (isFolder) {
                    childIds += childId
                } else {
                    writableDatabase.delete(TABLE_BOOKMARKS, "id = ?", arrayOf(childId.toString()))
                }
            }
        }
        childIds.forEach(::deleteBookmarkFolderRecursive)
        writableDatabase.delete(TABLE_BOOKMARKS, "id = ?", arrayOf(folderId.toString()))
    }

    private fun getCredentialById(id: Long): SavedCredentialItem? {
        readableDatabase.query(
            TABLE_CREDENTIALS,
            null,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return readCredential(cursor)
        }
    }

    private fun getCredentialByHostAndUsername(host: String, username: String): SavedCredentialItem? {
        readableDatabase.query(
            TABLE_CREDENTIALS,
            null,
            "host = ? AND username = ?",
            arrayOf(host, username),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return readCredential(cursor)
        }
    }

    private fun readCredential(cursor: android.database.Cursor): SavedCredentialItem? {
        return SavedCredentialItem(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            host = cursor.getString(cursor.getColumnIndexOrThrow("host")),
            pageUrl = cursor.getString(cursor.getColumnIndexOrThrow("page_url")),
            username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
            password = cursor.getString(cursor.getColumnIndexOrThrow("password")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
        )
    }

    private fun readDownload(cursor: android.database.Cursor): DownloadItem {
        return DownloadItem(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            sourceUrl = cursor.getString(cursor.getColumnIndexOrThrow("source_url")),
            pageUrl = cursor.getString(cursor.getColumnIndexOrThrow("page_url")),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
            category = FileCategory.fromValue(cursor.getString(cursor.getColumnIndexOrThrow("category"))),
            downloadType = cursor.getString(cursor.getColumnIndexOrThrow("download_type")),
            systemDownloadId = if (cursor.isNull(cursor.getColumnIndexOrThrow("system_download_id"))) {
                null
            } else {
                cursor.getLong(cursor.getColumnIndexOrThrow("system_download_id"))
            },
            localUri = cursor.getString(cursor.getColumnIndexOrThrow("local_uri")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            downloadedBytes = readLongOrNull(cursor, "downloaded_bytes"),
            fileSizeBytes = readLongOrNull(cursor, "file_size_bytes"),
            progressPercent = readIntOrNull(cursor, "progress_percent"),
            errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error_message")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        )
    }

    private fun siteKeyForUrl(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()
            ?.trim()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.ifBlank { null }
        if (host == null) {
            return url
        }
        if (host == "localhost" || host.matches(IPV4_HOST_REGEX) || (host.startsWith("[") && host.endsWith("]"))) {
            return host
        }
        val parts = host.split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) {
            parts.takeLast(2).joinToString(".")
        } else {
            host
        }
    }

    private fun readLongOrNull(cursor: android.database.Cursor, columnName: String): Long? {
        val columnIndex = cursor.getColumnIndex(columnName)
        if (columnIndex == -1 || cursor.isNull(columnIndex)) {
            return null
        }
        return cursor.getLong(columnIndex)
    }

    private fun readIntOrNull(cursor: android.database.Cursor, columnName: String): Int? {
        val columnIndex = cursor.getColumnIndex(columnName)
        if (columnIndex == -1 || cursor.isNull(columnIndex)) {
            return null
        }
        return cursor.getInt(columnIndex)
    }

    private data class TopSiteAggregate(
        val title: String,
        val url: String,
        val visitCount: Int,
        val visitedAt: Long,
    )
}
