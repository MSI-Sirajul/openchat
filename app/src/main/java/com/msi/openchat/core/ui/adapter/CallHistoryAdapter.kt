package com.msi.openchat.core.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.databinding.ItemCallHistoryBinding
import com.msi.openchat.core.common.DateTimeUtils
import com.msi.openchat.data.model.CallSession

class CallHistoryAdapter(
    private val isDark: Boolean = false,
    private val onCallback: (contactId: String, contactName: String, callType: String) -> Unit
) : ListAdapter<CallSession, CallHistoryAdapter.CallHistoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallHistoryViewHolder {
        val binding = ItemCallHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CallHistoryViewHolder(binding, isDark, onCallback)
    }

    override fun onBindViewHolder(holder: CallHistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CallHistoryViewHolder(
        private val binding: ItemCallHistoryBinding,
        private val isDark: Boolean,
        private val onCallback: (contactId: String, contactName: String, callType: String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(call: CallSession) {
            try {
                val other = call.callerProfile ?: call.receiverProfile
                val contactName = other?.getResolvedName()?.ifBlank { "Contact" } ?: "Contact"
                val contactId = other?.id?.takeIf { it.isNotBlank() } ?: if (call.callerProfile != null) call.callerId else call.receiverId
                val status = call.status ?: "ended"
                val callType = if (call.callType == "video" || call.id.startsWith("vid_")) "video" else "audio"
                val isMissed = status in listOf("missed", "declined", "failed")

                val nameColor = if (isDark) Color.parseColor("#E8EEF5") else Color.parseColor("#17212B")
                val subColor = if (isDark) Color.parseColor("#A3B1BF") else Color.parseColor("#64748B")
                val avatarBgColor = if (isDark) Color.parseColor("#203342") else Color.parseColor("#E0EDF8")

                binding.textContactName.setTextColor(nameColor)
                binding.textCallDetails.setTextColor(subColor)

                val bg = (binding.avatarContainer.background?.mutate() as? GradientDrawable)
                bg?.setColor(avatarBgColor)

                binding.textAvatar.text = contactName.take(1).uppercase()
                binding.textContactName.text = contactName

                val context = binding.root.context
                val formattedTime = DateTimeUtils.formatConversationTime(context, call.startedAt)
                val durationText = if (call.durationSeconds > 0) " • ${call.durationSeconds}s" else ""
                val timeText = if (formattedTime.isNotBlank()) " • $formattedTime" else ""
                val statusDisplay = status.replaceFirstChar { it.uppercase() }
                val typeLabel = if (callType == "video") "Video" else "Audio"

                binding.textCallDetails.text = "$typeLabel • $statusDisplay$durationText$timeText"

                if (isMissed) {
                    binding.imageStatus.setImageResource(R.drawable.ic_call_missed_24)
                    binding.textAvatar.setTextColor(Color.parseColor(if (isDark) "#FFB4AB" else "#BA1A1A"))
                } else {
                    binding.imageStatus.setImageResource(R.drawable.ic_call_received_24)
                    binding.textAvatar.setTextColor(Color.parseColor(if (isDark) "#54B9EA" else "#087EBA"))
                }

                binding.btnCallback.setImageResource(
                    if (callType == "video") R.drawable.ic_video_24 else R.drawable.ic_call_24
                )
                binding.btnCallback.contentDescription =
                    if (callType == "video") "Start Video Call" else "Start Voice Call"

                binding.btnCallback.setOnClickListener {
                    onCallback(contactId, contactName, callType)
                }
            } catch (e: Exception) {
                android.util.Log.e("CallHistoryAdapter", "Error binding call item: ${e.message}")
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<CallSession>() {
        override fun areItemsTheSame(oldItem: CallSession, newItem: CallSession): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CallSession, newItem: CallSession): Boolean {
            return oldItem.status == newItem.status &&
                    oldItem.durationSeconds == newItem.durationSeconds &&
                    oldItem.startedAt == newItem.startedAt
        }
    }
}
