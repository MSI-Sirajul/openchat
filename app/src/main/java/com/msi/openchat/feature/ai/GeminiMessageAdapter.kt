package com.msi.openchat.feature.ai

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.databinding.ItemGeminiMessageBinding

class GeminiMessageAdapter :
    ListAdapter<GeminiChatMessage, GeminiMessageAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGeminiMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemGeminiMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GeminiChatMessage) {
            if (item.isUser) {
                binding.layoutGeminiUserBubble.visibility = View.VISIBLE
                binding.layoutGeminiModelBubble.visibility = View.GONE
                binding.textGeminiUserMsg.text = item.text

                if (!item.imageUri.isNullOrBlank()) {
                    binding.imgGeminiUserAttachment.visibility = View.VISIBLE
                    binding.imgGeminiUserAttachment.load(Uri.parse(item.imageUri)) {
                        crossfade(true)
                    }
                } else {
                    binding.imgGeminiUserAttachment.visibility = View.GONE
                }
            } else {
                binding.layoutGeminiUserBubble.visibility = View.GONE
                binding.layoutGeminiModelBubble.visibility = View.VISIBLE
                binding.progressGeminiStreaming.visibility =
                    if (item.isStreaming) View.VISIBLE else View.GONE
                binding.textGeminiModelMsg.text =
                    item.text.ifBlank { binding.root.context.getString(R.string.gemini_thinking) }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<GeminiChatMessage>() {
        override fun areItemsTheSame(oldItem: GeminiChatMessage, newItem: GeminiChatMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: GeminiChatMessage, newItem: GeminiChatMessage): Boolean =
            oldItem == newItem
    }
}
