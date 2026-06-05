package com.fenglan.aurorabrowser

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.TextWatcher
import android.text.InputType
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.fenglan.aurorabrowser.browser.BrowserDownloads
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.browser.ResourceTools
import com.fenglan.aurorabrowser.browser.ThemeManager
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.databinding.ActivityLibraryBinding
import java.io.File
import com.fenglan.aurorabrowser.model.BookmarkItem
import com.fenglan.aurorabrowser.model.DownloadItem
import com.fenglan.aurorabrowser.model.FileCategory
import com.fenglan.aurorabrowser.model.HistoryItem
import com.fenglan.aurorabrowser.model.LibraryMode
import com.fenglan.aurorabrowser.model.ResourceItem
import com.fenglan.aurorabrowser.model.SearchHistoryItem
import com.fenglan.aurorabrowser.ui.LibraryAdapter
import com.fenglan.aurorabrowser.ui.LibraryRenderItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private val store by lazy { BrowserStore.getInstance(this) }
    private val preferences by lazy { BrowserPreferences(this) }
    private lateinit var mode: LibraryMode
    private lateinit var adapter: LibraryAdapter
    private val bookmarkImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importBookmarksFromHtml(it) }
    }

    private var bookmarks: List<BookmarkItem> = emptyList()
    private var historyItems: List<HistoryItem> = emptyList()
    private var searchHistoryItems: List<SearchHistoryItem> = emptyList()
    private var resources: List<ResourceItem> = emptyList()
    private var downloads: List<DownloadItem> = emptyList()
    private var visibleSearchHistoryItems: List<SearchHistoryItem> = emptyList()
    private var visibleBrowsingHistoryItems: List<HistoryItem> = emptyList()
    private var visibleResources: List<ResourceItem> = emptyList()
    private var visibleDownloads: List<DownloadItem> = emptyList()
    private var selectedCategory: FileCategory = FileCategory.ALL
    private var historyPanel: HistoryPanel = HistoryPanel.BROWSING
    private var historySearchQuery: String = ""
    private var historyDateGroup: DateGroup = DateGroup.ALL
    private var visibleHistoryIds: Set<Long> = emptySet()
    private var visibleBrowsingHistoryIds: Set<Long> = emptySet()
    private val selectedDownloadIds = linkedSetOf<Long>()
    private val selectedBookmarkIds = linkedSetOf<Long>()
    private val selectedHistoryIds = linkedSetOf<Long>()
    private val selectedResourceIds = linkedSetOf<Long>()
    private val promptedExportIds = linkedSetOf<Long>()
    private var selectionMode = false
    private var currentPageUrl: String? = null
    private var contentLoadJob: Job? = null
    private var currentBookmarkFolderId: Long = BrowserStore.ROOT_BOOKMARK_FOLDER_ID
    private val downloadRefreshRunnable = object : Runnable {
        override fun run() {
            if (mode == LibraryMode.DOWNLOADS && !isFinishing && !isDestroyed) {
                loadContent()
                binding.recyclerView.postDelayed(this, DOWNLOAD_REFRESH_INTERVAL_MS)
            }
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (mode == LibraryMode.DOWNLOADS) {
                loadContent()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyTheme()

        mode = LibraryMode.fromValue(intent.getStringExtra(EXTRA_MODE))
        currentPageUrl = intent.getStringExtra(EXTRA_PAGE_URL)?.takeIf { it.isNotBlank() }
        adapter = LibraryAdapter(
            onItemClick = ::handleItemClick,
            onItemLongClick = ::handleItemLongClick,
            onPrimaryClick = ::handlePrimaryAction,
            onSecondaryClick = ::handleSecondaryAction,
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.toolbar.title = mode.title
        binding.toolbar.subtitle = initialSubtitle()
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            if (selectionMode) {
                exitSelectionMode()
            } else if (mode == LibraryMode.BOOKMARKS && currentBookmarkFolderId != BrowserStore.ROOT_BOOKMARK_FOLDER_ID) {
                openBookmarkFolder(store.getBookmarkParentId(currentBookmarkFolderId))
            } else {
                finish()
            }
        }
        setupLibraryMenu()
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    loadContent()
                    true
                }

                R.id.action_create_folder -> {
                    showCreateBookmarkFolderDialog()
                    true
                }

                R.id.action_clear -> {
                    confirmClear()
                    true
                }

                R.id.action_export -> {
                    exportCurrentLibrary()
                    true
                }

                R.id.action_multi_select -> {
                    enterSelectionMode()
                    true
                }

                R.id.action_select_all -> {
                    toggleSelectAll()
                    true
                }

                R.id.action_batch_actions -> {
                    showBatchActions()
                    true
                }

                R.id.action_cleanup_missing -> {
                    cleanupMissingFiles()
                    true
                }

                R.id.action_import_bookmarks -> {
                    bookmarkImportLauncher.launch("text/html")
                    true
                }

                else -> false
            }
        }

        setupCategoryChips()
        setupSearchInput()
        updateToolbarMenu()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectionMode) {
                        exitSelectionMode()
                    } else if (mode == LibraryMode.BOOKMARKS && currentBookmarkFolderId != BrowserStore.ROOT_BOOKMARK_FOLDER_ID) {
                        openBookmarkFolder(store.getBookmarkParentId(currentBookmarkFolderId))
                    } else {
                        finish()
                    }
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        loadContent()
        if (mode == LibraryMode.DOWNLOADS) {
            binding.recyclerView.removeCallbacks(downloadRefreshRunnable)
            binding.recyclerView.postDelayed(downloadRefreshRunnable, DOWNLOAD_REFRESH_INTERVAL_MS)
        }
    }

    override fun onStop() {
        binding.recyclerView.removeCallbacks(downloadRefreshRunnable)
        runCatching { unregisterReceiver(downloadReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        contentLoadJob?.cancel()
        if (::adapter.isInitialized) {
            adapter.release()
        }
        super.onDestroy()
    }

    private fun setupSearchInput() {
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                historySearchQuery = binding.searchInput.text?.toString().orEmpty()
                renderHistory()
                true
            } else false
        }
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                historySearchQuery = s?.toString().orEmpty()
                if (historyPanel == HistoryPanel.SEARCH) {
                    renderHistory()
                } else if (mode == LibraryMode.HISTORY) {
                    loadContent()
                }
            }
        })
    }

    private fun setupCategoryChips() {
        binding.searchInput.isVisible = false
        if (mode == LibraryMode.HISTORY) {
            setupHistoryChips()
            return
        }
        if (mode != LibraryMode.DOWNLOADS && mode != LibraryMode.RESOURCES) {
            binding.filterScroll.isVisible = false
            return
        }

        binding.filterScroll.isVisible = true
        binding.categoryChips.removeAllViews()
        resourceFilterCategories().forEach { category ->
            val chip = Chip(this).apply {
                text = category.label
                isCheckable = true
                isChecked = category == selectedCategory
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selectedCategory = category
                        loadContent()
                    }
                }
            }
            binding.categoryChips.addView(chip)
        }
    }

    private fun setupHistoryChips() {
        binding.filterScroll.isVisible = true
        binding.categoryChips.removeAllViews()
        HistoryPanel.entries.forEach { panel ->
            val chip = Chip(this).apply {
                text = panel.label
                isCheckable = true
                isChecked = panel == historyPanel
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        historyPanel = panel
                        binding.searchInput.isVisible = true
                        loadContent()
                    }
                }
            }
            binding.categoryChips.addView(chip)
        }
        val divider = Chip(this).apply {
            text = "|"
            isEnabled = false
            setChipBackgroundColorResource(android.R.color.transparent)
            setTextColor(getColor(R.color.aurora_text_muted))
        }
        binding.categoryChips.addView(divider)
        DateGroup.entries.forEach { group ->
            val chip = Chip(this).apply {
                text = group.label
                isCheckable = true
                isChecked = group == historyDateGroup
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        historyDateGroup = group
                        loadContent()
                    }
                }
            }
            binding.categoryChips.addView(chip)
        }
    }

    private fun loadContent() {
        contentLoadJob?.cancel()
        contentLoadJob = lifecycleScope.launch {
            when (mode) {
                LibraryMode.BOOKMARKS -> {
                    val folderId = currentBookmarkFolderId
                    val payload = withContext(Dispatchers.IO) {
                        val items = store.getBookmarks(folderId)
                        val folderIds = items.filter { it.isFolder }.map { it.id }
                        BookmarkLoadPayload(
                            bookmarks = items,
                            childCounts = store.getBookmarkChildCounts(folderIds),
                            foldersById = if (folderIds.isEmpty()) emptyMap() else store.getAllBookmarkFolders().associateBy { it.id },
                        )
                    }
                    bookmarks = payload.bookmarks
                selectedBookmarkIds.retainAll(bookmarks.map { it.id }.toSet())
                if (selectionMode && selectedBookmarkIds.isEmpty()) exitSelectionMode()
                adapter.submitList(
                    bookmarks.map {
                        LibraryRenderItem(
                            id = it.id,
                            title = it.title,
                            subtitle = if (it.isFolder) bookmarkFolderSubtitle(it, payload.foldersById) else it.url.orEmpty(),
                            meta = if (it.isFolder) {
                                "文件夹 · ${payload.childCounts[it.id] ?: 0} 项"
                            } else {
                                "添加于 ${ResourceTools.formatTime(it.createdAt)}"
                            },
                            primaryText = if (it.isFolder) "进入" else "打开",
                            secondaryText = if (it.isFolder) "管理" else "删除",
                            leadingIconRes = if (it.isFolder) R.drawable.ic_folder else null,
                            primaryIconRes = if (it.isFolder) R.drawable.ic_open else R.drawable.ic_open,
                            secondaryIconRes = R.drawable.ic_delete,
                            selectionMode = selectionMode,
                            selected = selectedBookmarkIds.contains(it.id),
                        )
                    },
                )
                updateEmptyState(bookmarks.isEmpty(), if (currentBookmarkFolderId == BrowserStore.ROOT_BOOKMARK_FOLDER_ID) "暂无书签" else "这个文件夹还是空的")
            }

            LibraryMode.HISTORY -> {
                if (historyPanel == HistoryPanel.SEARCH) {
                    searchHistoryItems = withContext(Dispatchers.IO) { preferences.getSearchHistory() }
                } else {
                    val sinceMillis = historyDateGroup.sinceMillis()
                    val query = historySearchQuery.takeIf { it.isNotBlank() }
                    historyItems = withContext(Dispatchers.IO) {
                        store.getHistory(sinceMillis = sinceMillis, query = query)
                    }
                }
                renderHistory()
            }

            LibraryMode.RESOURCES -> {
                val pageUrl = currentPageUrl
                resources = withContext(Dispatchers.IO) {
                    pageUrl?.let {
                        store.getResourcesForPage(
                            pageUrl = it,
                            category = selectedCategory.takeIf { category -> category != FileCategory.ALL },
                        )
                    } ?: emptyList()
                }
                renderResources()
            }

            LibraryMode.DOWNLOADS -> {
                downloads = withContext(Dispatchers.IO) {
                    BrowserDownloads.refreshSystemDownloads(this@LibraryActivity, store)
                    store.getDownloads(category = selectedCategory.takeIf { it != FileCategory.ALL })
                }
                selectedDownloadIds.retainAll(downloads.map { it.id }.toSet())
                if (selectionMode && selectedDownloadIds.isEmpty()) {
                    selectionMode = false
                }
                renderDownloads()
                promptCompletedExportsIfNeeded()
            }
        }
            updateToolbarMenu()
        }
    }

    private fun renderHistory() {
        if (historyPanel == HistoryPanel.SEARCH) {
            val filtered = if (historySearchQuery.isBlank()) searchHistoryItems
            else searchHistoryItems.filter {
                it.query.contains(historySearchQuery, ignoreCase = true)
            }
            visibleSearchHistoryItems = filtered
            visibleBrowsingHistoryItems = emptyList()
            visibleHistoryIds = filtered.map { it.query.lowercase().hashCode().toLong() }.toSet()
            visibleBrowsingHistoryIds = emptySet()
            selectedHistoryIds.retainAll(visibleHistoryIds)
            if (selectionMode && selectedHistoryIds.isEmpty()) exitSelectionMode()
            adapter.submitList(
                visibleSearchHistoryItems.map {
                    LibraryRenderItem(
                        id = it.query.lowercase().hashCode().toLong(),
                        title = it.query,
                        subtitle = "使用 ${preferences.getSearchEngine().label} 再次搜索",
                        meta = if (it.searchedAt > 0L) {
                            "搜索于 ${ResourceTools.formatTime(it.searchedAt)}"
                        } else {
                            "搜索记录"
                        },
                        primaryText = "搜索",
                        secondaryText = "删除",
                        leadingIconRes = R.drawable.ic_search,
                        compact = true,
                        primaryIconRes = R.drawable.ic_search,
                        secondaryIconRes = R.drawable.ic_delete,
                        selectionMode = selectionMode,
                        selected = selectedHistoryIds.contains(it.query.lowercase().hashCode().toLong()),
                    )
                },
            )
            updateEmptyState(filtered.isEmpty(), if (historySearchQuery.isNotBlank()) "无匹配的搜索历史" else "暂无搜索历史")
            return
        }

        val filtered = historyItems
        visibleBrowsingHistoryItems = filtered
        visibleSearchHistoryItems = emptyList()
        visibleBrowsingHistoryIds = filtered.map { it.id }.toSet()
        visibleHistoryIds = emptySet()
        selectedHistoryIds.retainAll(visibleBrowsingHistoryIds)
        if (selectionMode && selectedHistoryIds.isEmpty()) exitSelectionMode()
        adapter.submitList(
            visibleBrowsingHistoryItems.map {
                LibraryRenderItem(
                    id = it.id,
                    title = it.title,
                    subtitle = it.url,
                    meta = "访问于 ${ResourceTools.formatTime(it.visitedAt)}",
                    primaryText = "打开",
                    secondaryText = "删除",
                    leadingIconRes = R.drawable.ic_history,
                    compact = true,
                    primaryIconRes = R.drawable.ic_open,
                    secondaryIconRes = R.drawable.ic_delete,
                    selectionMode = selectionMode,
                    selected = selectedHistoryIds.contains(it.id),
                )
            },
        )
        updateEmptyState(filtered.isEmpty(), when {
            historySearchQuery.isNotBlank() -> "无匹配的浏览历史"
            historyDateGroup != DateGroup.ALL -> "该时段暂无浏览历史"
            else -> "暂无浏览历史"
        })
    }

    private fun renderResources() {
        visibleResources = resources
            .filter { selectedCategory == FileCategory.ALL || it.category == selectedCategory }
            .sortedWith(
                compareBy<ResourceItem> { resourcePriority(it) }
                    .thenByDescending { it.capturedAt },
            )
        val visibleIds = visibleResources.map { it.id }.toSet()
        selectedResourceIds.retainAll(visibleIds)
        if (selectionMode && selectedResourceIds.isEmpty()) exitSelectionMode()
        adapter.submitList(
            visibleResources.map {
                LibraryRenderItem(
                    id = it.id,
                    title = it.title,
                    subtitle = it.url,
                    meta = "${it.category.label} · ${if (it.isStream) "流媒体" else "文件"} · ${ResourceTools.formatTime(it.capturedAt)}",
                    primaryText = "下载",
                    secondaryText = if (it.pageUrl.isNullOrBlank()) "删除" else "来源页",
                    primaryIconRes = R.drawable.ic_download,
                    secondaryIconRes = if (it.pageUrl.isNullOrBlank()) R.drawable.ic_delete else R.drawable.ic_open,
                    selectionMode = selectionMode,
                    selected = selectedResourceIds.contains(it.id),
                )
            },
        )
        updateEmptyState(visibleResources.isEmpty(), "暂无嗅探到的资源")
    }

    private fun renderDownloads() {
        visibleDownloads = downloads.filter {
            selectedCategory == FileCategory.ALL || it.category == selectedCategory
        }
        adapter.submitList(
            visibleDownloads.map {
                val isCompleted = it.status.startsWith("已完成") && !it.localUri.isNullOrBlank()
                val canPlayWhileDownloading = it.downloadType == "hls" && !it.localUri.isNullOrBlank()
                val isCompletedHlsExport = isCompleted && it.downloadType == "hls_export"
                val primaryText = when {
                    it.downloadType == "p2p" && isCompleted -> "打开文件夹"
                    isCompletedHlsExport -> "分享"
                    isCompleted && it.downloadType == "hls" -> "播放/转码"
                    BrowserDownloads.canRetry(it) && it.downloadType == "hls_export" -> "重新转码"
                    BrowserDownloads.canRetry(it) -> "重新下载"
                    BrowserDownloads.canResume(it) -> "继续"
                    canPlayWhileDownloading -> "边下边播"
                    isCompleted -> "打开"
                    else -> "复制链接"
                }
                val primaryIcon = when {
                    it.downloadType == "p2p" && isCompleted -> R.drawable.ic_open
                    isCompletedHlsExport -> R.drawable.ic_share
                    BrowserDownloads.canResume(it) || canPlayWhileDownloading -> R.drawable.ic_play
                    isCompleted -> R.drawable.ic_open
                    BrowserDownloads.canRetry(it) -> R.drawable.ic_download
                    else -> R.drawable.ic_open
                }
                LibraryRenderItem(
                    id = it.id,
                    title = it.title,
                    subtitle = downloadSubtitle(it),
                    meta = buildString {
                        append(it.status)
                        append(" · ")
                        append(downloadSizeLabel(it))
                        it.progressPercent?.takeIf { progress -> progress in 0..99 }?.let { progress ->
                            append(" · $progress%")
                        }
                    },
                    primaryText = primaryText,
                    secondaryText = "管理",
                    primaryEnabled = it.downloadType == "p2p" || canPlayWhileDownloading || it.status != "失败" || !it.sourceUrl.isBlank(),
                    progressPercent = it.progressPercent?.takeIf { progress -> progress in 0..99 },
                    selectionMode = selectionMode,
                    selected = selectedDownloadIds.contains(it.id),
                    thumbnailUri = if (it.category == FileCategory.VIDEO) it.localUri else null,
                    compact = true,
                    primaryIconRes = primaryIcon,
                    secondaryIconRes = R.drawable.ic_delete,
                )
            },
        )
        updateEmptyState(visibleDownloads.isEmpty(), "暂无下载记录")
    }

    private fun downloadSubtitle(item: DownloadItem): String {
        val cat = item.category.label
        return if (!item.errorMessage.isNullOrBlank()) {
            "$cat · ${item.errorMessage}"
        } else {
            cat
        }
    }

    private fun updateEmptyState(isEmpty: Boolean, text: String) {
        binding.emptyText.isVisible = isEmpty
        binding.emptyText.text = text
    }

    private fun handleItemClick(position: Int) {
        if (selectionMode) {
            toggleItemSelection(position)
            return
        }
        when (mode) {
            LibraryMode.BOOKMARKS -> bookmarks.getOrNull(position)?.let {
                if (it.isFolder) {
                    openBookmarkFolder(it.id)
                } else {
                    openInBrowser(it.url)
                }
            }

            LibraryMode.DOWNLOADS -> {
                val item = visibleDownloads.getOrNull(position) ?: return
                val isCompleted = item.status.startsWith("已完成") && !item.localUri.isNullOrBlank()
                if (isCompleted) {
                    if (!BrowserDownloads.openDownload(this, item)) {
                        toast("无法打开文件")
                    }
                } else if (item.downloadType == "hls" && !item.localUri.isNullOrBlank()) {
                    if (!BrowserDownloads.openDownload(this, item)) {
                        toast("无法播放正在下载的视频")
                    }
                }
            }

            LibraryMode.HISTORY -> handlePrimaryAction(position)

            else -> Unit
        }
    }

    private fun handleItemLongClick(position: Int) {
        if (!selectionMode) {
            enterSelectionMode()
        }
        toggleItemSelection(position)
    }

    private fun handlePrimaryAction(position: Int) {
        when (mode) {
            LibraryMode.BOOKMARKS -> bookmarks.getOrNull(position)?.let {
                if (it.isFolder) {
                    openBookmarkFolder(it.id)
                } else {
                    openInBrowser(it.url)
                }
            }
            LibraryMode.HISTORY -> {
                if (historyPanel == HistoryPanel.SEARCH) {
                    visibleSearchHistoryItems.getOrNull(position)?.let { openSearchInBrowser(it.query) }
                } else {
                    visibleBrowsingHistoryItems.getOrNull(position)?.let { openInBrowser(it.url) }
                }
            }
            LibraryMode.RESOURCES -> visibleResources.getOrNull(position)?.let { item ->
                enqueueResourceWithQuality(item)
            }

            LibraryMode.DOWNLOADS -> {
                val item = visibleDownloads.getOrNull(position) ?: return
                if (selectionMode) {
                    toggleItemSelection(position)
                    return
                }
                if (BrowserDownloads.canRetry(item)) {
                    toast(BrowserDownloads.retryDownload(this, store, item))
                    loadContent()
                    return
                }
                if (BrowserDownloads.canResume(item)) {
                    toast(BrowserDownloads.resumeDownload(this, store, item))
                    loadContent()
                    return
                }
                if (item.downloadType == "hls" && !item.localUri.isNullOrBlank()) {
                    if (item.status.startsWith("已完成")) {
                        showCompletedHlsActions(item)
                    } else if (!BrowserDownloads.openDownload(this, item)) {
                        toast("无法播放正在下载的视频")
                    }
                    return
                }
                if (item.downloadType == "hls_export" && item.status.startsWith("已完成") && !item.localUri.isNullOrBlank()) {
                    shareFile(item)
                    return
                }
                if (item.status.startsWith("已完成") && !item.localUri.isNullOrBlank()) {
                    if (!BrowserDownloads.openDownload(this, item)) {
                        toast("无法打开文件")
                    }
                } else {
                    copyToClipboard(item.sourceUrl)
                    toast("下载链接已复制")
                }
            }
        }
    }

    private fun handleSecondaryAction(position: Int) {
        when (mode) {
            LibraryMode.BOOKMARKS -> bookmarks.getOrNull(position)?.let {
                if (it.isFolder) {
                    showBookmarkFolderActions(it)
                } else {
                    confirmDeleteBookmark(it)
                }
            }

            LibraryMode.HISTORY -> {
                if (historyPanel == HistoryPanel.SEARCH) {
                    visibleSearchHistoryItems.getOrNull(position)?.let { confirmDeleteSearchHistory(it) }
                } else {
                    visibleBrowsingHistoryItems.getOrNull(position)?.let { confirmDeleteHistory(it) }
                }
            }

            LibraryMode.RESOURCES -> visibleResources.getOrNull(position)?.let {
                if (it.pageUrl.isNullOrBlank()) {
                    confirmDeleteResource(it)
                } else {
                    openInBrowser(it.pageUrl)
                }
            }

            LibraryMode.DOWNLOADS -> {
                val item = visibleDownloads.getOrNull(position) ?: return
                if (selectionMode) {
                    toggleItemSelection(position)
                } else {
                    showDownloadManageActions(item)
                }
            }
        }
    }

    private fun resourceFilterCategories(): List<FileCategory> {
        return when (mode) {
            LibraryMode.RESOURCES -> listOf(
                FileCategory.ALL,
                FileCategory.STREAM,
                FileCategory.VIDEO,
                FileCategory.AUDIO,
                FileCategory.IMAGE,
                FileCategory.SUBTITLE,
                FileCategory.DOCUMENT,
                FileCategory.ARCHIVE,
                FileCategory.TORRENT,
                FileCategory.OTHER,
            )

            LibraryMode.DOWNLOADS -> FileCategory.entries.toList()
            else -> emptyList()
        }
    }

    private fun resourcePriority(item: ResourceItem): Int {
        return when {
            item.isStream || item.category == FileCategory.STREAM -> 0
            item.category == FileCategory.VIDEO -> 1
            item.category == FileCategory.AUDIO -> 2
            item.category == FileCategory.IMAGE -> 3
            item.category == FileCategory.SUBTITLE -> 4
            item.category == FileCategory.DOCUMENT -> 5
            item.category == FileCategory.ARCHIVE -> 6
            item.category == FileCategory.TORRENT -> 7
            else -> 8
        }
    }

    private fun enqueueResourceWithQuality(item: ResourceItem) {
        if (!item.isStream && item.category != FileCategory.STREAM) {
            showDownloadFeedback(BrowserDownloads.enqueueFromResource(this, store, item))
            return
        }
        Snackbar.make(binding.root, "正在解析流媒体清晰度", Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val variants = withContext(Dispatchers.IO) {
                runCatching {
                    BrowserDownloads.loadHlsVariants(
                        context = this@LibraryActivity,
                        url = item.url,
                        referer = item.referer ?: item.pageUrl,
                        userAgent = item.userAgent,
                    )
                }.getOrDefault(emptyList())
            }
            if (variants.isEmpty()) {
                showDownloadFeedback(BrowserDownloads.enqueueFromResource(this@LibraryActivity, store, item))
                return@launch
            }
            ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
                .setTitle("选择清晰度")
                .setItems(variants.mapIndexed { index, option -> "${index + 1}. ${option.label}" }.toTypedArray()) { _, which ->
                    val option = variants[which]
                    val resource = item.copy(
                        url = option.url,
                        title = listOf(item.title, option.label).filter { it.isNotBlank() }.joinToString(" "),
                        mimeType = "application/x-mpegURL",
                        category = FileCategory.STREAM,
                        isStream = true,
                    )
                    showDownloadFeedback(BrowserDownloads.enqueueFromResource(this@LibraryActivity, store, resource))
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showDownloadFeedback(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction("查看") {
                if (mode == LibraryMode.DOWNLOADS) {
                    loadContent()
                } else {
                    startActivity(createIntent(this, LibraryMode.DOWNLOADS, null))
                }
            }
            .show()
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selectedDownloadIds.clear()
        selectedBookmarkIds.clear()
        selectedHistoryIds.clear()
        selectedResourceIds.clear()
        updateToolbarMenu()
        rerenderCurrentContent()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedDownloadIds.clear()
        selectedBookmarkIds.clear()
        selectedHistoryIds.clear()
        selectedResourceIds.clear()
        updateToolbarMenu()
        rerenderCurrentContent()
    }

    private fun currentSelectedIds(): LinkedHashSet<Long> {
        return when (mode) {
            LibraryMode.BOOKMARKS -> selectedBookmarkIds
            LibraryMode.HISTORY -> selectedHistoryIds
            LibraryMode.RESOURCES -> selectedResourceIds
            LibraryMode.DOWNLOADS -> selectedDownloadIds
        }
    }

    private fun currentVisibleCount(): Int {
        return when (mode) {
            LibraryMode.BOOKMARKS -> bookmarks.size
            LibraryMode.HISTORY -> if (historyPanel == HistoryPanel.SEARCH) visibleHistoryIds.size else visibleBrowsingHistoryIds.size
            LibraryMode.RESOURCES -> visibleResources.size
            LibraryMode.DOWNLOADS -> visibleDownloads.size
        }
    }

    private fun rerenderCurrentContent() {
        when (mode) {
            LibraryMode.BOOKMARKS -> loadContent()
            LibraryMode.HISTORY -> renderHistory()
            LibraryMode.RESOURCES -> renderResources()
            LibraryMode.DOWNLOADS -> renderDownloads()
        }
    }

    private fun toggleItemSelection(position: Int) {
        val itemId = when (mode) {
            LibraryMode.BOOKMARKS -> bookmarks.getOrNull(position)?.id ?: return
            LibraryMode.HISTORY -> {
                if (historyPanel == HistoryPanel.SEARCH) {
                    val item = visibleSearchHistoryItems.getOrNull(position) ?: return
                    item.query.lowercase().hashCode().toLong()
                } else {
                    visibleBrowsingHistoryItems.getOrNull(position)?.id ?: return
                }
            }
            LibraryMode.RESOURCES -> visibleResources.getOrNull(position)?.id ?: return
            LibraryMode.DOWNLOADS -> visibleDownloads.getOrNull(position)?.id ?: return
        }
        val selectedIds = currentSelectedIds()
        if (!selectedIds.add(itemId)) {
            selectedIds.remove(itemId)
        }
        if (selectedIds.isEmpty()) {
            exitSelectionMode()
        } else {
            updateToolbarMenu()
            rerenderCurrentContent()
        }
    }

    private fun toggleSelectAll() {
        if (!selectionMode) return
        val selectedIds = currentSelectedIds()
        val total = currentVisibleCount()
        if (selectedIds.size == total && total > 0) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            when (mode) {
                LibraryMode.BOOKMARKS -> selectedIds.addAll(bookmarks.map { it.id })
                LibraryMode.HISTORY -> {
                    if (historyPanel == HistoryPanel.SEARCH) {
                        selectedIds.addAll(visibleHistoryIds)
                    } else {
                        selectedIds.addAll(visibleBrowsingHistoryIds)
                    }
                }
                LibraryMode.RESOURCES -> selectedIds.addAll(visibleResources.map { it.id })
                LibraryMode.DOWNLOADS -> selectedIds.addAll(visibleDownloads.map { it.id })
            }
        }
        if (selectedIds.isEmpty()) {
            exitSelectionMode()
        } else {
            updateToolbarMenu()
            rerenderCurrentContent()
        }
    }

    private fun selectedDownloads(): List<DownloadItem> {
        return downloads.filter { selectedDownloadIds.contains(it.id) }
    }

    private fun updateToolbarMenu() {
        val menu = binding.toolbar.menu
        val isDownloads = mode == LibraryMode.DOWNLOADS
        val hasItems = currentVisibleCount() > 0
        menu.findItem(R.id.action_clear).isVisible = !selectionMode && mode != LibraryMode.BOOKMARKS
        menu.findItem(R.id.action_refresh).isVisible = !selectionMode
        menu.findItem(R.id.action_create_folder).isVisible = !selectionMode && mode == LibraryMode.BOOKMARKS
        menu.findItem(R.id.action_export).isVisible = !selectionMode && hasItems
        menu.findItem(R.id.action_multi_select).isVisible = !selectionMode && hasItems
        menu.findItem(R.id.action_select_all).isVisible = selectionMode
        menu.findItem(R.id.action_batch_actions).isVisible = selectionMode && currentSelectedIds().isNotEmpty()
        menu.findItem(R.id.action_cleanup_missing).isVisible = isDownloads && !selectionMode && downloads.any { !it.localUri.isNullOrBlank() }
        menu.findItem(R.id.action_import_bookmarks).isVisible = mode == LibraryMode.BOOKMARKS && !selectionMode
        val selectedCount = currentSelectedIds().size
        binding.toolbar.title = when {
            selectionMode -> "已选择 $selectedCount 项"
            mode == LibraryMode.HISTORY -> historyPanel.label
            else -> mode.title
        }
        binding.toolbar.subtitle = when {
            selectionMode -> "批量管理"
            mode == LibraryMode.HISTORY -> historyPanel.subtitle
            else -> initialSubtitle()
        }
    }

    private fun initialSubtitle(): String {
        if (mode == LibraryMode.BOOKMARKS) {
            val path = store.getBookmarkPath(currentBookmarkFolderId)
            return if (path.isEmpty()) {
                mode.subtitle
            } else {
                "位置 · " + path.joinToString(" / ") { it.title }
            }
        }
        if (mode != LibraryMode.RESOURCES) {
            return mode.subtitle
        }
        val pageUrl = currentPageUrl ?: return mode.subtitle
        val host = runCatching { android.net.Uri.parse(pageUrl).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
        return if (host != null) {
            "当前标签页 · $host"
        } else {
            "当前标签页捕获的资源"
        }
    }

    private fun cleanupMissingFiles() {
        val exportedSourceIds = downloads
            .filter { it.downloadType == "hls_export" && !it.localUri.isNullOrBlank() }
            .map { it.sourceUrl }
            .toSet()
        val missingRecords = downloads.filter { item ->
            val uri = item.localUri
            if (uri.isNullOrBlank()) return@filter false
            if (item.downloadType == "hls" && item.sourceUrl in exportedSourceIds) return@filter false
            !runCatching {
                val path = android.net.Uri.parse(uri).path
                if (path == null) false else File(path).exists()
            }.getOrDefault(false)
        }
        if (missingRecords.isEmpty()) {
            toast("所有已下载文件均完好")
            return
        }
        val hlsSkipped = downloads.count {
            it.downloadType == "hls" && it.sourceUrl in exportedSourceIds &&
            it.localUri != null && !runCatching { File(android.net.Uri.parse(it.localUri).path!!).exists() }.getOrDefault(false)
        }
        val extraHint = if (hlsSkipped > 0) "（已跳过 $hlsSkipped 条已转 MP4 的原 HLS 记录）" else ""
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle("清理已删除的文件记录")
            .setMessage("检测到 ${missingRecords.size} 条下载记录对应的文件已被删除，是否清除这些下载记录？\n$extraHint")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理记录") { _, _ ->
                missingRecords.forEach { store.deleteDownload(it.id) }
                selectedDownloadIds.removeAll(missingRecords.map { it.id })
                if (selectedDownloadIds.isEmpty()) {
                    selectionMode = false
                }
                loadContent()
                toast("已清除 ${missingRecords.size} 条记录")
            }
            .show()
    }

    private fun exportCurrentLibrary() {
        lifecycleScope.launch {
            val title = when (mode) {
                LibraryMode.BOOKMARKS -> "Aurora 书签导出"
                LibraryMode.HISTORY -> if (historyPanel == HistoryPanel.SEARCH) "Aurora 搜索历史导出" else "Aurora 浏览历史导出"
                LibraryMode.RESOURCES -> "Aurora 嗅探资源导出"
                LibraryMode.DOWNLOADS -> "Aurora 下载记录导出"
            }
            val html = withContext(Dispatchers.IO) { buildExportHtml(title) }
            val message = withContext(Dispatchers.IO) {
                BrowserDownloads.saveHtmlDocument(
                    context = this@LibraryActivity,
                    store = store,
                    title = title,
                    pageUrl = "about:${mode.value}",
                    html = html,
                )
            }
            toast(message.replace("网页 HTML", "列表 HTML"))
            if (mode == LibraryMode.DOWNLOADS) {
                loadContent()
            }
        }
    }

    private fun buildExportHtml(title: String): String {
        val rows = when (mode) {
            LibraryMode.BOOKMARKS -> bookmarks.map { item ->
                exportRow(
                    title = item.title,
                    url = item.url.orEmpty(),
                    meta = if (item.isFolder) "文件夹" else ResourceTools.formatTime(item.createdAt),
                )
            }
            LibraryMode.HISTORY -> if (historyPanel == HistoryPanel.SEARCH) {
                searchHistoryItems.map { item ->
                    exportRow(item.query, preferences.getSearchEngine().buildSearchUrl(item.query), ResourceTools.formatTime(item.searchedAt))
                }
            } else {
                historyItems.map { item ->
                    exportRow(item.title, item.url, ResourceTools.formatTime(item.visitedAt))
                }
            }
            LibraryMode.RESOURCES -> visibleResources.map { item ->
                exportRow(item.title, item.url, "${item.category.label} · ${ResourceTools.formatTime(item.capturedAt)}")
            }
            LibraryMode.DOWNLOADS -> visibleDownloads.map { item ->
                exportRow(item.title, item.sourceUrl, "${item.status} · ${item.category.label}")
            }
        }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escapeHtml(title)}</title>
              <style>
                body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;margin:24px;line-height:1.55;color:#1f2933;background:#fbfcfe}
                h1{font-size:22px;margin:0 0 16px}
                .item{padding:12px 0;border-bottom:1px solid #d9e2ec}
                a{color:#1264a3;text-decoration:none;word-break:break-all}
                .meta{color:#65758b;font-size:13px;margin-top:4px}
              </style>
            </head>
            <body>
              <h1>${escapeHtml(title)}</h1>
              ${rows.joinToString("\n")}
            </body>
            </html>
        """.trimIndent()
    }

    private fun exportRow(title: String, url: String, meta: String): String {
        val escapedTitle = escapeHtml(title.ifBlank { url })
        val escapedUrl = escapeHtml(url)
        return """
            <div class="item">
              <a href="$escapedUrl">$escapedTitle</a>
              <div class="meta">${escapeHtml(meta)}</div>
            </div>
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun confirmClear() {
        val message = when (mode) {
            LibraryMode.BOOKMARKS -> return
            LibraryMode.HISTORY -> if (historyPanel == HistoryPanel.SEARCH) {
                "清空全部搜索历史？"
            } else {
                "清空全部浏览历史？"
            }
            LibraryMode.RESOURCES -> "清空全部嗅探记录？"
            LibraryMode.DOWNLOADS -> "仅清空下载记录，不删除文件，是否继续？"
        }
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle(if (mode == LibraryMode.HISTORY) historyPanel.label else mode.title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                when (mode) {
                    LibraryMode.HISTORY -> {
                        if (historyPanel == HistoryPanel.SEARCH) {
                            preferences.clearRecentSearches()
                        } else {
                            store.clearHistory()
                        }
                    }
                    LibraryMode.RESOURCES -> store.clearResources()
                    LibraryMode.DOWNLOADS -> store.clearDownloads()
                    LibraryMode.BOOKMARKS -> Unit
                }
                loadContent()
            }
            .show()
    }

    private fun openBookmarkFolder(folderId: Long) {
        currentBookmarkFolderId = folderId
        loadContent()
    }

    private fun bookmarkFolderSubtitle(item: BookmarkItem, foldersById: Map<Long, BookmarkItem>): String {
        val path = buildList {
            var cursorId = item.id
            while (cursorId != BrowserStore.ROOT_BOOKMARK_FOLDER_ID) {
                val node = foldersById[cursorId] ?: break
                add(0, node.title)
                cursorId = node.folderId
            }
        }.joinToString(" / ")
        return if (path.isBlank()) {
            "进入文件夹"
        } else {
            path
        }
    }

    private fun confirmDeleteBookmark(item: BookmarkItem) {
        confirmDestructiveAction(
            title = item.title,
            message = "删除这条书签？",
        ) {
            store.deleteBookmark(item.id)
            loadContent()
        }
    }

    private fun confirmDeleteHistory(item: HistoryItem) {
        confirmDestructiveAction(
            title = item.title,
            message = "删除这条浏览历史？",
        ) {
            store.deleteHistory(item.id)
            loadContent()
        }
    }

    private fun confirmDeleteSearchHistory(item: SearchHistoryItem) {
        confirmDestructiveAction(
            title = item.query,
            message = "删除这条搜索历史？",
        ) {
            preferences.deleteRecentSearch(item.query)
            loadContent()
        }
    }

    private fun confirmDeleteResource(item: ResourceItem) {
        confirmDestructiveAction(
            title = item.title,
            message = "删除这条嗅探记录？",
        ) {
            store.deleteResource(item.id)
            loadContent()
        }
    }

    private fun confirmDestructiveAction(
        title: String,
        message: String,
        positiveText: String = "删除",
        onConfirm: () -> Unit,
    ) {
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .show()
    }

    private fun showCreateBookmarkFolderDialog(parentId: Long = currentBookmarkFolderId) {
        val input = EditText(this).apply {
            hint = "文件夹名称"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle("新建书签文件夹")
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 20, 48, 0)
                    addView(input)
                },
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                store.createBookmarkFolder(input.text?.toString().orEmpty(), parentId)
                if (currentBookmarkFolderId != parentId) {
                    currentBookmarkFolderId = parentId
                }
                loadContent()
            }
            .show()
    }

    private fun showBookmarkFolderActions(item: BookmarkItem) {
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle(item.title)
            .setItems(arrayOf("重命名", "在此新建子文件夹", "删除文件夹")) { _, which ->
                when (which) {
                    0 -> showRenameBookmarkFolderDialog(item)
                    1 -> showCreateBookmarkFolderDialog(item.id)
                    2 -> confirmDeleteBookmarkFolder(item)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameBookmarkFolderDialog(item: BookmarkItem) {
        val input = EditText(this).apply {
            hint = "文件夹名称"
            setText(item.title)
            setSelection(text?.length ?: 0)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle("重命名文件夹")
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 20, 48, 0)
                    addView(input)
                },
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                store.renameBookmarkFolder(item.id, input.text?.toString().orEmpty())
                loadContent()
            }
            .show()
    }

    private fun confirmDeleteBookmarkFolder(item: BookmarkItem) {
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle(item.title)
            .setMessage("删除这个文件夹及其全部子文件夹、书签？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                store.deleteBookmark(item.id)
                if (currentBookmarkFolderId == item.id) {
                    currentBookmarkFolderId = store.getBookmarkParentId(item.id)
                }
                loadContent()
            }
            .show()
    }

    private fun confirmDeleteDownload(item: DownloadItem) {
        val hasLocalFile = !item.localUri.isNullOrBlank()
        val message = if (hasLocalFile) {
            "选择删除方式"
        } else {
            "删除这条下载记录？"
        }
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle(item.title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .apply {
                if (hasLocalFile) {
                    setNeutralButton("仅删记录") { _, _ ->
                        val result = BrowserDownloads.deleteDownload(this@LibraryActivity, store, item, deleteFile = false)
                        toast(result)
                        loadContent()
                    }
                    setPositiveButton("记录和文件") { _, _ ->
                        val result = BrowserDownloads.deleteDownload(this@LibraryActivity, store, item, deleteFile = true)
                        toast(result)
                        loadContent()
                    }
                } else {
                    setPositiveButton("删除") { _, _ ->
                        val result = BrowserDownloads.deleteDownload(this@LibraryActivity, store, item, deleteFile = false)
                        toast(result)
                        loadContent()
                    }
                }
            }
            .show()
    }

    private fun showCompletedHlsActions(item: DownloadItem) {
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle(item.title)
            .setItems(arrayOf("播放", "转为 MP4", "管理")) { _, which ->
                when (which) {
                    0 -> {
                        if (!BrowserDownloads.openDownload(this, item)) {
                            toast("无法播放已下载视频")
                        }
                    }

                    1 -> {
                        val result = BrowserDownloads.exportHlsToMp4(this, store, item)
                        toast(result)
                        loadContent()
                    }

                    2 -> {
                        showDownloadManageActions(item)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptCompletedExportsIfNeeded() {
        val exportItem = downloads.firstOrNull {
            it.downloadType == "hls_export" &&
                it.status.startsWith("已完成") &&
                promptedExportIds.add(it.id)
        } ?: return
        val sourceItem = downloads.firstOrNull {
            it.downloadType == "hls" &&
                it.sourceUrl == exportItem.sourceUrl &&
                !it.localUri.isNullOrBlank()
        } ?: return

        binding.recyclerView.post {
            if (isFinishing || isDestroyed) {
                return@post
            }
            ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
                .setTitle("转码完成")
                .setMessage("《${exportItem.title}》已转为 MP4，是否删除原 m3u8 源文件包？")
                .setNegativeButton("保留", null)
                .setPositiveButton("删除源文件") { _, _ ->
                    toast(BrowserDownloads.deleteDownload(this, store, sourceItem, deleteFile = true))
                    loadContent()
                }
                .show()
        }
    }

    private fun showDownloadManageActions(item: DownloadItem) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (BrowserDownloads.canPause(item)) {
            labels += "暂停"
            actions += {
                toast(BrowserDownloads.pauseDownload(this, store, item))
                loadContent()
            }
        }
        if (BrowserDownloads.canResume(item)) {
            labels += "继续"
            actions += {
                toast(BrowserDownloads.resumeDownload(this, store, item))
                loadContent()
            }
        }
        if (BrowserDownloads.canRetry(item)) {
            labels += if (item.downloadType == "hls_export") "重新转码" else "重新下载"
            actions += {
                toast(BrowserDownloads.retryDownload(this, store, item))
                loadContent()
            }
        }
        if (BrowserDownloads.canCancel(item)) {
            labels += "取消下载"
            actions += { confirmCancelDownload(item) }
        }
        if (item.downloadType == "hls" && item.status.startsWith("已完成")) {
            labels += "转为 MP4"
            actions += {
                toast(BrowserDownloads.exportHlsToMp4(this, store, item))
                loadContent()
            }
        }
        if (item.downloadType == "p2p" && item.status.startsWith("已完成")) {
            labels += "打开文件夹"
            actions += {
                if (!BrowserDownloads.openDownload(this, item)) {
                    toast("无法打开下载目录")
                }
            }
        }
        labels += "复制链接"
        actions += {
            copyToClipboard(item.sourceUrl)
            toast("下载链接已复制")
        }
        if (!item.pageUrl.isNullOrBlank()) {
            labels += "打开原网页"
            actions += { openInBrowser(item.pageUrl) }
        }
        labels += "删除"
        actions += { confirmDeleteDownload(item) }

        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchActions() {
        val selectedIds = currentSelectedIds()
        if (selectedIds.isEmpty()) return

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        when (mode) {
            LibraryMode.BOOKMARKS -> {
                labels += "批量删除"
                actions += {
                    store.deleteBookmarks(selectedIds)
                    toast("已删除 ${selectedIds.size} 条书签")
                    loadContent()
                    exitSelectionMode()
                }
            }

            LibraryMode.HISTORY -> {
                labels += "批量删除"
                actions += {
                    store.deleteHistoryItems(selectedIds)
                    toast("已删除 ${selectedIds.size} 条历史记录")
                    loadContent()
                    exitSelectionMode()
                }
            }

            LibraryMode.RESOURCES -> {
                labels += "批量下载"
                actions += {
                    visibleResources.filter { selectedResourceIds.contains(it.id) }.forEach { item ->
                        BrowserDownloads.enqueueFromResource(this, store, item)
                    }
                    showDownloadFeedback("已加入 ${selectedIds.size} 项下载")
                    loadContent()
                    exitSelectionMode()
                }
                labels += "批量删除"
                actions += {
                    store.deleteResources(selectedIds)
                    toast("已删除 ${selectedIds.size} 条资源记录")
                    loadContent()
                    exitSelectionMode()
                }
            }

            LibraryMode.DOWNLOADS -> {
                val selectedItems = selectedDownloads()
                if (selectedItems.isEmpty()) return

                if (selectedItems.any(BrowserDownloads::canPause)) {
                    labels += "批量暂停"
                    actions += {
                        selectedItems.filter(BrowserDownloads::canPause).forEach {
                            BrowserDownloads.pauseDownload(this, store, it)
                        }
                        toast("已暂停选中任务")
                        loadContent()
                        exitSelectionMode()
                    }
                }
                if (selectedItems.any(BrowserDownloads::canResume)) {
                    labels += "批量继续"
                    actions += {
                        selectedItems.filter(BrowserDownloads::canResume).forEach {
                            BrowserDownloads.resumeDownload(this, store, it)
                        }
                        toast("已继续选中任务")
                        loadContent()
                        exitSelectionMode()
                    }
                }
                if (selectedItems.any(BrowserDownloads::canRetry)) {
                    labels += "批量重新下载"
                    actions += {
                        selectedItems.filter(BrowserDownloads::canRetry).forEach {
                            BrowserDownloads.retryDownload(this, store, it)
                        }
                        toast("已重新下载选中失败任务")
                        loadContent()
                        exitSelectionMode()
                    }
                }
                if (selectedItems.any(BrowserDownloads::canCancel)) {
                    labels += "批量取消"
                    actions += { confirmCancelDownloads(selectedItems.filter(BrowserDownloads::canCancel)) }
                }
                if (selectedItems.any { it.downloadType == "hls" && it.status.startsWith("已完成") }) {
                    labels += "批量转为 MP4"
                    actions += {
                        selectedItems.filter { it.downloadType == "hls" && it.status.startsWith("已完成") }.forEach {
                            BrowserDownloads.exportHlsToMp4(this, store, it)
                        }
                        toast("已加入 MP4 转码队列")
                        loadContent()
                        exitSelectionMode()
                    }
                }
                labels += "批量删记录"
                actions += { confirmDeleteDownloads(selectedItems, deleteFiles = false) }
                if (selectedItems.any { !it.localUri.isNullOrBlank() }) {
                    labels += "批量删记录和文件"
                    actions += { confirmDeleteDownloads(selectedItems, deleteFiles = true) }
                }
            }
        }

        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle("批量操作")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteDownloads(items: List<DownloadItem>, deleteFiles: Boolean) {
        val message = if (deleteFiles) {
            "删除 ${items.size} 条记录及文件？"
        } else {
            "删除 ${items.size} 条记录？"
        }
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle("批量删除")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                items.forEach {
                    BrowserDownloads.deleteDownload(this, store, it, deleteFiles)
                }
                toast("已删除 ${items.size} 项")
                loadContent()
                exitSelectionMode()
            }
            .show()
    }

    private fun confirmCancelDownload(item: DownloadItem) {
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle(item.title)
            .setMessage("取消这个下载任务？未完成的临时数据会被清理。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认取消") { _, _ ->
                toast(BrowserDownloads.cancelDownload(this, store, item))
                loadContent()
            }
            .show()
    }

    private fun confirmCancelDownloads(items: List<DownloadItem>) {
        if (items.isEmpty()) {
            return
        }
        ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
            .setTitle("批量取消")
            .setMessage("取消 ${items.size} 个下载任务？未完成的临时数据会被清理。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认取消") { _, _ ->
                items.forEach {
                    BrowserDownloads.cancelDownload(this, store, it)
                }
                toast("已取消 ${items.size} 项")
                loadContent()
                exitSelectionMode()
            }
            .show()
    }

    private fun downloadSizeLabel(item: DownloadItem): String {
        val isEstimate = item.downloadType == "hls" && !item.status.startsWith("已完成")
        val prefix = if (isEstimate) "~" else ""
        return when {
            item.downloadedBytes != null && item.fileSizeBytes != null && item.fileSizeBytes > 0 && item.downloadedBytes < item.fileSizeBytes -> {
                "${ResourceTools.formatBytes(item.downloadedBytes)}/$prefix${ResourceTools.formatBytes(item.fileSizeBytes)}"
            }

            item.fileSizeBytes != null -> "$prefix${ResourceTools.formatBytes(item.fileSizeBytes)}"
            item.downloadedBytes != null -> "已下 ${ResourceTools.formatBytes(item.downloadedBytes)}"
            else -> "大小未知"
        }
    }

    private fun openInBrowser(url: String?) {
        if (url.isNullOrBlank()) {
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_URL, url)
                putExtra(MainActivity.EXTRA_USER_INITIATED, true)
            },
        )
        finish()
    }

    private fun openSearchInBrowser(query: String) {
        preferences.saveRecentSearch(query)
        openInBrowser(preferences.getSearchEngine().buildSearchUrl(query))
    }

    private fun shareFile(item: DownloadItem) {
        val uri = item.localUri?.let { android.net.Uri.parse(it) } ?: return
        val mime = item.mimeType ?: "video/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享 ${item.title}"))
    }

    private fun copyToClipboard(value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("url", value))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private enum class HistoryPanel(val label: String, val subtitle: String) {
        BROWSING("浏览历史", "可单条删除或全部清空"),
        SEARCH("搜索历史", "记录搜索词，可再次搜索或删除"),
    }

    private enum class DateGroup(val label: String) {
        TODAY("今天"),
        WEEK("过去一周"),
        MONTH("过去一月"),
        ALL("全部");

        fun sinceMillis(now: Long = System.currentTimeMillis()): Long? {
            return when (this) {
                TODAY -> now - 86_400_000L
                WEEK -> now - 604_800_000L
                MONTH -> now - 2_592_000_000L
                ALL -> null
            }
        }
    }

    private data class BookmarkLoadPayload(
        val bookmarks: List<BookmarkItem>,
        val childCounts: Map<Long, Int>,
        val foldersById: Map<Long, BookmarkItem>,
    )

    private fun applyTheme() {
        val p = ThemeManager.currentPalette
        binding.root.setBackgroundColor(p.surface)
        binding.toolbar.setBackgroundColor(p.surface)
        binding.toolbar.setTitleTextColor(p.text)
        binding.toolbar.overflowIcon?.setTint(p.text)
        binding.searchInput.setTextColor(p.text)
        binding.searchInput.setHintTextColor(p.textMuted)
        binding.emptyText.setTextColor(p.textMuted)
        for (i in 0 until binding.categoryChips.childCount) { val chip = binding.categoryChips.getChildAt(i)
            (chip as? Chip)?.apply {
                setTextColor(p.text)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(p.surfaceAlt)
                setChipStrokeColorResource(R.color.aurora_line)
            }
        }
        val grad = ThemeManager.gradientDrawableIfAvailable()
        if (grad != null) {
            binding.root.background = grad
        }
    }

    private fun setupLibraryMenu() {
        val p = ThemeManager.currentPalette
        binding.toolbar.inflateMenu(R.menu.library_menu)
        binding.toolbar.overflowIcon?.setTint(p.text)
    }

    private fun importBookmarksFromHtml(uri: android.net.Uri) {
        lifecycleScope.launch {
            val html = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
            }
            if (html.isBlank()) {
                Toast.makeText(this@LibraryActivity, "无法读取书签文件", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val bookmarks = parseNetscapeBookmarks(html)
            if (bookmarks.isEmpty()) {
                Toast.makeText(this@LibraryActivity, "未找到书签数据", Toast.LENGTH_SHORT).show()
                return@launch
            }
            ThemeManager.styleAlertDialog(this@LibraryActivity, MaterialAlertDialogBuilder(this@LibraryActivity))
                .setTitle("导入书签")
                .setMessage("找到 ${bookmarks.size} 个书签，确定导入？")
                .setPositiveButton("导入") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        var imported = 0
                        bookmarks.forEach { (title, url) ->
                            try {
                                store.upsertBookmark(title, url, BrowserStore.ROOT_BOOKMARK_FOLDER_ID)
                                imported++
                            } catch (_: Exception) {}
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@LibraryActivity, "已导入 $imported 个书签", Toast.LENGTH_SHORT).show()
                            loadContent()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun parseNetscapeBookmarks(html: String): List<Pair<String, String>> {
        val bookmarks = mutableListOf<Pair<String, String>>()
        val pattern = Regex("""<A[^>]*HREF=["']([^"']+)["'][^>]*>([^<]+)</A>""", RegexOption.IGNORE_CASE)
        pattern.findAll(html).forEach { match ->
            val url = match.groupValues[1].trim()
            val title = match.groupValues[2].trim()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                bookmarks.add(title to url)
            }
        }
        return bookmarks
    }

    companion object {
        const val EXTRA_MODE = "mode"
        private const val DOWNLOAD_REFRESH_INTERVAL_MS = 1200L

        fun createIntent(context: Context, mode: LibraryMode): Intent {
            return Intent(context, LibraryActivity::class.java).putExtra(EXTRA_MODE, mode.value)
        }

        fun createIntent(context: Context, mode: LibraryMode, pageUrl: String?): Intent {
            return Intent(context, LibraryActivity::class.java)
                .putExtra(EXTRA_MODE, mode.value)
                .putExtra(EXTRA_PAGE_URL, pageUrl)
        }

        const val EXTRA_PAGE_URL = "page_url"
    }
}
