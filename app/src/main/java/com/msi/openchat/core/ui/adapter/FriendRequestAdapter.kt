package com.msi.openchat.core.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemFriendRequestBinding
import com.msi.openchat.core.common.DateTimeUtils
import com.msi.openchat.data.model.FriendRequest

class FriendRequestAdapter(
    private val isReceivedTab: Boolean,
    private val onAccept: (FriendRequest) -> Unit,
    private val onDecline: (FriendRequest) -> Unit,
    private val onCancel: (FriendRequest) -> Unit
) : ListAdapter<FriendRequest, FriendRequestAdapter.FriendRequestViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendRequestViewHolder {
        val binding = ItemFriendRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FriendRequestViewHolder(binding, isReceivedTab, onAccept, onDecline, onCancel)
    }

    override fun onBindViewHolder(holder: FriendRequestViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FriendRequestViewHolder(
        private val binding: ItemFriendRequestBinding,
        private val isReceivedTab: Boolean,
        private val onAccept: (FriendRequest) -> Unit,
        private val onDecline: (FriendRequest) -> Unit,
        private val onCancel: (FriendRequest) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(request: FriendRequest) {
            val user = if (isReceivedTab) request.sender else request.receiver
            val displayName = user?.displayName?.ifBlank { user.username ?: "User" } ?: "User"
            binding.textRequestName.text = displayName
            com.msi.openchat.core.ui.AvatarHelper.loadAvatar(
                imageView = binding.imgRequestAvatar,
                letterTextView = binding.textRequestAvatar,
                avatarUrl = user?.avatarUrl ?: user?.avatar,
                displayName = displayName
            )
            binding.textRequestTime.text = DateTimeUtils.formatRelativeTime(request.createdAt)

            if (isReceivedTab) {
                binding.layoutReceivedActions.visibility = View.VISIBLE
                binding.btnCancelRequest.visibility = View.GONE

                binding.btnAcceptRequest.setOnClickListener { onAccept(request) }
                binding.btnDeclineRequest.setOnClickListener { onDecline(request) }
            } else {
                binding.layoutReceivedActions.visibility = View.GONE
                binding.btnCancelRequest.visibility = View.VISIBLE
                binding.btnCancelRequest.setOnClickListener { onCancel(request) }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<FriendRequest>() {
        override fun areItemsTheSame(oldItem: FriendRequest, newItem: FriendRequest): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FriendRequest, newItem: FriendRequest): Boolean {
            return oldItem == newItem
        }
    }
}
