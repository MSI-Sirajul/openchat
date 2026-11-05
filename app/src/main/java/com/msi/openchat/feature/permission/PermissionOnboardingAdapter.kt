package com.msi.openchat.feature.permission

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemPermissionOnboardingBinding
import com.msi.openchat.core.permission.PermissionItemState

/**
 * Minimal AOSP-style Adapter for Permission Onboarding list.
 * Flat classic rows: title + short description + checkbox with thin divider.
 */
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
        holder.bind(getItem(position))
    }

    inner class PermissionViewHolder(
        private val binding: ItemPermissionOnboardingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PermissionItemState) {
            binding.textPermissionTitle.text = item.title
            binding.textPermissionDesc.text = item.description

            // State is strictly driven by item.isGranted
            binding.cbPermission.setOnCheckedChangeListener(null)
            binding.cbPermission.isChecked = item.isGranted
            binding.cbPermission.isClickable = false
            binding.cbPermission.isFocusable = false

            // Tapping anywhere on the row triggers the permission prompt
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
