package com.msi.openchat.core.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemFriendBinding
import com.msi.openchat.data.model.Friendship

class FriendAdapter(
    private val isDark: Boolean = false,
    private val onAvatarClick: ((com.msi.openchat.data.model.Profile) -> Unit)? = null,
    private val onFriendClick: (Friendship) -> Unit
) : ListAdapter<Friendship, FriendAdapter.FriendViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val binding = ItemFriendBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FriendViewHolder(binding, isDark, onFriendClick, onAvatarClick)
    }

    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FriendViewHolder(
        private val binding: ItemFriendBinding,
        private val isDark: Boolean,
        private val onFriendClick: (Friendship) -> Unit,
        private val onAvatarClick: ((com.msi.openchat.data.model.Profile) -> Unit)?
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(friendship: Friendship) {
            try {
                val friend = friendship.friendProfile
                val displayName = friend?.getResolvedName()?.ifBlank { "Friend" } ?: "Friend"
                val username = friend?.username?.ifBlank { "user" } ?: "user"

                val nameColor = if (isDark) Color.parseColor("#E8EEF5") else Color.parseColor("#17212B")
                val subColor = if (isDark) Color.parseColor("#A3B1BF") else Color.parseColor("#64748B")
                val avatarBgColor = if (isDark) Color.parseColor("#203342") else Color.parseColor("#E0EDF8")
                val avatarTextColor = if (isDark) Color.parseColor("#54B9EA") else Color.parseColor("#087EBA")

                binding.textDisplayName.setTextColor(nameColor)
                binding.textUsername.setTextColor(subColor)
                binding.textAvatar.setTextColor(avatarTextColor)

                val bg = (binding.avatarContainer.background?.mutate() as? GradientDrawable)
                bg?.setColor(avatarBgColor)

                // Avatar image loading with initials fallback
                com.msi.openchat.core.ui.AvatarHelper.loadAvatar(
                    imageView = binding.imgAvatar,
                    letterTextView = binding.textAvatar,
                    avatarUrl = friend?.avatarUrl ?: friend?.avatar,
                    displayName = displayName
                )

                binding.textDisplayName.text = displayName
                binding.textUsername.text = "@$username"

                // Online status indicator
                val isOnline = friend?.onlineStatus == "online"
                binding.viewOnlineStatus.visibility = if (isOnline) View.VISIBLE else View.GONE

                // Clicking the avatar opens that friend's profile
                binding.avatarContainer.setOnClickListener {
                    friend?.let { onAvatarClick?.invoke(it) }
                }

                binding.root.setOnClickListener {
                    onFriendClick(friendship)
                }
            } catch (e: Exception) {
                android.util.Log.e("FriendAdapter", "Error binding friend item: ${e.message}")
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Friendship>() {
        override fun areItemsTheSame(oldItem: Friendship, newItem: Friendship): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Friendship, newItem: Friendship): Boolean {
            return oldItem.friendProfile?.displayName == newItem.friendProfile?.displayName &&
                    oldItem.friendProfile?.name == newItem.friendProfile?.name &&
                    oldItem.friendProfile?.username == newItem.friendProfile?.username &&
                    oldItem.friendProfile?.avatarUrl == newItem.friendProfile?.avatarUrl &&
                    oldItem.friendProfile?.onlineStatus == newItem.friendProfile?.onlineStatus
        }
    }
}
