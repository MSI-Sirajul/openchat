package com.msi.openchat.core.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemConversationBinding
import com.msi.openchat.core.common.DateTimeUtils
import com.msi.openchat.data.model.Conversation

class ConversationAdapter(
    private val isDark: Boolean = false,
    private val onConversationClick: (Conversation) -> Unit
) : ListAdapter<Conversation, ConversationAdapter.ConversationViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConversationViewHolder(binding, isDark, onConversationClick)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ConversationViewHolder(
        private val binding: ItemConversationBinding,
        private val isDark: Boolean,
        private val onConversationClick: (Conversation) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: Conversation) {
            val other = conversation.otherUser
            val displayName = other?.getResolvedName() ?: "User"
            val initial = displayName.take(1).uppercase()

            val nameColor = if (isDark) Color.parseColor("#E8EEF5") else Color.parseColor("#17212B")
            val subColor = if (isDark) Color.parseColor("#A3B1BF") else Color.parseColor("#64748B")
            val avatarBgColor = if (isDark) Color.parseColor("#203342") else Color.parseColor("#E0EDF8")
            val avatarTextColor = if (isDark) Color.parseColor("#54B9EA") else Color.parseColor("#087EBA")

            binding.textName.setTextColor(nameColor)
            binding.textLastMessage.setTextColor(subColor)
            binding.textTime.setTextColor(subColor)
            binding.textAvatar.setTextColor(avatarTextColor)

            val bg = (binding.avatarContainer.background?.mutate() as? GradientDrawable)
            bg?.setColor(avatarBgColor)

            binding.textAvatar.text = initial
            binding.textName.text = displayName
            binding.textLastMessage.text = conversation.lastMessageText ?: "Started conversation"
            binding.textTime.text = DateTimeUtils.formatConversationTime(
                binding.root.context,
                conversation.lastMessageAt
            )

            // Real-time online/offline indicator
            val isOnline = other?.onlineStatus == "online"
            binding.viewOnlineStatus.visibility = if (isOnline) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onConversationClick(conversation)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return oldItem.lastMessageAt == newItem.lastMessageAt &&
                    oldItem.lastMessageText == newItem.lastMessageText &&
                    oldItem.otherUser?.displayName == newItem.otherUser?.displayName &&
                    oldItem.otherUser?.name == newItem.otherUser?.name &&
                    oldItem.otherUser?.onlineStatus == newItem.otherUser?.onlineStatus &&
                    oldItem.otherUser?.lastSeen == newItem.otherUser?.lastSeen &&
                    oldItem.otherUser?.avatarUrl == newItem.otherUser?.avatarUrl
        }
    }
}
