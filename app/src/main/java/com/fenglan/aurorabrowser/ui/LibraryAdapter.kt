package com.fenglan.aurorabrowser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.fenglan.aurorabrowser.databinding.ItemLibraryEntryBinding

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
)

class LibraryAdapter(
    private val onItemClick: (Int) -> Unit,
    private val onItemLongClick: (Int) -> Unit,
    private val onPrimaryClick: (Int) -> Unit,
    private val onSecondaryClick: (Int) -> Unit,
) : RecyclerView.Adapter<LibraryAdapter.LibraryViewHolder>() {
    private val items = mutableListOf<LibraryRenderItem>()

    fun submitList(newItems: List<LibraryRenderItem>) {
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(
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
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryViewHolder {
        val binding = ItemLibraryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LibraryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LibraryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class LibraryViewHolder(
        private val binding: ItemLibraryEntryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LibraryRenderItem) {
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
            binding.root.alpha = if (item.selected) 0.88f else 1f
            applyDensity(item.compact)

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

        private fun applyDensity(compact: Boolean) {
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dp(if (compact) 6 else 10)
            }
            binding.contentContainer.updatePadding(
                left = dp(if (compact) 10 else 16),
                top = dp(if (compact) 8 else 16),
                right = dp(if (compact) 8 else 16),
                bottom = dp(if (compact) 8 else 16),
            )
            binding.titleText.textSize = if (compact) 14f else 16f
            binding.titleText.maxLines = if (compact) 1 else 2
            binding.subtitleText.textSize = if (compact) 12f else 13f
            binding.subtitleText.maxLines = if (compact) 1 else 2
            binding.metaText.textSize = if (compact) 11f else 12f
            binding.subtitleText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = dp(if (compact) 3 else 6)
            }
            binding.metaText.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = dp(if (compact) 3 else 6)
            }
        }

        private fun dp(value: Int): Int {
            val density = binding.root.resources.displayMetrics.density
            return (value * density + 0.5f).toInt()
        }
    }
}
