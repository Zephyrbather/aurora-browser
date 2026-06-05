package com.fenglan.aurorabrowser

import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.browser.BrowserThemePalette
import com.fenglan.aurorabrowser.browser.BrowserThemeTools
import com.fenglan.aurorabrowser.browser.ThemeManager
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.databinding.ActivitySettingsBinding
import com.fenglan.aurorabrowser.model.DEFAULT_HOME_URL
import com.fenglan.aurorabrowser.model.P2pDownloadEngine
import com.fenglan.aurorabrowser.model.SearchEngine
import com.fenglan.aurorabrowser.model.TranslationEngine
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var currentPalette: BrowserThemePalette
    private val preferences by lazy { BrowserPreferences(this) }
    private val store by lazy { BrowserStore.getInstance(this) }
    private val downloadFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val treeUri = uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val subdirectory = resolveDownloadSubdirectoryFromTree(treeUri)
        if (subdirectory == null) {
            Toast.makeText(this, "请选择 Download 目录下的具体子文件夹", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        preferences.setDownloadSubdirectory(subdirectory)
        renderSettingsSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = "设置"
        binding.toolbar.subtitle = "浏览器默认选项与账号管理"
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        applySettingsTheme()

        binding.downloadPathCard.setOnClickListener { showDownloadPathDialog() }
        binding.hlsConcurrencyCard.setOnClickListener { showHlsConcurrencyDialog() }
        binding.p2pEngineCard.setOnClickListener { showP2pEngineDialog() }
        binding.cacheCleanupCard.setOnClickListener { showCacheCleanupDialog() }
        binding.startPageCard.setOnClickListener { showStartPageDialog() }
        binding.searchEngineCard.setOnClickListener { showSearchEngineDialog() }
        binding.translationEngineCard.setOnClickListener { showTranslationEngineDialog() }
        binding.credentialsCard.setOnClickListener {
            startActivity(Intent(this, CredentialVaultActivity::class.java))
        }
        binding.permissionCard.setOnClickListener { showPermissionInfoDialog() }
    }

    override fun onResume() {
        super.onResume()
        applySettingsTheme()
        renderSettingsSummary()
    }

    private fun renderSettingsSummary() {
        binding.downloadPathSubtitle.text = "Download/${preferences.getDownloadSubdirectory()}"
        binding.hlsConcurrencySubtitle.text = "同时下载 ${preferences.getHlsConcurrency()} 个 HLS 分片"
        val p2pEngine = preferences.getP2pDownloadEngine()
        binding.p2pEngineSubtitle.text = "${p2pEngine.label} · ${p2pEngine.summary}"
        renderCacheSummary()
        binding.startPageSubtitle.text = when (val startPage = preferences.getStartPageUrl()) {
            DEFAULT_HOME_URL -> "浏览器首页"
            else -> startPage
        }
        binding.searchEngineSubtitle.text = preferences.getSearchEngine().label
        binding.translationEngineSubtitle.text = preferences.getTranslationEngine().label
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { store.getSavedCredentials().size }
            binding.credentialsSubtitle.text = if (count == 0) "还没有已保存的登录信息" else "已保存 $count 组站点账号密码"
        }
    }

    private fun showDownloadPathDialog() {
        themedDialog()
            .setTitle("下载路径")
            .setMessage("使用系统文件管理器选择 Download 目录下的子文件夹作为下载路径")
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认") { _, _ ->
                preferences.setDownloadSubdirectory("Aurora")
                renderSettingsSummary()
            }
            .setPositiveButton("选择文件夹") { _, _ ->
                downloadFolderLauncher.launch(null)
            }
            .showThemed()
    }

    private fun showCacheCleanupDialog() {
        themedDialog()
            .setTitle("清理浏览器缓存")
            .setMessage(
                """
                将清理网页缓存、HLS 预取播放列表、列表缩略图和应用临时缓存。

                不会清理浏览历史、搜索历史、书签收藏、已保存账号密码和下载记录。
                """.trimIndent(),
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("清理缓存") { _, _ ->
                clearBrowserCache()
            }
            .showThemed()
    }

    private fun clearBrowserCache() {
        lifecycleScope.launch {
            binding.cacheCleanupSubtitle.text = "正在清理..."
            val webViewCleared = runCatching {
                WebView(this@SettingsActivity).apply {
                    clearCache(true)
                    destroy()
                }
            }.isSuccess
            val deletedBytes = withContext(Dispatchers.IO) {
                listOfNotNull(cacheDir, externalCacheDir).sumOf(::deleteDirectoryChildren)
            }
            renderCacheSummary()
            val message = if (webViewCleared) {
                "已清理 ${formatBytes(deletedBytes)} 缓存"
            } else {
                "临时缓存已清理，WebView 缓存可能需要重启后释放"
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderCacheSummary() {
        binding.cacheCleanupSubtitle.text = "正在计算缓存大小"
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) {
                listOfNotNull(cacheDir, externalCacheDir).sumOf(::directorySize)
            }
            binding.cacheCleanupSubtitle.text = "临时缓存约 ${formatBytes(size)}，不包含历史和书签"
        }
    }

    private fun showHlsConcurrencyDialog() {
        val values = listOf(1, 2, 4, 6, 8)
        val labels = values.map { value ->
            when (value) {
                1 -> "1 个分片 · 最稳"
                2 -> "2 个分片 · 低并发"
                4 -> "4 个分片 · 默认"
                6 -> "6 个分片 · 较快"
                else -> "8 个分片 · 最高"
            }
        }.toTypedArray()
        themedDialog()
            .setTitle("HLS 分片并发")
            .setSingleChoiceItems(labels, values.indexOf(preferences.getHlsConcurrency())) { dialog, which ->
                preferences.setHlsConcurrency(values[which])
                renderSettingsSummary()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .showThemed()
    }

    private fun showP2pEngineDialog() {
        val engines = P2pDownloadEngine.entries
        themedDialog()
            .setTitle("磁力下载引擎")
            .setMessage("内置引擎可在 Aurora 下载列表显示进度；外部应用适合已经安装专业 BT 客户端的设备。")
            .setSingleChoiceItems(
                engines.map { "${it.label}\n${it.summary}" }.toTypedArray(),
                engines.indexOf(preferences.getP2pDownloadEngine()),
            ) { dialog, which ->
                preferences.setP2pDownloadEngine(engines[which])
                renderSettingsSummary()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .showThemed()
    }

    private fun showStartPageDialog() {
        val input = EditText(this).apply {
            setSingleLine()
            hint = "留空则使用浏览器首页"
            setText(
                preferences.getStartPageUrl().takeIf { it != DEFAULT_HOME_URL }.orEmpty(),
            )
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        styleDialogInput(input)
        themedDialog()
            .setTitle("自定义初始页")
            .setMessage("留空使用浏览器首页，也可以填写完整网址")
            .setView(input)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复首页") { _, _ ->
                preferences.setStartPageUrl(DEFAULT_HOME_URL)
                renderSettingsSummary()
            }
            .setPositiveButton("保存") { _, _ ->
                val resolved = normalizeStartPageInput(input.text?.toString().orEmpty())
                if (resolved == null) {
                    Toast.makeText(this, "请输入有效网址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                preferences.setStartPageUrl(resolved)
                renderSettingsSummary()
            }
            .showThemed()
    }

    private fun showSearchEngineDialog() {
        val engines = SearchEngine.entries
        themedDialog()
            .setTitle("默认搜索引擎")
            .setSingleChoiceItems(
                engines.map { it.label }.toTypedArray(),
                engines.indexOf(preferences.getSearchEngine()),
            ) { dialog, which ->
                preferences.setSearchEngine(engines[which])
                renderSettingsSummary()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .showThemed()
    }

    private fun showTranslationEngineDialog() {
        val engines = TranslationEngine.entries
        themedDialog()
            .setTitle("默认翻译引擎")
            .setSingleChoiceItems(
                engines.map { it.label }.toTypedArray(),
                engines.indexOf(preferences.getTranslationEngine()),
            ) { dialog, which ->
                val engine = engines[which]
                dialog.dismiss()
                if (engine.requiresCredential) {
                    showTranslationCredentialDialog(engine)
                } else {
                    preferences.setTranslationEngine(engine)
                    renderSettingsSummary()
                }
            }
            .setNegativeButton("取消", null)
            .showThemed()
    }

    private fun showTranslationCredentialDialog(engine: TranslationEngine) {
        val credentialInput = EditText(this).apply {
            setSingleLine()
            hint = if (engine == TranslationEngine.BAIDU) "Baidu App ID" else "Youdao App Key"
            setText(
                if (engine == TranslationEngine.BAIDU) {
                    preferences.getBaiduAppId()
                } else {
                    preferences.getYoudaoAppKey()
                },
            )
        }
        val secretInput = EditText(this).apply {
            setSingleLine()
            hint = if (engine == TranslationEngine.BAIDU) "Baidu Secret" else "Youdao App Secret"
            setText(
                if (engine == TranslationEngine.BAIDU) {
                    preferences.getBaiduSecret()
                } else {
                    preferences.getYoudaoAppSecret()
                },
            )
        }
        styleDialogInput(credentialInput)
        styleDialogInput(secretInput)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(credentialInput)
            addView(
                secretInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
        }
        themedDialog()
            .setTitle(engine.label)
            .setMessage("输入接口凭据后设为默认翻译引擎")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                if (credentialInput.text?.isBlank() != false || secretInput.text?.isBlank() != false) {
                    Toast.makeText(this, "请完整填写凭据", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                when (engine) {
                    TranslationEngine.BAIDU -> {
                        preferences.setBaiduAppId(credentialInput.text?.toString().orEmpty())
                        preferences.setBaiduSecret(secretInput.text?.toString().orEmpty())
                    }

                    TranslationEngine.YOUDAO -> {
                        preferences.setYoudaoAppKey(credentialInput.text?.toString().orEmpty())
                        preferences.setYoudaoAppSecret(secretInput.text?.toString().orEmpty())
                    }

                    else -> Unit
                }
                preferences.setTranslationEngine(engine)
                renderSettingsSummary()
            }
            .showThemed()
    }

    private fun showPermissionInfoDialog() {
        themedDialog()
            .setTitle("功能权限说明")
            .setMessage(
                """
                网络权限：用于网页浏览、搜索建议、网页翻译、资源嗅探和下载。

                通知权限：用于显示下载进度和完成提醒。

                系统下载目录：用于保存网页文件、视频和普通下载内容。

                相机/相册跳转：用于扫码或识别二维码，实际由系统相机或系统选图界面完成。

                已保存账号密码：仅保存在本机浏览器数据库中，用于自动填充登录表单。
                """.trimIndent(),
            )
            .setPositiveButton("知道了", null)
            .showThemed()
    }

    private fun applySettingsTheme() {
        val themeMode = preferences.getSettings().themeMode
        val palette = BrowserThemeTools.paletteFor(themeMode)
        currentPalette = palette
        ThemeManager.update(palette)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = palette.surface
        val isDark = BrowserThemeTools.isEffectivelyDark(themeMode)
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !isDark
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightNavigationBars = !isDark

        binding.root.background = gradientBackground(palette) ?: ColorDrawable(palette.surface)
        binding.toolbar.background = if (palette.gradientStart != null && palette.gradientEnd != null) {
            ColorDrawable(Color.TRANSPARENT)
        } else {
            ColorDrawable(palette.surface)
        }
        binding.toolbar.setTitleTextColor(palette.text)
        binding.toolbar.setSubtitleTextColor(palette.textMuted)
        binding.toolbar.navigationIcon?.setTint(palette.text)

        settingsCards().forEach { card ->
            card.setCardBackgroundColor(palette.surfaceAlt)
            card.strokeColor = palette.line
            card.strokeWidth = dp(1)
            card.cardElevation = 0f
            card.rippleColor = ColorStateList.valueOf(tintWithAlpha(palette.accent, 36))
            applyViewPalette(card)
        }
    }

    private fun settingsCards(): List<MaterialCardView> {
        return listOf(
            binding.downloadPathCard,
            binding.hlsConcurrencyCard,
            binding.p2pEngineCard,
            binding.cacheCleanupCard,
            binding.startPageCard,
            binding.searchEngineCard,
            binding.translationEngineCard,
            binding.credentialsCard,
            binding.permissionCard,
        )
    }

    private fun applyViewPalette(view: View) {
        when (view) {
            is ImageView -> view.imageTintList = ColorStateList.valueOf(currentPalette.text)
            is TextView -> {
                val sp = textSizeSp(view)
                view.setTextColor(if (sp <= 13.5f) currentPalette.textMuted else currentPalette.text)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyViewPalette(view.getChildAt(index))
            }
        }
    }

    private fun themedDialog(): MaterialAlertDialogBuilder {
        ensurePalette()
        return MaterialAlertDialogBuilder(this)
            .setBackground(
                GradientDrawable().apply {
                    setColor(currentPalette.surface)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), currentPalette.line)
                },
            )
    }

    private fun MaterialAlertDialogBuilder.showThemed(): AlertDialog {
        val dialog = show()
        applyDialogPalette(dialog)
        return dialog
    }

    private fun applyDialogPalette(dialog: AlertDialog) {
        dialog.listView?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            divider = ColorDrawable(Color.TRANSPARENT)
            selector = RippleDrawable(
                ColorStateList.valueOf(tintWithAlpha(currentPalette.accent, 36)),
                ColorDrawable(Color.TRANSPARENT),
                null,
            )
        }
        dialog.window?.decorView?.let(::applyDialogViewPalette)
        listOf(
            DialogInterface.BUTTON_POSITIVE,
            DialogInterface.BUTTON_NEGATIVE,
            DialogInterface.BUTTON_NEUTRAL,
        ).forEach { buttonId ->
            dialog.getButton(buttonId)?.apply {
                setTextColor(currentPalette.accent)
                background = RippleDrawable(
                    ColorStateList.valueOf(tintWithAlpha(currentPalette.accent, 36)),
                    ColorDrawable(Color.TRANSPARENT),
                    null,
                )
            }
        }
    }

    private fun applyDialogViewPalette(view: View) {
        when (view) {
            is CheckedTextView -> {
                view.setTextColor(currentPalette.text)
                view.checkMarkTintList = ColorStateList.valueOf(currentPalette.accent)
                view.minHeight = view.minHeight.coerceAtLeast(dp(48))
            }

            is CompoundButton -> {
                view.setTextColor(currentPalette.text)
                view.buttonTintList = ColorStateList.valueOf(currentPalette.accent)
                view.minHeight = view.minHeight.coerceAtLeast(dp(48))
            }

            is ImageView -> view.imageTintList = ColorStateList.valueOf(currentPalette.text)

            is TextView -> {
                val sp = textSizeSp(view)
                view.setTextColor(if (sp <= 14f) currentPalette.textMuted else currentPalette.text)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyDialogViewPalette(view.getChildAt(index))
            }
        }
    }

    private fun styleDialogInput(input: EditText) {
        ensurePalette()
        input.setTextColor(currentPalette.text)
        input.setHintTextColor(currentPalette.textMuted)
        input.backgroundTintList = ColorStateList.valueOf(currentPalette.accent)
        input.minHeight = dp(48)
    }

    private fun gradientBackground(palette: BrowserThemePalette): GradientDrawable? {
        val start = palette.gradientStart ?: return null
        val end = palette.gradientEnd ?: return null
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (palette.gradientMid != null) {
                intArrayOf(start, palette.gradientMid, end)
            } else {
                intArrayOf(start, end)
            },
        )
    }

    private fun ensurePalette() {
        if (!::currentPalette.isInitialized) {
            currentPalette = BrowserThemeTools.paletteFor(preferences.getSettings().themeMode)
        }
    }

    private fun tintWithAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun textSizeSp(view: TextView): Float {
        val effectiveScaledDensity = resources.displayMetrics.density * resources.configuration.fontScale
        return view.textSize / effectiveScaledDensity.coerceAtLeast(0.1f)
    }

    private fun normalizeStartPageInput(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == DEFAULT_HOME_URL) {
            return DEFAULT_HOME_URL
        }
        val resolved = when {
            hasExplicitScheme(trimmed) -> trimmed
            looksLikeDirectUrl(trimmed) -> "https://$trimmed"
            else -> return null
        }
        val uri = runCatching { Uri.parse(resolved) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        return resolved.takeIf { scheme == "http" || scheme == "https" }
    }

    private fun hasExplicitScheme(input: String): Boolean {
        return Regex("^[a-zA-Z][a-zA-Z\\d+.-]*:(//)?").containsMatchIn(input)
    }

    private fun looksLikeDirectUrl(input: String): Boolean {
        if (input.isBlank() || input.contains(' ')) {
            return false
        }
        val host = input.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (host.equals("localhost", ignoreCase = true)) {
            return true
        }
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
            return true
        }
        return host.contains('.')
    }

    private fun resolveDownloadSubdirectoryFromTree(treeUri: Uri): String? {
        val documentFile = DocumentFile.fromTreeUri(this, treeUri) ?: return null
        val path = buildTreePath(documentFile).trim('/')
        if (path.isBlank()) {
            return null
        }
        val normalized = path.replace('\\', '/')
        return when {
            normalized.equals("Download", ignoreCase = true) -> null
            normalized.startsWith("Download/", ignoreCase = true) -> normalized.substringAfter("Download/").trim('/').ifBlank { null }
            else -> null
        }
    }

    private fun buildTreePath(documentFile: DocumentFile): String {
        val documentId = DocumentsContract.getTreeDocumentId(documentFile.uri)
        return documentId.substringAfter(':', "")
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) {
            return 0L
        }
        if (file.isFile) {
            return file.length()
        }
        return file.listFiles()?.sumOf(::directorySize) ?: 0L
    }

    private fun deleteDirectoryChildren(directory: File): Long {
        if (!directory.exists() || !directory.isDirectory) {
            return 0L
        }
        return directory.listFiles()?.sumOf { child ->
            val size = directorySize(child)
            runCatching { child.deleteRecursively() }
            size
        } ?: 0L
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) {
            return "${bytes.coerceAtLeast(0L)} B"
        }
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
