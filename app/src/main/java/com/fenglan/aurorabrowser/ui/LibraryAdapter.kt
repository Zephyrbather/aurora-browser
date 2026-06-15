package com.fenglan.aurorabrowser.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.fenglan.aurorabrowser.R
import com.fenglan.aurorabrowser.databinding.ItemLibraryEntryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class LibraryRenderItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val meta: String,
    val primaryText: String,
    val secondaryText: String?,
    val leadingIconRes: Int? = null,
    val primaryEnabled: Boolean = true,
    val progressPercent: Int? = null,
    val selectionMode: Boolean = false,
    val selected: Boolean = false,
    val compact: Boolean = false,
    val primaryIconRes: Int? = null,
    val secondaryIconRes: Int? = null,
    val thumbnailUri: String? = null,
)

class LibraryAdapter(
    private val onItemClick: (Int) -> Unit,
    private val onItemLongClick: (Int) -> Unit,
    private val onPrimaryClick: (Int) -> Unit,
    private val onSecondaryClick: (Int) -> Unit,
) : RecyclerView.Adapter<LibraryAdapter.LibraryViewHolder>() {
    private val items = mutableListOf<LibraryRenderItem>()
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var submitGeneration = 0

    fun submitList(newItems: List<LibraryRenderItem>) {
        val generation = ++submitGeneration
        val oldItems = items.toList()
        adapterScope.launch {
            val diff = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(
                    object : DiffUtil.Callback() {
                        override fun getOldListSize(): Int = oldItems.size

                        override fun getNewListSize(): Int = newItems.size

                        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                            return oldItems[oldItemPosition].id == newItems[newItemPosition].id
                        }

                        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                            return oldItems[oldItemPosition] == newItems[newItemPosition]
                        }
                    },
                )
            }
            if (generation != submitGeneration) {
                return@launch
            }
            items.clear()
            items.addAll(newItems)
            diff.dispatchUpdatesTo(this@LibraryAdapter)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryViewHolder {
        val binding = ItemLibraryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LibraryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LibraryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: LibraryViewHolder) {
        holder.recycle()
    }

    override fun getItemCount(): Int = items.size

    fun release() {
        adapterScope.cancel()
    }

    inner class LibraryViewHolder(
        private val binding: ItemLibraryEntryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var thumbnailJob: Job? = null

        fun bind(item: LibraryRenderItem) {
            thumbnailJob?.cancel()
            binding.leadingIcon.isVisible = item.leadingIconRes != null
            item.leadingIconRes?.let(binding.leadingIcon::setImageResource)
            binding.titleText.text = item.title
            binding.subtitleText.text = item.subtitle
            binding.metaText.text = item.meta
            binding.primaryButton.text = item.primaryText
            binding.primaryButton.isEnabled = item.primaryEnabled
            binding.secondaryButton.isVisible = !item.secondaryText.isNullOrBlank()
            binding.secondaryButton.text = item.secondaryText
            binding.progressBar.isVisible = item.progressPercent != null
            binding.progressBar.progress = item.progressPercent ?: 0
            binding.selectionCheck.isVisible = item.selectionMode
            binding.selectionCheck.isChecked = item.selected
            val useIconActions = !item.selectionMode && (item.primaryIconRes != null || item.secondaryIconRes != null)
            binding.buttonRow.isVisible = !item.selectionMode && !useIconActions
            binding.actionColumn.isVisible = useIconActions
            binding.primaryIconButton.isVisible = item.primaryIconRes != null
            binding.primaryIconButton.isEnabled = item.primaryEnabled
            item.primaryIconRes?.let(binding.primaryIconButton::setImageResource)
            binding.primaryIconButton.contentDescription = item.primaryText
            binding.secondaryIconButton.isVisible = item.secondaryIconRes != null
            item.secondaryIconRes?.let(binding.secondaryIconButton::setImageResource)
            binding.secondaryIconButton.contentDescription = item.secondaryText
            val iconTint = ColorStateList.valueOf(binding.root.context.getColor(R.color.aurora_text))
            binding.primaryIconButton.imageTintList = iconTint
            binding.secondaryIconButton.imageTintList = iconTint
            binding.root.alpha = if (item.selected) 0.88f else 1f
            applyDensity(item.compact)

            val thumbnailUri = item.thumbnailUri
            if (!thumbnailUri.isNullOrBlank()) {
                binding.thumbnailImage.isVisible = true
                binding.thumbnailImage.setImageResource(R.drawable.ic_play)
                val context = binding.root.context
                val cacheDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
                val cacheFile = File(cacheDir, "thumb_${item.id}.jpg")
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    binding.thumbnailImage.setImageURI(Uri.fromFile(cacheFile))
                } else {
                    thumbnailJob = adapterScope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            extractVideoFrame(context, thumbnailUri)
                        }
                        if (bitmap != null) {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    FileOutputStream(cacheFile).use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                                    }
                                }
                            }
                            if (items.getOrNull(bindingAdapterPosition)?.id == item.id) {
                                binding.thumbnailImage.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            } else {
                binding.thumbnailImage.isVisible = false
                binding.thumbnailImage.setImageDrawable(null)
            }

            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(position)
                }
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(position)
                    true
                } else {
                    false
                }
            }
            binding.primaryButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPrimaryClick(position)
                }
            }
            binding.secondaryButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSecondaryClick(position)
                }
            }
            binding.primaryIconButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPrimaryClick(position)
                }
            }
            binding.secondaryIconButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSecondaryClick(position)
                }
            }
        }

        fun recycle() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            binding.thumbnailImage.setImageDrawable(null)
        }

        private fun extractVideoFrame(context: android.content.Context, uriString: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriString)
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val bitmap = retriever.getFrameAtTime(2_000_000)
                retriever.release()
                bitmap
            } catch (_: Exception) {
                null
            }
        }

        private fun applyDensity(compact: Boolean) {
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dp(if (compact) 4 else 10)
            }
            binding.thumbnailImage.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = dp(if (compact) 72 else 96)
                height = dp(if (compact) 48 else 64)
            }
            binding.contentContainer.updatePadding(
                left = dp(if (compact) 8 else 16),
                top = dp(if (compact) 6 else 16),
                right = dp(if (compact) 6 else 16),
                bottom = dp(if (compact) 6 else 16),
            )
            binding.titleText.textSize = if (compact) 13f else 16f
            binding.titleText.maxLines = if (compact) 1 else 2
            binding.subtitleText.textSize = if (compact) 11f else 13f
            binding.subtitleText.maxLines = if (compact) 1 else 2
            binding.metaText.textSize = if (compact) 10f else 12f
            binding.metaText.maxLines = if (compact) 1 else 2
            binding.subtitleText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = dp(if (compact) 2 else 6)
            }
            binding.metaText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = dp(if (compact) 2 else 6)
            }
        }

        private val displayDensity: Float by lazy { binding.root.resources.displayMetrics.density }

        private fun dp(value: Int): Int {
            return (value * displayDensity + 0.5f).toInt()
        }
    }
}
