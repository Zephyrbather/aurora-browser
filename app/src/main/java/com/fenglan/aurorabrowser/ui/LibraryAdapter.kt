package com.fenglan.aurorabrowser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
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
            binding.buttonRow.isVisible = !item.selectionMode
            binding.root.alpha = if (item.selected) 0.88f else 1f

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
        }
    }
}
