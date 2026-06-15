package com.fenglan.aurorabrowser.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fenglan.aurorabrowser.browser.BrowserThemePalette
import com.fenglan.aurorabrowser.browser.BrowserThemeTools
import com.fenglan.aurorabrowser.browser.MagnetSearchResult
import com.fenglan.aurorabrowser.databinding.ItemMagnetResultBinding
import com.fenglan.aurorabrowser.model.BrowserThemeMode

class MagnetSearchAdapter(
    private val onCopyClick: (MagnetSearchResult) -> Unit,
    private val onCopyMagnetClick: (MagnetSearchResult) -> Unit,
    private val onCloudPlayClick: (MagnetSearchResult) -> Unit,
    private val onOpenClick: (MagnetSearchResult) -> Unit,
) : ListAdapter<MagnetSearchResult, MagnetSearchAdapter.ViewHolder>(DiffCallback) {
    private var currentPalette: BrowserThemePalette? = null

    fun setPalette(value: BrowserThemePalette) {
        currentPalette = value
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemMagnetResultBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemMagnetResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MagnetSearchResult) {
            binding.titleText.text = item.title
            binding.sourceText.text = buildString {
                append(item.sourceLabel)
                when {
                    item.magnetUrl != null -> append(" · Magnet")
                    item.torrentUrl != null -> append(" · Torrent")
                    else -> append(" · 详情页")
                }
                item.sizeText?.takeIf { it.isNotBlank() }?.let { size ->
                    append(" · ")
                    append(size)
                }
            }
            binding.linkText.text = item.magnetUrl
                ?: item.torrentUrl
                ?: "未解析到磁力链接，可打开详情页"
            styleButtons()
            val hasDirectLink = item.magnetUrl != null || item.torrentUrl != null
            binding.copyButton.isEnabled = hasDirectLink
            binding.copyButton.alpha = if (hasDirectLink) 1f else 0.45f
            binding.copyButton.setOnClickListener { onCopyClick(item) }
            binding.copyMagnetButton.isEnabled = item.magnetUrl != null
            binding.copyMagnetButton.alpha = if (item.magnetUrl != null) 1f else 0.45f
            binding.copyMagnetButton.setOnClickListener { onCopyMagnetClick(item) }
            binding.cloudPlayButton.isEnabled = item.magnetUrl != null
            binding.cloudPlayButton.alpha = if (item.magnetUrl != null) 1f else 0.45f
            binding.cloudPlayButton.setOnClickListener { onCloudPlayClick(item) }
            binding.openButton.setOnClickListener { onOpenClick(item) }
            binding.root.setOnClickListener { onOpenClick(item) }
        }

        private fun styleButtons() {
            val palette = currentPalette ?: BrowserThemeTools.paletteFor(BrowserThemeMode.LIGHT)
            val outlineButtons = listOf(binding.copyButton, binding.copyMagnetButton, binding.cloudPlayButton)
            outlineButtons.forEach { button ->
                button.iconSize = dp(18)
                button.iconTint = ColorStateList.valueOf(palette.text)
                button.backgroundTintList = ColorStateList.valueOf(palette.surfaceAlt)
                button.strokeColor = ColorStateList.valueOf(palette.line)
                button.rippleColor = ColorStateList.valueOf(withAlpha(palette.accent, 36))
            }
            binding.openButton.iconSize = dp(18)
            binding.openButton.iconTint = ColorStateList.valueOf(Color.WHITE)
            binding.openButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
            binding.openButton.rippleColor = ColorStateList.valueOf(withAlpha(Color.WHITE, 42))
        }

        private val displayDensity: Float by lazy { binding.root.resources.displayMetrics.density }

        private fun dp(value: Int): Int {
            return (value * displayDensity + 0.5f).toInt()
        }

        private fun withAlpha(color: Int, alpha: Int): Int {
            return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<MagnetSearchResult>() {
        override fun areItemsTheSame(oldItem: MagnetSearchResult, newItem: MagnetSearchResult): Boolean {
            return oldItem.copyUrl == newItem.copyUrl && oldItem.sourceId == newItem.sourceId
        }

        override fun areContentsTheSame(oldItem: MagnetSearchResult, newItem: MagnetSearchResult): Boolean {
            return oldItem == newItem
        }
    }
}
