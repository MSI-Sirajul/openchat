package com.msi.openchat.feature.ai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.databinding.ItemOpenchatAiMessageBinding

class OpenChatAiMessageAdapter(
    private val onCopyClick: (String) -> Unit
) : ListAdapter<OpenChatAiMessage, OpenChatAiMessageAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOpenchatAiMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onCopyClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemOpenchatAiMessageBinding,
        private val onCopyClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OpenChatAiMessage) {
            val ctx = binding.root.context
            if (item.isUser) {
                binding.layoutOpenchatAiUserBubble.visibility = View.VISIBLE
                binding.layoutOpenchatAiModelBubble.visibility = View.GONE
                binding.textOpenchatAiUserMsg.text = item.text
            } else {
                binding.layoutOpenchatAiUserBubble.visibility = View.GONE
                binding.layoutOpenchatAiModelBubble.visibility = View.VISIBLE

                val modelTag = if (item.modelOption == DeepSeekModelOption.REASONER) {
                    ctx.getString(R.string.openchat_ai_model_r1)
                } else {
                    ctx.getString(R.string.openchat_ai_model_chat)
                }
                binding.textOpenchatAiModelBadge.text =
                    "${ctx.getString(R.string.openchat_ai_title)} • $modelTag"

                binding.progressOpenchatAiStreaming.visibility =
                    if (item.isStreaming) View.VISIBLE else View.GONE

                val canCopy = !item.isStreaming && item.text.isNotBlank()
                binding.btnOpenchatAiCopy.visibility = if (canCopy) View.VISIBLE else View.GONE
                binding.btnOpenchatAiCopy.setOnClickListener {
                    if (item.text.isNotBlank()) {
                        onCopyClick(item.text)
                    }
                }

                binding.textOpenchatAiModelMsg.text =
                    item.text.ifBlank { ctx.getString(R.string.openchat_ai_thinking) }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<OpenChatAiMessage>() {
        override fun areItemsTheSame(oldItem: OpenChatAiMessage, newItem: OpenChatAiMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: OpenChatAiMessage, newItem: OpenChatAiMessage): Boolean =
            oldItem == newItem
    }
}
