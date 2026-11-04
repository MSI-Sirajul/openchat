package com.msi.openchat.feature.permission

import android.view.LayoutInflater
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
        holder.bind(getItem(position))
    }

    inner class PermissionViewHolder(
        private val binding: ItemPermissionOnboardingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PermissionItemState) {
            val context = binding.root.context
            binding.textPermissionTitle.text = item.title
            binding.textPermissionDesc.text = item.description
            binding.imgPermissionIcon.setImageResource(item.iconRes)

            // Disallow direct checkbox toggle; state is strictly driven by item.isGranted
            binding.cbPermission.setOnCheckedChangeListener(null)
            binding.cbPermission.isChecked = item.isGranted
            binding.cbPermission.isClickable = false
            binding.cbPermission.isFocusable = false

            val activeColor = ContextCompat.getColor(context, R.color.color_primary)
            val neutralBorder = ContextCompat.getColor(context, R.color.color_divider)

            if (item.isGranted) {
                binding.cardPermissionItem.strokeColor = activeColor
                binding.cardPermissionItem.strokeWidth = 2
                binding.imgPermissionIcon.setColorFilter(activeColor)
            } else {
                binding.cardPermissionItem.strokeColor = neutralBorder
                binding.cardPermissionItem.strokeWidth = 1
                binding.imgPermissionIcon.setColorFilter(activeColor)
            }

            // Tapping either the card or the checkbox triggers the permission request
            binding.root.setOnClickListener {
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
