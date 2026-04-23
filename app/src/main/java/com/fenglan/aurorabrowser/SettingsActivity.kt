package com.fenglan.aurorabrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fenglan.aurorabrowser.browser.BrowserPreferences
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.databinding.ActivitySettingsBinding
import com.fenglan.aurorabrowser.model.DEFAULT_HOME_URL
import com.fenglan.aurorabrowser.model.SearchEngine
import com.fenglan.aurorabrowser.model.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val preferences by lazy { BrowserPreferences(this) }
    private val store by lazy { BrowserStore(this) }
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

        binding.downloadPathCard.setOnClickListener { showDownloadPathDialog() }
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
        renderSettingsSummary()
    }

    private fun renderSettingsSummary() {
        binding.downloadPathSubtitle.text = "Download/${preferences.getDownloadSubdirectory()}"
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
        MaterialAlertDialogBuilder(this)
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
            .show()
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
        MaterialAlertDialogBuilder(this)
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
            .show()
    }

    private fun showSearchEngineDialog() {
        val engines = SearchEngine.entries
        MaterialAlertDialogBuilder(this)
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
            .show()
    }

    private fun showTranslationEngineDialog() {
        val engines = TranslationEngine.entries
        MaterialAlertDialogBuilder(this)
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
            .show()
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 0)
            addView(credentialInput)
            addView(
                secretInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 24
                },
            )
        }
        MaterialAlertDialogBuilder(this)
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
            .show()
    }

    private fun showPermissionInfoDialog() {
        MaterialAlertDialogBuilder(this)
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
            .show()
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
}
