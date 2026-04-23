package com.fenglan.aurorabrowser

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.InputType
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fenglan.aurorabrowser.browser.BrowserDownloads
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.browser.ResourceTools
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.databinding.ActivityLibraryBinding
import com.fenglan.aurorabrowser.model.BookmarkItem
import com.fenglan.aurorabrowser.model.DownloadItem
import com.fenglan.aurorabrowser.model.FileCategory
import com.fenglan.aurorabrowser.model.HistoryItem
import com.fenglan.aurorabrowser.model.LibraryMode
import com.fenglan.aurorabrowser.model.ResourceItem
import com.fenglan.aurorabrowser.model.SearchHistoryItem
import com.fenglan.aurorabrowser.ui.LibraryAdapter
import com.fenglan.aurorabrowser.ui.LibraryRenderItem

class LibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private val store by lazy { BrowserStore(this) }
    private val preferences by lazy { BrowserPreferences(this) }
    private lateinit var mode: LibraryMode
    private lateinit var adapter: LibraryAdapter

    private var bookmarks: List<BookmarkItem> = emptyList()
    private var historyItems: List<HistoryItem> = emptyList()
    private var searchHistoryItems: List<SearchHistoryItem> = emptyList()
    private var resources: List<ResourceItem> = emptyList()
    private var downloads: List<DownloadItem> = emptyList()
    private var visibleResources: List<ResourceItem> = emptyList()
    private var visibleDownloads: List<DownloadItem> = emptyList()
    private var selectedCategory: FileCategory = FileCategory.ALL
    private var historyPanel: HistoryPanel = HistoryPanel.BROWSING
    private val selectedDownloadIds = linkedSetOf<Long>()
    private val promptedExportIds = linkedSetOf<Long>()
    private var selectionMode = false
    private var currentPageUrl: String? = null
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
        binding.toolbar.inflateMenu(R.menu.library_menu)
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

                else -> false
            }
        }

        setupCategoryChips()
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
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
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

    private fun setupCategoryChips() {
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
                        if (mode == LibraryMode.RESOURCES) {
                            renderResources()
                        } else {
                            renderDownloads()
                        }
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
                        loadContent()
                    }
                }
            }
            binding.categoryChips.addView(chip)
        }
    }

    private fun loadContent() {
        when (mode) {
            LibraryMode.BOOKMARKS -> {
                bookmarks = store.getBookmarks(currentBookmarkFolderId)
                val folderIds = bookmarks.filter { it.isFolder }.map { it.id }
                val childCounts = store.getBookmarkChildCounts(folderIds)
                val foldersById = if (folderIds.isEmpty()) emptyMap() else store.getAllBookmarkFolders().associateBy { it.id }
                adapter.submitList(
                    bookmarks.map {
                        LibraryRenderItem(
                            id = it.id,
                            title = it.title,
                            subtitle = if (it.isFolder) bookmarkFolderSubtitle(it, foldersById) else it.url.orEmpty(),
                            meta = if (it.isFolder) {
                                "文件夹 · ${childCounts[it.id] ?: 0} 项"
                            } else {
                                "添加于 ${ResourceTools.formatTime(it.createdAt)}"
                            },
                            primaryText = if (it.isFolder) "进入" else "打开",
                            secondaryText = if (it.isFolder) "管理" else "删除",
                            leadingIconRes = if (it.isFolder) R.drawable.ic_folder else null,
                        )
                    },
                )
                updateEmptyState(bookmarks.isEmpty(), if (currentBookmarkFolderId == BrowserStore.ROOT_BOOKMARK_FOLDER_ID) "暂无书签" else "这个文件夹还是空的")
            }

            LibraryMode.HISTORY -> {
                renderHistory()
            }

            LibraryMode.RESOURCES -> {
                resources = currentPageUrl?.let(store::getResourcesForPage) ?: emptyList()
                renderResources()
            }

            LibraryMode.DOWNLOADS -> {
                BrowserDownloads.refreshSystemDownloads(this, store)
                downloads = store.getDownloads()
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

    private fun renderHistory() {
        if (historyPanel == HistoryPanel.SEARCH) {
            searchHistoryItems = preferences.getSearchHistory()
            adapter.submitList(
                searchHistoryItems.map {
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
                    )
                },
            )
            updateEmptyState(searchHistoryItems.isEmpty(), "暂无搜索历史")
            return
        }

        historyItems = store.getHistory()
        adapter.submitList(
            historyItems.map {
                LibraryRenderItem(
                    id = it.id,
                    title = it.title,
                    subtitle = it.url,
                    meta = "访问于 ${ResourceTools.formatTime(it.visitedAt)}",
                    primaryText = "打开",
                    secondaryText = "删除",
                    leadingIconRes = R.drawable.ic_history,
                )
            },
        )
        updateEmptyState(historyItems.isEmpty(), "暂无浏览历史")
    }

    private fun renderResources() {
        visibleResources = resources
            .filter { selectedCategory == FileCategory.ALL || it.category == selectedCategory }
            .sortedWith(
                compareBy<ResourceItem> { resourcePriority(it) }
                    .thenByDescending { it.capturedAt },
            )
        adapter.submitList(
            visibleResources.map {
                LibraryRenderItem(
                    id = it.id,
                    title = it.title,
                    subtitle = it.url,
                    meta = "${it.category.label} · ${if (it.isStream) "流媒体" else "文件"} · ${ResourceTools.formatTime(it.capturedAt)}",
                    primaryText = "下载",
                    secondaryText = if (it.pageUrl.isNullOrBlank()) "删除" else "来源页",
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
                LibraryRenderItem(
                    id = it.id,
                    title = it.title,
                    subtitle = it.sourceUrl,
                    meta = buildString {
                        append(it.category.label)
                        append(" · ")
                        append(it.status)
                        append(" · ")
                        append(downloadSizeLabel(it))
                        it.progressPercent?.takeIf { progress -> progress in 0..99 }?.let { progress ->
                            append(" · ")
                            append("$progress%")
                        }
                        if (!it.errorMessage.isNullOrBlank()) {
                            append(" · ")
                            append(it.errorMessage)
                        }
                    },
                    primaryText = when {
                        isCompleted && it.downloadType == "hls" -> "播放/转码"
                        BrowserDownloads.canResume(it) -> "继续"
                        canPlayWhileDownloading -> "边下边播"
                        isCompleted -> "打开"
                        else -> "复制链接"
                    },
                    secondaryText = "管理",
                    primaryEnabled = canPlayWhileDownloading || it.status != "失败" || !it.sourceUrl.isBlank(),
                    progressPercent = it.progressPercent?.takeIf { progress -> progress in 0..99 },
                    selectionMode = selectionMode,
                    selected = selectedDownloadIds.contains(it.id),
                )
            },
        )
        updateEmptyState(visibleDownloads.isEmpty(), "暂无下载记录")
    }

    private fun updateEmptyState(isEmpty: Boolean, text: String) {
        binding.emptyText.isVisible = isEmpty
        binding.emptyText.text = text
    }

    private fun handleItemClick(position: Int) {
        when (mode) {
            LibraryMode.BOOKMARKS -> bookmarks.getOrNull(position)?.let {
                if (it.isFolder) {
                    openBookmarkFolder(it.id)
                } else {
                    openInBrowser(it.url)
                }
            }

            LibraryMode.DOWNLOADS -> if (selectionMode) {
                toggleDownloadSelection(position)
            }

            LibraryMode.HISTORY -> handlePrimaryAction(position)

            else -> Unit
        }
    }

    private fun handleItemLongClick(position: Int) {
        if (mode == LibraryMode.DOWNLOADS) {
            if (!selectionMode) {
                enterSelectionMode()
            }
            toggleDownloadSelection(position)
        }
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
                    searchHistoryItems.getOrNull(position)?.let { openSearchInBrowser(it.query) }
                } else {
                    historyItems.getOrNull(position)?.let { openInBrowser(it.url) }
                }
            }
            LibraryMode.RESOURCES -> visibleResources.getOrNull(position)?.let { item ->
                val message = BrowserDownloads.enqueueFromResource(this, store, item)
                toast(message)
            }

            LibraryMode.DOWNLOADS -> {
                val item = visibleDownloads.getOrNull(position) ?: return
                if (selectionMode) {
                    toggleDownloadSelection(position)
                    return
                }
                if (BrowserDownloads.canResume(item)) {
                    toast(BrowserDownloads.resumeDownload(this, store, item))
                    loadContent()
                    return
                }
                if (item.downloadType == "hls" && !item.localUri.isNullOrBlank()) {
                    if (item.downloadType == "hls") {
                        if (item.status.startsWith("已完成")) {
                            showCompletedHlsActions(item)
                        } else if (!BrowserDownloads.openDownload(this, item)) {
                            toast("无法播放正在下载的视频")
                        }
                        return
                    }
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
                    searchHistoryItems.getOrNull(position)?.let { confirmDeleteSearchHistory(it) }
                } else {
                    historyItems.getOrNull(position)?.let { confirmDeleteHistory(it) }
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
                    toggleDownloadSelection(position)
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
                FileCategory.DOCUMENT,
                FileCategory.ARCHIVE,
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
            item.category == FileCategory.DOCUMENT -> 4
            item.category == FileCategory.ARCHIVE -> 5
            else -> 6
        }
    }

    private fun enterSelectionMode() {
        if (mode != LibraryMode.DOWNLOADS) {
            return
        }
        selectionMode = true
        selectedDownloadIds.clear()
        updateToolbarMenu()
        renderDownloads()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedDownloadIds.clear()
        updateToolbarMenu()
        if (mode == LibraryMode.DOWNLOADS) {
            renderDownloads()
        }
    }

    private fun toggleDownloadSelection(position: Int) {
        val item = visibleDownloads.getOrNull(position) ?: return
        if (!selectedDownloadIds.add(item.id)) {
            selectedDownloadIds.remove(item.id)
        }
        if (selectedDownloadIds.isEmpty()) {
            exitSelectionMode()
        } else {
            updateToolbarMenu()
            renderDownloads()
        }
    }

    private fun toggleSelectAll() {
        if (!selectionMode || mode != LibraryMode.DOWNLOADS) {
            return
        }
        if (selectedDownloadIds.size == visibleDownloads.size && visibleDownloads.isNotEmpty()) {
            selectedDownloadIds.clear()
        } else {
            selectedDownloadIds.clear()
            selectedDownloadIds.addAll(visibleDownloads.map { it.id })
        }
        if (selectedDownloadIds.isEmpty()) {
            exitSelectionMode()
        } else {
            updateToolbarMenu()
            renderDownloads()
        }
    }

    private fun selectedDownloads(): List<DownloadItem> {
        return downloads.filter { selectedDownloadIds.contains(it.id) }
    }

    private fun updateToolbarMenu() {
        val menu = binding.toolbar.menu
        val isDownloads = mode == LibraryMode.DOWNLOADS
        menu.findItem(R.id.action_clear).isVisible = !selectionMode && mode != LibraryMode.BOOKMARKS
        menu.findItem(R.id.action_refresh).isVisible = !selectionMode
        menu.findItem(R.id.action_create_folder).isVisible = !selectionMode && mode == LibraryMode.BOOKMARKS
        menu.findItem(R.id.action_multi_select).isVisible = isDownloads && !selectionMode && downloads.isNotEmpty()
        menu.findItem(R.id.action_select_all).isVisible = isDownloads && selectionMode
        menu.findItem(R.id.action_batch_actions).isVisible = isDownloads && selectionMode && selectedDownloadIds.isNotEmpty()
        binding.toolbar.title = when {
            selectionMode -> "已选择 ${selectedDownloadIds.size} 项"
            mode == LibraryMode.HISTORY -> historyPanel.label
            else -> mode.title
        }
        binding.toolbar.subtitle = when {
            selectionMode -> "批量管理下载任务"
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
            MaterialAlertDialogBuilder(this)
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
        labels += "复制链接"
        actions += {
            copyToClipboard(item.sourceUrl)
            toast("下载链接已复制")
        }
        labels += "删除"
        actions += { confirmDeleteDownload(item) }

        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchActions() {
        val selectedItems = selectedDownloads()
        if (selectedItems.isEmpty()) {
            return
        }

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

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

        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        return when {
            item.downloadedBytes != null && item.fileSizeBytes != null && item.fileSizeBytes > 0 && item.downloadedBytes < item.fileSizeBytes -> {
                "${ResourceTools.formatBytes(item.downloadedBytes)}/${ResourceTools.formatBytes(item.fileSizeBytes)}"
            }

            item.fileSizeBytes != null -> ResourceTools.formatBytes(item.fileSizeBytes)
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
