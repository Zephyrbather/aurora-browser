package com.fenglan.aurorabrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fenglan.aurorabrowser.browser.BrowserDownloads
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.browser.BrowserThemePalette
import com.fenglan.aurorabrowser.browser.BrowserThemeTools
import com.fenglan.aurorabrowser.browser.MagnetSearchClient
import com.fenglan.aurorabrowser.browser.MagnetSearchOutcome
import com.fenglan.aurorabrowser.browser.MagnetSearchResult
import com.fenglan.aurorabrowser.browser.MagnetSearchSource
import com.fenglan.aurorabrowser.browser.MagnetSourceHealth
import com.fenglan.aurorabrowser.browser.MagnetSourceStatus
import com.fenglan.aurorabrowser.browser.MagnetSearchSources
import com.fenglan.aurorabrowser.browser.MagnetSearchStateTools
import com.fenglan.aurorabrowser.browser.TorrentTools
import com.fenglan.aurorabrowser.browser.ThemeManager
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.databinding.ActivityMagnetSearchBinding
import com.fenglan.aurorabrowser.model.P2pDownloadEngine
import com.fenglan.aurorabrowser.ui.MagnetSearchAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MagnetSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMagnetSearchBinding
    private lateinit var adapter: MagnetSearchAdapter
    private val preferences by lazy { BrowserPreferences(this) }
    private val store by lazy { BrowserStore.getInstance(this) }
    private var sources: List<MagnetSearchSource> = emptyList()
    private var selectedSourceIds: LinkedHashSet<String> = linkedSetOf()
    private var latestOutcomes: List<MagnetSearchOutcome> = emptyList()
    private var currentResultSourceId: String = RESULT_ALL_SOURCE_ID
    private var currentSearchUrls: Map<String, String> = emptyMap()
    private var activeSearchSourceIds: Set<String> = emptySet()
    private var searchJob: Job? = null
    private var sourceMenuPopup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMagnetSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MagnetSearchAdapter(
            onCopyClick = ::copyResult,
            onCopyMagnetClick = ::copyMagnetResult,
            onCloudPlayClick = ::cloudPlayResult,
            onOpenClick = ::openResult,
        )
        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.toolbar.title = "磁力搜索"
        binding.toolbar.subtitle = "智能排序并发检索"
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val savedOutcomes = preferences.getLastMagnetSearchOutcomes()
        val initialQuery = intent.getStringExtra(EXTRA_QUERY)
            ?.takeIf { it.isNotBlank() }
            ?: preferences.getLastMagnetSearchQuery()
        binding.queryInput.setText(initialQuery)
        binding.queryInput.setSelection(binding.queryInput.text?.length ?: 0)
        binding.queryInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                runSearch()
                true
            } else {
                false
            }
        }
        binding.searchButton.setOnClickListener { runSearch() }
        binding.historyButton.setOnClickListener { showHistoryDialog() }
        binding.sourceMenuButton.setOnClickListener { showSourceMenu() }

        applyTheme()
        reloadSources()

        if (savedOutcomes.isNotEmpty()) {
            currentResultSourceId = preferences.getLastMagnetSourceId().ifBlank { RESULT_ALL_SOURCE_ID }
            renderSearchOutcomes(savedOutcomes)
            binding.progressBar.isVisible = false
        } else if (!binding.queryInput.text.isNullOrBlank()) {
            runSearch()
        }
    }

    override fun onDestroy() {
        sourceMenuPopup?.dismiss()
        searchJob?.cancel()
        super.onDestroy()
    }

    private fun applyTheme() {
        val palette = BrowserThemeTools.paletteFor(preferences.getSettings().themeMode)
        adapter.setPalette(palette)
        binding.root.setBackgroundColor(palette.surface)
        binding.toolbar.setBackgroundColor(palette.surface)
        binding.toolbar.setTitleTextColor(palette.text)
        binding.toolbar.setSubtitleTextColor(palette.textMuted)
        binding.statusText.setTextColor(palette.textMuted)
        binding.emptyText.setTextColor(palette.textMuted)
        binding.sourceStateTitle.setTextColor(palette.text)
        binding.sourceStateMessage.setTextColor(palette.textMuted)
        binding.sourceStateCard.setCardBackgroundColor(palette.surfaceAlt)
        binding.sourceStateCard.strokeColor = palette.line
        binding.progressBar.progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        binding.progressBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.line)
        binding.queryLayout.boxBackgroundColor = palette.surfaceAlt
        binding.queryLayout.boxStrokeColor = palette.line
        binding.queryLayout.defaultHintTextColor = android.content.res.ColorStateList.valueOf(palette.textMuted)
        binding.queryInput.setTextColor(palette.text)
        binding.queryInput.setHintTextColor(palette.textMuted)
        styleIconButton(binding.searchButton, filled = true)
        listOf(binding.historyButton, binding.sourceMenuButton).forEach {
            styleIconButton(it, filled = false)
        }
        binding.openSourceButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
        binding.openSourceButton.setTextColor(Color.WHITE)
        binding.retrySourceButton.strokeColor = ColorStateList.valueOf(palette.line)
        binding.retrySourceButton.setTextColor(palette.text)
    }

    private fun styleIconButton(button: MaterialButton, filled: Boolean) {
        val palette = BrowserThemeTools.paletteFor(preferences.getSettings().themeMode)
        button.cornerRadius = dp(14)
        button.iconSize = dp(20)
        button.iconTint = ColorStateList.valueOf(if (filled) Color.WHITE else palette.text)
        button.backgroundTintList = ColorStateList.valueOf(if (filled) palette.accent else palette.surfaceAlt)
        button.strokeWidth = if (filled) 0 else 1
        button.strokeColor = ColorStateList.valueOf(palette.line)
        button.rippleColor = ColorStateList.valueOf(withAlpha(palette.accent, 36))
    }

    private fun reloadSources() {
        sources = preferences.getMagnetSources()
        selectedSourceIds = preferences.getSelectedMagnetSourceIds()
            .filter { id -> sources.any { it.id == id } }
            .toCollection(linkedSetOf())
        renderSourceMenuButton()
    }

    private fun renderSourceMenuButton() {
        binding.sourceMenuButton.contentDescription = "磁力源管理，已启用 ${selectedSourceIds.size}/${sources.size} 个源"
        updateSelectedStatus()
    }

    private fun showSourceMenu() {
        sourceMenuPopup?.dismiss()
        val palette = BrowserThemeTools.paletteFor(preferences.getSettings().themeMode)
        val health = preferences.getMagnetSourceHealth()
        val popupWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(380))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.line)
            }
        }
        val headerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        headerRow.addView(
            TextView(this).apply {
                text = "磁力源"
                setTextColor(palette.text)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val countText = TextView(this).apply {
            text = sourceMenuCountText()
            setTextColor(palette.textMuted)
            textSize = 12f
        }
        headerRow.addView(countText)
        root.addView(headerRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(
            createSourceMenuActionButton("启用全部", R.drawable.ic_select_all, filled = false) {
                enableAllSources()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        actionRow.addView(
            createSourceMenuActionButton("停用全部", R.drawable.ic_close, filled = false) {
                disableAllSources()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) },
        )
        actionRow.addView(
            createSourceMenuActionButton("添加", R.drawable.ic_add, filled = true) {
                sourceMenuPopup?.dismiss()
                showAddSourceDialog()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) },
        )
        root.addView(
            actionRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            },
        )

        root.addView(
            View(this).apply { setBackgroundColor(palette.line) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            },
        )

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (sources.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = "暂无磁力源"
                    setTextColor(palette.textMuted)
                    textSize = 13f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)),
            )
        } else {
            sources.forEach { source ->
                list.addView(
                    createSourceMenuRow(source, palette, health[source.id]) {
                        countText.text = sourceMenuCountText()
                    },
                )
            }
        }
        val scrollHeight = (sources.size.coerceAtLeast(1) * dp(62))
            .coerceAtMost((resources.displayMetrics.heightPixels * 0.52f).toInt())
            .coerceAtLeast(dp(64))
        root.addView(
            ScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(list)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, scrollHeight),
        )

        val popup = PopupWindow(root, popupWidth, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
        }
        sourceMenuPopup = popup
        val xOffset = binding.sourceMenuButton.width - popupWidth
        popup.showAsDropDown(binding.sourceMenuButton, xOffset, dp(6))
    }

    private fun createSourceMenuActionButton(
        label: String,
        iconRes: Int,
        filled: Boolean,
        onClick: () -> Unit,
    ): MaterialButton {
        val palette = BrowserThemeTools.paletteFor(preferences.getSettings().themeMode)
        return MaterialButton(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(14)
            setPadding(dp(4), 0, dp(4), 0)
            icon = getDrawable(iconRes)
            iconSize = dp(18)
            iconPadding = dp(4)
            iconTint = ColorStateList.valueOf(if (filled) Color.WHITE else palette.text)
            backgroundTintList = ColorStateList.valueOf(if (filled) palette.accent else palette.surfaceAlt)
            strokeWidth = if (filled) 0 else 1
            strokeColor = ColorStateList.valueOf(palette.line)
            setTextColor(if (filled) Color.WHITE else palette.text)
            rippleColor = ColorStateList.valueOf(withAlpha(palette.accent, 36))
            setOnClickListener { onClick() }
        }
    }

    private fun createSourceMenuRow(
        source: MagnetSearchSource,
        palette: BrowserThemePalette,
        health: MagnetSourceHealth?,
        onSelectionChanged: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(0, dp(4), 0, dp(4))
        }
        val checkBox = MaterialCheckBox(this).apply {
            isChecked = source.id in selectedSourceIds
            contentDescription = "启用或停用 ${source.label}"
            buttonTintList = ColorStateList.valueOf(palette.accent)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedSourceIds.add(source.id)
                } else {
                    selectedSourceIds.remove(source.id)
                }
                preferences.setSelectedMagnetSourceIds(selectedSourceIds)
                renderSourceMenuButton()
                onSelectionChanged()
            }
        }
        row.addView(checkBox, LinearLayout.LayoutParams(dp(48), dp(48)))

        val textStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textStack.addView(
            TextView(this).apply {
                text = source.label
                setTextColor(palette.text)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        textStack.addView(
            TextView(this).apply {
                text = sourceDetailText(source, health)
                setTextColor(palette.textMuted)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        row.addView(textStack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (source.isCustom) {
            row.addView(
                ImageButton(this).apply {
                    setImageResource(R.drawable.ic_delete)
                    contentDescription = "删除磁力源 ${source.label}"
                    imageTintList = ColorStateList.valueOf(palette.textMuted)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                    }
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener {
                        sourceMenuPopup?.dismiss()
                        confirmDeleteSource(source)
                    }
                },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) },
            )
        }
        row.setOnClickListener {
            checkBox.isChecked = !checkBox.isChecked
        }
        return row
    }

    private fun applyChipStyle(chip: Chip, compact: Boolean) {
        val palette = BrowserThemeTools.paletteFor(preferences.getSettings().themeMode)
        chip.chipMinHeight = dp(if (compact) 34 else 36).toFloat()
        chip.textSize = if (compact) 12f else 13f
        chip.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        chip.chipCornerRadius = dp(14).toFloat()
        chip.chipStrokeWidth = 1f
        chip.chipStrokeColor = ColorStateList.valueOf(palette.line)
        chip.chipBackgroundColor = ColorStateList.valueOf(palette.surfaceAlt)
        chip.setTextColor(palette.text)
        chip.checkedIconTint = ColorStateList.valueOf(palette.accent)
        chip.closeIconTint = ColorStateList.valueOf(palette.textMuted)
        chip.rippleColor = ColorStateList.valueOf(withAlpha(palette.accent, 36))
    }

    private fun updateSelectedStatus() {
        val selectedCount = selectedSourceIds.size
        val resultCount = adapter.currentList.size
        binding.statusText.text = if (resultCount > 0) {
            "已选 $selectedCount 个源，当前 $resultCount 条结果"
        } else {
            "已选 $selectedCount 个源，输入关键词后逐个检索"
        }
    }

    private fun sourceMenuCountText(): String {
        return "已启用 ${selectedSourceIds.size}/${sources.size}"
    }

    private fun enableAllSources() {
        selectedSourceIds.clear()
        selectedSourceIds.addAll(sources.map { it.id })
        preferences.setSelectedMagnetSourceIds(selectedSourceIds)
        renderSourceMenuButton()
        refreshSourceMenu()
    }

    private fun disableAllSources() {
        selectedSourceIds.clear()
        preferences.setSelectedMagnetSourceIds(selectedSourceIds)
        renderSourceMenuButton()
        refreshSourceMenu()
    }

    private fun refreshSourceMenu() {
        if (sourceMenuPopup?.isShowing == true) {
            sourceMenuPopup?.dismiss()
            binding.sourceMenuButton.post { showSourceMenu() }
        }
    }

    private fun sourceDetailText(source: MagnetSearchSource, health: MagnetSourceHealth?): String {
        val type = if (source.isCustom) "自定义" else "内置"
        val host = sourceHostText(source)
        val status = health?.let { sourceHealthText(it) }
        return listOfNotNull(type, host, status).joinToString(" · ")
    }

    private fun sourceHostText(source: MagnetSearchSource): String? {
        return runCatching {
            Uri.parse(source.template).host
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun sourceHealthText(health: MagnetSourceHealth): String {
        val status = when (health.lastStatus) {
            MagnetSourceStatus.SUCCESS -> "上次 ${health.lastResultCount} 条"
            MagnetSourceStatus.EMPTY -> "上次无结果"
            MagnetSourceStatus.FAILED -> "上次失败"
            MagnetSourceStatus.VERIFICATION -> "上次需验证"
        }
        return "$status，${durationText(health.lastDurationMs)}"
    }

    private fun durationText(durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        if (safeMs < 1_000L) {
            return "${safeMs}ms"
        }
        if (safeMs < 60_000L) {
            val tenths = (safeMs + 50L) / 100L
            return "${tenths / 10L}.${tenths % 10L}s"
        }
        return "${safeMs / 1_000L}s"
    }

    private fun runSearch() {
        val query = binding.queryInput.text?.toString().orEmpty().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedSources = preferences.rankMagnetSourcesForSearch(
            sources.filter { it.id in selectedSourceIds },
        )
        if (selectedSources.isEmpty()) {
            Toast.makeText(this, "请在右上角磁力源中至少启用一个源", Toast.LENGTH_SHORT).show()
            return
        }
        searchJob?.cancel()
        preferences.saveMagnetSearchHistory(query)
        preferences.saveLastMagnetSearchQuery(query)
        preferences.saveLastMagnetSourceId(RESULT_ALL_SOURCE_ID)
        preferences.saveLastMagnetSearchOutcomes(emptyList())
        binding.progressBar.isVisible = true
        binding.emptyText.isVisible = false
        binding.resultSourceScroller.isVisible = false
        binding.resultSourceChipGroup.removeAllViews()
        latestOutcomes = emptyList()
        currentResultSourceId = RESULT_ALL_SOURCE_ID
        currentSearchUrls = selectedSources.associate { it.id to it.buildSearchUrl(query) }
        activeSearchSourceIds = emptySet()
        hideSourceState()
        adapter.submitList(emptyList())
        binding.statusText.text = "准备检索 ${selectedSources.size} 个源"
        searchJob = lifecycleScope.launch {
            val collected = mutableListOf<MagnetSearchOutcome>()
            val runningSourceIds = linkedSetOf<String>()
            val completedCount = intArrayOf(0)
            val semaphore = Semaphore(SEARCH_SOURCE_CONCURRENCY)
            selectedSources.map { source ->
                async {
                    semaphore.withPermit {
                        ensureActive()
                        val startAt = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            runningSourceIds.add(source.id)
                            activeSearchSourceIds = runningSourceIds.toSet()
                            binding.progressBar.isVisible = true
                            binding.statusText.text = buildRunningStatus(
                                runningSourceIds = runningSourceIds,
                                completed = completedCount.first(),
                                total = selectedSources.size,
                            )
                            renderResultSourceChips()
                        }
                        val outcome = withContext(Dispatchers.IO) {
                            searchSourceSafely(source, query)
                        }
                        val durationMs = System.currentTimeMillis() - startAt
                        withContext(Dispatchers.Main) {
                            preferences.recordMagnetSourceHealth(source.id, outcome, durationMs)
                            runningSourceIds.remove(source.id)
                            completedCount[0] = completedCount.first() + 1
                            collected.removeAll { it.source.id == source.id }
                            collected.add(outcome)
                            latestOutcomes = orderedOutcomes(selectedSources, collected)
                            activeSearchSourceIds = runningSourceIds.toSet()
                            if (currentResultSourceId == RESULT_ALL_SOURCE_ID && outcome.results.isNotEmpty() && adapter.currentList.isEmpty()) {
                                currentResultSourceId = source.id
                                preferences.saveLastMagnetSourceId(source.id)
                            }
                            persistLastSearchSnapshot()
                            renderPartialSearchOutcomes(
                                completed = latestOutcomes,
                                totalSources = selectedSources.size,
                                currentIndex = completedCount.first(),
                                runningSourceIds = runningSourceIds,
                            )
                        }
                    }
                }
            }.awaitAll()
            activeSearchSourceIds = emptySet()
            renderSearchOutcomes(latestOutcomes)
        }
    }

    private fun renderPartialSearchOutcomes(
        completed: List<MagnetSearchOutcome>,
        totalSources: Int,
        currentIndex: Int,
        runningSourceIds: Set<String>,
    ) {
        renderResultSourceChips()
        submitFilteredResults()
        val totalResults = completed.sumOf { it.results.size }
        val failed = completed.count { it.errorMessage != null }
        val running = runningSourceIds
            .mapNotNull { id -> sources.firstOrNull { it.id == id }?.label }
            .take(SEARCH_SOURCE_CONCURRENCY)
            .joinToString("、")
        binding.statusText.text = buildString {
            append("已完成 $currentIndex/$totalSources 个源，$totalResults 条结果，$failed 个源需处理")
            if (running.isNotBlank()) append("；检索中：$running")
        }
    }

    private fun renderSearchOutcomes(outcomes: List<MagnetSearchOutcome>) {
        latestOutcomes = outcomes
        activeSearchSourceIds = emptySet()
        renderResultSourceChips()
        submitFilteredResults()
        binding.progressBar.isVisible = false
        val totalResults = outcomes.sumOf { it.results.size }
        binding.emptyText.isVisible = binding.sourceStateCard.isVisible.not() && totalResults == 0
        if (totalResults == 0) {
            binding.emptyText.text = "没有搜索到可直接打开或复制的结果"
        }
        val failed = outcomes.count { it.errorMessage != null }
        val successfulSources = outcomes
            .filter { it.errorMessage == null }
            .joinToString("、") { it.source.label }
        binding.statusText.text = buildString {
            append("完成：${outcomes.size} 个源，${totalResults} 条结果")
            if (successfulSources.isNotBlank()) append("；成功源：$successfulSources")
            if (failed > 0) append("，$failed 个源失败")
        }
        preferences.saveLastMagnetSearchOutcomes(outcomes)
        val query = binding.queryInput.text?.toString().orEmpty().trim()
        if (query.isNotBlank()) {
            preferences.saveLastMagnetSearchQuery(query)
        }
    }

    private fun persistLastSearchSnapshot() {
        val query = binding.queryInput.text?.toString().orEmpty().trim()
        if (query.isNotBlank()) {
            preferences.saveLastMagnetSearchQuery(query)
        }
        preferences.saveLastMagnetSearchOutcomes(latestOutcomes)
    }

    private fun orderedOutcomes(
        orderedSources: List<MagnetSearchSource>,
        outcomes: List<MagnetSearchOutcome>,
    ): List<MagnetSearchOutcome> {
        val order = orderedSources.mapIndexed { index, source -> source.id to index }.toMap()
        return outcomes.sortedBy { order[it.source.id] ?: Int.MAX_VALUE }
    }

    private fun buildRunningStatus(
        runningSourceIds: Set<String>,
        completed: Int,
        total: Int,
    ): String {
        val labels = runningSourceIds
            .mapNotNull { id -> sources.firstOrNull { it.id == id }?.label }
            .joinToString("、")
        return if (labels.isBlank()) {
            "正在检索：$completed/$total"
        } else {
            "正在检索：$labels（$completed/$total 已完成）"
        }
    }

    private fun renderResultSourceChips() {
        val outcomes = latestOutcomes
        binding.resultSourceChipGroup.removeAllViews()
        val hasActiveSearch = activeSearchSourceIds.isNotEmpty()
        binding.resultSourceScroller.isVisible = outcomes.isNotEmpty() || hasActiveSearch
        if (outcomes.isEmpty() && !hasActiveSearch) {
            return
        }
        val totalResults = outcomes.sumOf { it.results.size }
        binding.resultSourceChipGroup.addView(
            createResultSourceChip(RESULT_ALL_SOURCE_ID, "全部 $totalResults"),
        )
        val outcomeIds = outcomes.map { it.source.id }.toSet()
        activeSearchSourceIds
            .filterNot { it in outcomeIds }
            .forEach { activeId ->
                val activeSource = sources.firstOrNull { it.id == activeId } ?: return@forEach
                binding.resultSourceChipGroup.addView(
                    createResultSourceChip(activeSource.id, "${activeSource.label} 检索中"),
                )
            }
        outcomes.forEach { outcome ->
            val suffix = when {
                outcome.source.id in activeSearchSourceIds -> "检索中"
                outcome.requiresVerification -> "验证"
                outcome.errorMessage != null -> "失败"
                else -> outcome.results.size.toString()
            }
            binding.resultSourceChipGroup.addView(
                createResultSourceChip(outcome.source.id, "${outcome.source.label} $suffix"),
            )
        }
    }

    private fun createResultSourceChip(sourceId: String, label: String): Chip {
        return Chip(this).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            isChecked = currentResultSourceId == sourceId
            applyChipStyle(this, compact = true)
            setOnClickListener {
                currentResultSourceId = sourceId
                preferences.saveLastMagnetSourceId(sourceId)
                renderResultSourceChips()
                submitFilteredResults()
            }
        }
    }

    private fun submitFilteredResults() {
        val results = if (currentResultSourceId == RESULT_ALL_SOURCE_ID) {
            latestOutcomes.flatMap { it.results }
        } else {
            latestOutcomes.firstOrNull { it.source.id == currentResultSourceId }?.results.orEmpty()
        }
        adapter.submitList(results)
        val outcome = latestOutcomes.firstOrNull { it.source.id == currentResultSourceId }
        val isActiveSource = currentResultSourceId in activeSearchSourceIds
        if (currentResultSourceId != RESULT_ALL_SOURCE_ID && (outcome?.errorMessage != null || isActiveSource)) {
            binding.emptyText.isVisible = false
            renderSourceState(outcome, isActiveSource)
            return
        }
        hideSourceState()
        binding.emptyText.isVisible = latestOutcomes.isNotEmpty() && results.isEmpty()
        if (binding.emptyText.isVisible) {
            binding.emptyText.text = when {
                currentResultSourceId == RESULT_ALL_SOURCE_ID -> "没有搜索到可直接打开或复制的结果"
                outcome != null -> "${outcome.source.label} 暂无可展示结果"
                isActiveSource -> "正在检索此源"
                else -> "暂无结果"
            }
        }
    }

    private fun renderSourceState(outcome: MagnetSearchOutcome?, active: Boolean) {
        val source = outcome?.source ?: sources.firstOrNull { it.id == currentResultSourceId }
        binding.sourceStateCard.isVisible = true
        binding.openSourceButton.isVisible = outcome?.requiresVerification == true
        binding.retrySourceButton.isVisible = outcome != null && !active
        binding.sourceStateTitle.text = when {
            active && source != null -> "正在检索 ${source.label}"
            outcome?.requiresVerification == true -> "${outcome.source.label} 需要验证"
            outcome?.errorMessage != null -> "${outcome.source.label} 暂不可用"
            else -> "正在检索"
        }
        binding.sourceStateMessage.text = when {
            active -> "此源正在检索，完成后会立即显示结果。"
            outcome?.requiresVerification == true -> "源站要求浏览器验证。打开源站完成验证后返回本页重试该源。"
            outcome?.errorMessage != null -> outcome.errorMessage
            else -> ""
        }
        binding.openSourceButton.setOnClickListener {
            val url = source?.id?.let { currentSearchUrls[it] }
                ?: source?.buildSearchUrl(binding.queryInput.text?.toString().orEmpty())
                ?: return@setOnClickListener
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_URL, url)
                    putExtra(MainActivity.EXTRA_USER_INITIATED, true)
                    putExtra(MainActivity.EXTRA_FINISH_ON_BACK, true)
                },
            )
        }
        binding.retrySourceButton.setOnClickListener {
            source?.let { retrySingleSource(it) }
        }
    }

    private fun hideSourceState() {
        binding.sourceStateCard.isVisible = false
    }

    private val displayDensity: Float by lazy { resources.displayMetrics.density }

    private fun dp(value: Int): Int {
        return (value * displayDensity + 0.5f).toInt()
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun retrySingleSource(source: MagnetSearchSource) {
        val query = binding.queryInput.text?.toString().orEmpty().trim()
        if (query.isBlank()) {
            return
        }
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            activeSearchSourceIds = setOf(source.id)
            currentResultSourceId = source.id
            currentSearchUrls = currentSearchUrls + (source.id to source.buildSearchUrl(query))
            binding.progressBar.isVisible = true
            renderResultSourceChips()
            submitFilteredResults()
            binding.statusText.text = "正在重试：${source.label}"
            val outcome = withContext(Dispatchers.IO) {
                val startAt = System.currentTimeMillis()
                val result = searchSourceSafely(source, query)
                preferences.recordMagnetSourceHealth(source.id, result, System.currentTimeMillis() - startAt)
                result
            }
            latestOutcomes = (latestOutcomes.filterNot { it.source.id == source.id } + outcome)
            activeSearchSourceIds = emptySet()
            renderSearchOutcomes(latestOutcomes)
        }
    }

    private fun copyResult(result: MagnetSearchResult) {
        val value = result.magnetUrl ?: result.torrentUrl.orEmpty()
        if (value.isBlank()) {
            Toast.makeText(this, "未解析到可复制的磁力或种子链接", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(result.title, value))
        Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
        maybePromptTorrentDownload(value, result.title)
    }

    private suspend fun searchSourceSafely(source: MagnetSearchSource, query: String): MagnetSearchOutcome {
        return runCatching {
            MagnetSearchClient.search(source, query, this@MagnetSearchActivity)
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            MagnetSearchOutcome(source, emptyList(), "站点暂时不可访问")
        }
    }

    private fun copyMagnetResult(result: MagnetSearchResult) {
        val magnet = result.magnetUrl
        if (magnet.isNullOrBlank()) {
            Toast.makeText(this, "此结果没有可直接复制的磁力链接", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(result.title, magnet))
        Toast.makeText(this, "已复制磁力链接", Toast.LENGTH_SHORT).show()
        maybePromptTorrentDownload(magnet, result.title)
    }

    private fun cloudPlayResult(result: MagnetSearchResult) {
        val magnet = result.magnetUrl
        if (magnet.isNullOrBlank()) {
            Toast.makeText(this, "此结果没有磁力链接，请先打开详情页获取", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_URL, CLOUD_PLAY_DISPLAY_URL)
                putExtra(MainActivity.EXTRA_INLINE_HTML, buildCloudPlayHtml(result))
                putExtra(MainActivity.EXTRA_USER_INITIATED, true)
                putExtra(MainActivity.EXTRA_FINISH_ON_BACK, true)
            },
        )
    }

    private fun openResult(result: MagnetSearchResult) {
        val target = result.openUrl
        if (target.isBlank()) {
            Toast.makeText(this, "没有可打开的链接", Toast.LENGTH_SHORT).show()
            return
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            if (TorrentTools.isTorrentUrl(target)) {
                showTorrentDownloadDialog(target, result.title, copied = false)
                return
            }
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_URL, target)
                    putExtra(MainActivity.EXTRA_USER_INITIATED, true)
                    putExtra(MainActivity.EXTRA_FINISH_ON_BACK, true)
                },
            )
            return
        }
        TorrentTools.extractValidMagnet(target)?.let {
            showTorrentDownloadDialog(it, result.title, copied = false)
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.onFailure {
            copyResult(result)
            Toast.makeText(this, "未找到可打开此链接的应用，已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybePromptTorrentDownload(value: String, title: String?) {
        when {
            TorrentTools.extractValidMagnet(value) != null -> showTorrentDownloadDialog(value, title, copied = true)
            TorrentTools.isTorrentUrl(value) -> showTorrentDownloadDialog(value, title, copied = true)
        }
    }

    private fun showTorrentDownloadDialog(value: String, title: String?, copied: Boolean) {
        val magnet = TorrentTools.extractValidMagnet(value)
        val isMagnet = magnet != null
        val currentEngine = preferences.getP2pDownloadEngine()

        val privacyCheck = android.widget.CheckBox(this).apply {
            text = getString(R.string.download_to_privacy)
            isChecked = preferences.getDefaultPrivacyDownload()
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(android.widget.TextView(this@MagnetSearchActivity).apply {
                text = (title ?: value).take(160)
                setTextColor(ThemeManager.currentPalette.textMuted)
                textSize = 14f
                setPadding(0, 0, 0, dp(8))
            })
            addView(privacyCheck)
        }

        ThemeManager.styleAlertDialog(this@MagnetSearchActivity, MaterialAlertDialogBuilder(this@MagnetSearchActivity))
            .setTitle(if (isMagnet) "下载磁力链接？" else "下载种子文件？")
            .setView(container)
            .setNegativeButton(if (copied) "仅复制" else "取消", null)
            .setNeutralButton(currentEngine.alternate().actionLabel) { _, _ ->
                enqueueTorrentDownload(value, title, magnet, currentEngine.alternate(), privacyCheck.isChecked)
            }
            .setPositiveButton(currentEngine.actionLabel) { _, _ ->
                enqueueTorrentDownload(value, title, magnet, currentEngine, privacyCheck.isChecked)
            }
            .show()
    }

    private fun enqueueTorrentDownload(value: String, title: String?, magnet: String?, engine: P2pDownloadEngine, isPrivate: Boolean = false) {
        val message = if (magnet != null) {
            BrowserDownloads.enqueueMagnetDownload(
                context = this,
                store = store,
                magnet = magnet,
                title = title,
                pageUrl = null,
                engine = engine,
                isPrivate = isPrivate,
            )
        } else if (engine == P2pDownloadEngine.EXTERNAL) {
            if (BrowserDownloads.openP2pWithExternalApp(this, value, "application/x-bittorrent")) {
                "已交给外部 BT 应用"
            } else {
                "未找到可处理此链接的 BT 应用"
            }
        } else {
            BrowserDownloads.enqueue(
                context = this,
                store = store,
                url = value,
                title = title,
                pageUrl = null,
                mimeType = "application/x-bittorrent",
                contentDisposition = null,
                referer = null,
                userAgent = null,
                isPrivate = isPrivate,
            )
        }
        if (message.startsWith("未找到")) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("magnet", value))
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showAddSourceDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 8, 2, 0)
        }
        val nameLayout = TextInputLayout(this).apply {
            hint = "源名称"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
        }
        val nameInput = TextInputEditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText("自定义磁力源")
        }
        nameLayout.addView(nameInput)
        container.addView(nameLayout)

        val templateLayout = TextInputLayout(this).apply {
            hint = "搜索模板"
            helperText = "示例：${MagnetSearchSources.EXAMPLE_TEMPLATE}；也支持 %s、{query+}、{query%20}、{query-}"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
        }
        val templateInput = TextInputEditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(MagnetSearchSources.EXAMPLE_TEMPLATE)
            setSelection(text?.length ?: 0)
        }
        templateLayout.addView(templateInput)
        container.addView(templateLayout)

        val dialog = ThemeManager.styleAlertDialog(this@MagnetSearchActivity, MaterialAlertDialogBuilder(this@MagnetSearchActivity))
            .setTitle("添加磁力源")
            .setMessage("模板必须以 http 或 https 开头，并包含关键词占位符。")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val source = preferences.addCustomMagnetSource(
                    label = nameInput.text?.toString().orEmpty(),
                    template = templateInput.text?.toString().orEmpty(),
                )
                if (source == null) {
                    templateLayout.error = "请检查名称和模板格式"
                    return@setOnClickListener
                }
                selectedSourceIds.add(source.id)
                preferences.setSelectedMagnetSourceIds(selectedSourceIds)
                Toast.makeText(this, "已添加磁力源：${source.label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                reloadSources()
            }
        }
        dialog.show()
    }

    private fun buildCloudPlayHtml(result: MagnetSearchResult): String {
        val magnet = result.magnetUrl.orEmpty()
        val title = result.title.ifBlank { "Aurora 云播" }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <title>${htmlEscape(title)}</title>
              <style>
                html,body{margin:0;width:100%;height:100%;background:#0b1020;color:#f8fafc;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;}
                body{display:flex;flex-direction:column;}
                header{padding:12px 14px;border-bottom:1px solid rgba(255,255,255,.12);font-size:14px;line-height:1.35;}
                #player{flex:1;min-height:320px;}
                #fallback{display:none;padding:12px 14px;color:#cbd5e1;font-size:13px;line-height:1.55;}
                a{color:#7dd3fc;}
              </style>
            </head>
            <body>
              <header>${htmlEscape(title)}</header>
              <div id="player"></div>
              <div id="fallback">云播组件加载较慢或被网络拦截，已保留磁力链接。<a href="https://webtor.io/">打开 Webtor</a></div>
              <script>
                window.webtor = window.webtor || [];
                window.webtor.push({
                  id: 'player',
                  magnet: ${JSONObject.quote(magnet)},
                  title: ${JSONObject.quote(title)},
                  width: '100%',
                  height: '100%'
                });
                setTimeout(function() {
                  if (!document.querySelector('#player iframe,#player video')) {
                    document.getElementById('fallback').style.display = 'block';
                  }
                }, 9000);
              </script>
              <script src="https://cdn.jsdelivr.net/npm/@webtor/embed-sdk-js/dist/index.min.js" charset="utf-8" async></script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun showHistoryDialog() {
        val history = preferences.getMagnetSearchHistory()
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无磁力搜索历史", Toast.LENGTH_SHORT).show()
            return
        }
        val items = history.map { it.query }.toTypedArray()
        ThemeManager.styleAlertDialog(this@MagnetSearchActivity, MaterialAlertDialogBuilder(this@MagnetSearchActivity))
            .setTitle("磁力搜索历史")
            .setItems(items) { dialog, which ->
                binding.queryInput.setText(history[which].query)
                binding.queryInput.setSelection(binding.queryInput.text?.length ?: 0)
                dialog.dismiss()
            }
            .setPositiveButton("删除当前") { _, _ ->
                val query = binding.queryInput.text?.toString().orEmpty()
                preferences.deleteMagnetSearchHistory(query)
                Toast.makeText(this, "已删除当前关键词历史", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空历史") { _, _ ->
                preferences.clearMagnetSearchHistory()
                Toast.makeText(this, "已清空磁力搜索历史", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteSource(source: MagnetSearchSource) {
        ThemeManager.styleAlertDialog(this@MagnetSearchActivity, MaterialAlertDialogBuilder(this@MagnetSearchActivity))
            .setTitle("删除磁力源")
            .setMessage("删除自定义源“${source.label}”？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                preferences.deleteCustomMagnetSource(source.id)
                selectedSourceIds.remove(source.id)
                preferences.setSelectedMagnetSourceIds(selectedSourceIds)
                reloadSources()
            }
            .show()
    }

    companion object {
        private const val EXTRA_QUERY = "query"
        private const val RESULT_ALL_SOURCE_ID = "__all__"
        private const val CLOUD_PLAY_DISPLAY_URL = "aurora://cloud-play"
        private val SEARCH_SOURCE_CONCURRENCY = MagnetSearchStateTools.DEFAULT_SOURCE_CONCURRENCY

        fun createIntent(context: Context, query: String? = null): Intent {
            return Intent(context, MagnetSearchActivity::class.java).apply {
                putExtra(EXTRA_QUERY, query.orEmpty())
            }
        }
    }
}
