package com.msi.openchat.feature.permission

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.databinding.ItemPermissionOnboardingBinding
import com.msi.openchat.core.permission.PermissionItemState

class PermissionOnboardingAdapter(
    private val onItemClick: (PermissionItemState) -> Unit
) : ListAdapter<PermissionItemState, PermissionOnboardingAdapter.PermissionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PermissionViewHolder {
        val binding = ItemPermissionOnboardingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PermissionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PermissionViewHolder, position: Int) {
        holder.bind(getItem(position), isLast = position == itemCount - 1)
    }

    inner class PermissionViewHolder(
        private val binding: ItemPermissionOnboardingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PermissionItemState, isLast: Boolean) {
            val context = binding.root.context
            binding.textPermissionTitle.text = item.title
            binding.textPermissionDesc.text = item.description

            binding.imgPermissionIcon.setImageResource(item.iconRes)
            val tintColor = ContextCompat.getColor(
                context,
                if (item.isGranted) R.color.color_status_online else R.color.color_primary
            )
            binding.imgPermissionIcon.setColorFilter(tintColor)

            binding.imgPermissionGranted.visibility = if (item.isGranted) View.VISIBLE else View.GONE
            binding.imgPermissionGranted.contentDescription =
                context.getString(R.string.permissions_already_enabled_toast, item.title)

            binding.dividerPermission.visibility = if (isLast) View.GONE else View.VISIBLE

            binding.layoutPermissionRow.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<PermissionItemState>() {
        override fun areItemsTheSame(
            oldItem: PermissionItemState,
            newItem: PermissionItemState
        ): Boolean = oldItem.permission == newItem.permission

        override fun areContentsTheSame(
            oldItem: PermissionItemState,
            newItem: PermissionItemState
        ): Boolean = oldItem == newItem
    }
}
