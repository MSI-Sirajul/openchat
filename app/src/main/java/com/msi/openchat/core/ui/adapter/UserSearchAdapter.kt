package com.msi.openchat.core.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.databinding.ItemUserSearchBinding
import com.msi.openchat.data.model.Profile

enum class RelationshipStatus {
    NONE,
    REQUESTED,
    FRIENDS
}

data class UserSearchItem(
    val profile: Profile,
    val status: RelationshipStatus = RelationshipStatus.NONE,
    val isAlreadyFriend: Boolean = (status == RelationshipStatus.FRIENDS),
    val isPendingRequest: Boolean = (status == RelationshipStatus.REQUESTED)
)

class UserSearchAdapter(
    private val onAddFriend: (Profile) -> Unit
) : ListAdapter<UserSearchItem, UserSearchAdapter.UserSearchViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserSearchViewHolder {
        val binding = ItemUserSearchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UserSearchViewHolder(binding, onAddFriend)
    }

    override fun onBindViewHolder(holder: UserSearchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class UserSearchViewHolder(
        private val binding: ItemUserSearchBinding,
        private val onAddFriend: (Profile) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UserSearchItem) {
            val profile = item.profile
            binding.textUserName.text = profile.displayName.ifBlank { profile.username ?: "User" }
            binding.textUserHandle.text = if (!profile.username.isNullOrBlank()) "@${profile.username}" else profile.phoneNumber ?: ""
            binding.textUserAvatar.text = profile.displayName.take(1).ifBlank { "U" }.uppercase()

            when (item.status) {
                RelationshipStatus.FRIENDS -> {
                    binding.btnActionFriend.isEnabled = false
                    binding.btnActionFriend.text = "Friends"
                    binding.btnActionFriend.setBackgroundColor(binding.root.context.getColor(R.color.color_surface_variant))
                    binding.btnActionFriend.setTextColor(binding.root.context.getColor(R.color.color_secondary_text))
                }
                RelationshipStatus.REQUESTED -> {
                    binding.btnActionFriend.isEnabled = false
                    binding.btnActionFriend.text = "Requested"
                    binding.btnActionFriend.setBackgroundColor(binding.root.context.getColor(R.color.color_surface_variant))
                    binding.btnActionFriend.setTextColor(binding.root.context.getColor(R.color.color_secondary_text))
                }
                RelationshipStatus.NONE -> {
                    binding.btnActionFriend.isEnabled = true
                    binding.btnActionFriend.text = "Add"
                    binding.btnActionFriend.setBackgroundColor(binding.root.context.getColor(R.color.color_primary))
                    binding.btnActionFriend.setTextColor(0xFFFFFFFF.toInt())
                    binding.btnActionFriend.setOnClickListener {
                        onAddFriend(profile)
                    }
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<UserSearchItem>() {
        override fun areItemsTheSame(oldItem: UserSearchItem, newItem: UserSearchItem): Boolean {
            return oldItem.profile.id == newItem.profile.id
        }

        override fun areContentsTheSame(oldItem: UserSearchItem, newItem: UserSearchItem): Boolean {
            return oldItem == newItem
        }
    }
}
