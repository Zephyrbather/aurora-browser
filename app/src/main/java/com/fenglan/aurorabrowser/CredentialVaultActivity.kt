package com.fenglan.aurorabrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fenglan.aurorabrowser.data.BrowserStore
import com.fenglan.aurorabrowser.databinding.ActivityLibraryBinding
import com.fenglan.aurorabrowser.model.SavedCredentialItem
import com.fenglan.aurorabrowser.ui.LibraryAdapter
import com.fenglan.aurorabrowser.ui.LibraryRenderItem

class CredentialVaultActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private val store by lazy { BrowserStore(this) }
    private lateinit var adapter: LibraryAdapter
    private var credentials: List<SavedCredentialItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = LibraryAdapter(
            onItemClick = ::showCredentialAt,
            onItemLongClick = ::showCredentialAt,
            onPrimaryClick = ::showCredentialAt,
            onSecondaryClick = ::deleteCredentialAt,
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.filterScroll.isVisible = false
        binding.toolbar.title = "已保存账号密码"
        binding.toolbar.subtitle = "用于自动填充站点登录表单"
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadCredentials()
    }

    private fun loadCredentials() {
        credentials = store.getSavedCredentials()
        adapter.submitList(
            credentials.map {
                LibraryRenderItem(
                    id = it.id,
                    title = it.host,
                    subtitle = it.username,
                    meta = "最近更新 ${com.fenglan.aurorabrowser.browser.ResourceTools.formatTime(it.updatedAt)}",
                    primaryText = "查看",
                    secondaryText = "删除",
                    leadingIconRes = R.drawable.ic_key,
                )
            },
        )
        binding.emptyText.isVisible = credentials.isEmpty()
        binding.emptyText.text = "还没有保存任何站点账号密码"
    }

    private fun showCredentialAt(position: Int) {
        val item = credentials.getOrNull(position) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(item.host)
            .setMessage(
                """
                账号：${item.username}
                密码：${item.password}
                
                页面：${item.pageUrl ?: "未知"}
                """.trimIndent(),
            )
            .setNegativeButton("关闭", null)
            .setNeutralButton("复制账号") { _, _ ->
                copyToClipboard("username", item.username)
                toast("账号已复制")
            }
            .setPositiveButton("复制密码") { _, _ ->
                copyToClipboard("password", item.password)
                toast("密码已复制")
            }
            .show()
    }

    private fun deleteCredentialAt(position: Int) {
        val item = credentials.getOrNull(position) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(item.host)
            .setMessage("删除这条已保存的账号密码？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                store.deleteCredential(item.id)
                loadCredentials()
            }
            .show()
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
