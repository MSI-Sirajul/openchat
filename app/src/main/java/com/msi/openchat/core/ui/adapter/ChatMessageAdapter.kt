package com.msi.openchat.core.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.databinding.ItemChatMessageBinding
import com.msi.openchat.core.common.DateTimeUtils
import com.msi.openchat.data.model.Message

class ChatMessageAdapter(
    private val currentUserId: String?,
    private val isDark: Boolean = false,
    private val onPlayVoice: (Message) -> Unit,
    private val onResolveUrl: (ref: String, callback: (String?) -> Unit) -> Unit,
    private val onMediaClick: (message: Message, resolvedUrl: String) -> Unit = { _, _ -> },
    private val onAttachmentClick: (message: Message) -> Unit = { _ -> }
) : ListAdapter<Message, ChatMessageAdapter.ChatMessageViewHolder>(DiffCallback) {

    private var currentlyPlayingId: String? = null
    private var playbackProgress: Float = 0f

    fun updatePlaybackState(playingId: String?, progress: Float) {
        val previousId = currentlyPlayingId
        currentlyPlayingId = playingId
        playbackProgress = progress

        if (previousId != null) {
            val prevPos = currentList.indexOfFirst { it.id == previousId }
            if (prevPos != -1) notifyItemChanged(prevPos)
        }
        if (playingId != null) {
            val currPos = currentList.indexOfFirst { it.id == playingId }
            if (currPos != -1) notifyItemChanged(currPos)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessageViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatMessageViewHolder(binding, currentUserId, isDark, onPlayVoice, onResolveUrl, onMediaClick, onAttachmentClick)
    }

    override fun onBindViewHolder(holder: ChatMessageViewHolder, position: Int) {
        val message = getItem(position)
        val showDateSeparator = position == 0 ||
                DateTimeUtils.isDifferentDay(getItem(position - 1).createdAt, message.createdAt)
        val isPlaying = currentlyPlayingId == message.id
        holder.bind(message, showDateSeparator, isPlaying, if (isPlaying) playbackProgress else 0f)
    }

    class ChatMessageViewHolder(
        private val binding: ItemChatMessageBinding,
        private val currentUserId: String?,
        private val isDark: Boolean,
        private val onPlayVoice: (Message) -> Unit,
        private val onResolveUrl: (ref: String, callback: (String?) -> Unit) -> Unit,
        private val onMediaClick: (message: Message, resolvedUrl: String) -> Unit,
        private val onAttachmentClick: (message: Message) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            message: Message,
            showDateSeparator: Boolean,
            isPlaying: Boolean,
            progress: Float
        ) {
            val context = binding.root.context

            val outgoingBg = androidx.core.content.ContextCompat.getColor(context, R.color.color_bubble_outgoing)
            val incomingBg = androidx.core.content.ContextCompat.getColor(context, R.color.color_bubble_incoming)
            val outgoingText = androidx.core.content.ContextCompat.getColor(context, R.color.color_bubble_outgoing_text)
            val incomingText = androidx.core.content.ContextCompat.getColor(context, R.color.color_bubble_incoming_text)
            val outgoingTime = androidx.core.content.ContextCompat.getColor(context, R.color.color_timestamp_outgoing)
            val incomingTime = androidx.core.content.ContextCompat.getColor(context, R.color.color_timestamp_incoming)
            val primaryAccentColor = androidx.core.content.ContextCompat.getColor(context, R.color.color_primary)
            val dateCapsuleText = androidx.core.content.ContextCompat.getColor(context, R.color.color_date_capsule_text)
            val readTickColor = androidx.core.content.ContextCompat.getColor(context, R.color.color_read_tick)
            val tickColor = androidx.core.content.ContextCompat.getColor(context, R.color.color_delivered_tick)

            // Date separator capsule
            if (showDateSeparator) {
                binding.textDateSeparator.visibility = View.VISIBLE
                binding.textDateSeparator.setBackgroundResource(R.drawable.bg_date_capsule)
                binding.textDateSeparator.setTextColor(dateCapsuleText)
                binding.textDateSeparator.text = DateTimeUtils.formatDateSeparator(message.createdAt)
            } else {
                binding.textDateSeparator.visibility = View.GONE
            }

            val isOutgoing = message.senderId == currentUserId
            val params = binding.layoutBubbleContainer.layoutParams as LinearLayout.LayoutParams

            // Sender name in groups
            if (!isOutgoing && !message.senderName.isNullOrBlank()) {
                binding.textSenderName.visibility = View.VISIBLE
                binding.textSenderName.text = message.senderName
            } else {
                binding.textSenderName.visibility = View.GONE
            }

            val bubbleTextColor = if (isOutgoing) outgoingText else incomingText
            val bubbleTimeColor = if (isOutgoing) outgoingTime else incomingTime
            val bubbleBgColor = if (isOutgoing) outgoingBg else incomingBg

            val maxBubbleMargin = (48 * context.resources.displayMetrics.density).toInt()
            if (isOutgoing) {
                params.gravity = Gravity.END
                params.marginStart = maxBubbleMargin
                params.marginEnd = 0
                val bg = (context.getDrawable(R.drawable.bg_bubble_outgoing)?.mutate() as? GradientDrawable)
                    ?: GradientDrawable()
                bg.setColor(bubbleBgColor)
                binding.layoutBubbleContainer.background = bg
            } else {
                params.gravity = Gravity.START
                params.marginStart = 0
                params.marginEnd = maxBubbleMargin
                val bg = (context.getDrawable(R.drawable.bg_bubble_incoming)?.mutate() as? GradientDrawable)
                    ?: GradientDrawable()
                bg.setColor(bubbleBgColor)
                binding.layoutBubbleContainer.background = bg
            }
            binding.layoutBubbleContainer.layoutParams = params

            binding.textMessageBody.setTextColor(bubbleTextColor)
            binding.textMessageTime.setTextColor(bubbleTimeColor)
            binding.textVoiceDuration.setTextColor(bubbleTextColor)
            binding.textFileName.setTextColor(bubbleTextColor)
            binding.btnPlayVoice.setColorFilter(primaryAccentColor)

            binding.textMessageTime.text = DateTimeUtils.formatMessageTime(context, message.createdAt)

            // Message Status & Read Receipts (Single / Double tick with uniform stroke)
            if (isOutgoing) {
                binding.imageMessageStatus.visibility = View.VISIBLE
                when (message.status) {
                    "read" -> {
                        binding.textSeenStatus.visibility = View.GONE
                        binding.imageMessageStatus.setImageResource(R.drawable.ic_check_double_16)
                        binding.imageMessageStatus.setColorFilter(readTickColor)
                        binding.imageMessageStatus.contentDescription = "Read"
                    }
                    "delivered" -> {
                        binding.textSeenStatus.visibility = View.GONE
                        binding.imageMessageStatus.setImageResource(R.drawable.ic_check_double_16)
                        binding.imageMessageStatus.setColorFilter(tickColor)
                        binding.imageMessageStatus.contentDescription = "Delivered"
                    }
                    "sending" -> {
                        binding.textSeenStatus.visibility = View.GONE
                        binding.imageMessageStatus.setImageResource(R.drawable.ic_clock_16)
                        binding.imageMessageStatus.setColorFilter(tickColor)
                        binding.imageMessageStatus.contentDescription = "Sending"
                    }
                    else -> { // "sent"
                        binding.textSeenStatus.visibility = View.GONE
                        binding.imageMessageStatus.setImageResource(R.drawable.ic_check_single_16)
                        binding.imageMessageStatus.setColorFilter(tickColor)
                        binding.imageMessageStatus.contentDescription = "Sent"
                    }
                }
            } else {
                binding.textSeenStatus.visibility = View.GONE
                binding.imageMessageStatus.visibility = View.GONE
            }

            // Types: "text", "audio" (voice), "image", "video", "file"
            when (message.type) {
                "audio", "voice" -> {
                    binding.layoutVoiceNote.visibility = View.VISIBLE
                    binding.textMessageBody.visibility = View.GONE
                    binding.layoutMediaContainer.visibility = View.GONE
                    binding.layoutFileAttachment.visibility = View.GONE

                    binding.btnPlayVoice.setImageResource(
                        if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24
                    )
                    binding.progressVoice.progress = (progress * 100).toInt()

                    val durationSec = message.mediaDurationSeconds
                    binding.textVoiceDuration.text = if (durationSec > 0) "${durationSec}s" else "Voice"

                    binding.btnPlayVoice.setOnClickListener {
                        onPlayVoice(message)
                    }
                }
                "image", "video" -> {
                    binding.layoutVoiceNote.visibility = View.GONE
                    binding.layoutMediaContainer.visibility = View.VISIBLE
                    binding.layoutFileAttachment.visibility = View.GONE

                    val isVideo = message.type == "video"
                    binding.layoutVideoOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE

                    val contentText = message.content.orEmpty().trim()
                    val isPlaceholder = contentText.isBlank() ||
                        contentText.equals("Photo", ignoreCase = true) ||
                        contentText.contains("Photo", ignoreCase = true) ||
                        contentText.equals("Video", ignoreCase = true) ||
                        contentText.contains("Video", ignoreCase = true) ||
                        contentText.equals("Voice message", ignoreCase = true) ||
                        contentText.contains("Voice", ignoreCase = true) ||
                        contentText.equals("Attachment", ignoreCase = true) ||
                        contentText.contains("Document", ignoreCase = true) ||
                        contentText.startsWith("📷") ||
                        contentText.startsWith("📹") ||
                        contentText.startsWith("🎤") ||
                        contentText.startsWith("📎")

                    if (contentText.isNotBlank() && !isPlaceholder) {
                        binding.textMessageBody.visibility = View.VISIBLE
                        binding.textMessageBody.text = contentText
                    } else {
                        binding.textMessageBody.visibility = View.GONE
                    }

                    binding.layoutMediaContainer.clipToOutline = true

                    var currentLoadedUrl: String? = message.localFilePath ?: message.mediaUrl
                    var isLoaded = false

                    // 1. Direct content:// URI check (zero-copy, direct rendering)
                    if (!message.localFilePath.isNullOrBlank() && message.localFilePath.startsWith("content://")) {
                        val uri = android.net.Uri.parse(message.localFilePath)
                        if (com.msi.openchat.core.media.PersistableUriManager.isUriAccessible(context, uri)) {
                            currentLoadedUrl = message.localFilePath
                            loadUriMedia(binding.imageAttachment, context, uri, isVideo)
                            isLoaded = true
                        } else {
                            binding.imageAttachment.setImageResource(if (isVideo) R.drawable.ic_video_24 else R.drawable.ic_image_24)
                            binding.imageAttachment.setColorFilter(bubbleTimeColor)
                            isLoaded = true
                        }
                    }

                    // 2. Direct local file path or receiver cache check
                    var localFile: java.io.File? = null
                    if (!isLoaded && !message.localFilePath.isNullOrBlank()) {
                        val f = java.io.File(message.localFilePath.removePrefix("file://"))
                        if (f.exists() && f.length() > 0) {
                            localFile = f
                        }
                    }
                    if (!isLoaded && localFile == null) {
                        val cached = com.msi.openchat.core.media.MediaDownloadManager.getCachedFile(
                            context = context,
                            messageId = message.id,
                            remoteUrl = message.mediaUrl,
                            type = message.type,
                            originalName = message.content
                        )
                        if (cached != null && cached.exists() && cached.length() > 0) {
                            localFile = cached
                        }
                    }

                    if (!isLoaded) {
                        if (localFile != null && localFile.exists() && localFile.length() > 0) {
                            currentLoadedUrl = "file://${localFile.absolutePath}"
                            loadImageFile(binding.imageAttachment, localFile, isVideo)
                        } else {
                            val mediaUrl = message.mediaUrl
                            if (!mediaUrl.isNullOrBlank()) {
                                if (mediaUrl.startsWith("data:")) {
                                    try {
                                        val rawBase64 = if (mediaUrl.contains(",")) mediaUrl.substringAfter(",") else mediaUrl
                                        val bytes = android.util.Base64.decode(rawBase64, android.util.Base64.DEFAULT)
                                        val opts = android.graphics.BitmapFactory.Options().apply {
                                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                        }
                                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                        if (bitmap != null) {
                                            binding.imageAttachment.setImageBitmap(bitmap)
                                            currentLoadedUrl = mediaUrl
                                        }
                                    } catch (t: Throwable) {
                                        android.util.Log.e("ChatMessageAdapter", "Error decoding base64 image: ${t.message}")
                                    }
                                } else if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
                                    currentLoadedUrl = mediaUrl
                                    binding.imageAttachment.load(mediaUrl) {
                                        crossfade(true)
                                    }
                                } else {
                                    onResolveUrl(mediaUrl) { resolved ->
                                        if (resolved != null) {
                                            currentLoadedUrl = resolved
                                            if (resolved.startsWith("data:")) {
                                                try {
                                                    val raw = if (resolved.contains(",")) resolved.substringAfter(",") else resolved
                                                    val b = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
                                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                                    }
                                                    val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, opts)
                                                    if (bmp != null) {
                                                        binding.imageAttachment.post { binding.imageAttachment.setImageBitmap(bmp) }
                                                    }
                                                } catch (_: Throwable) {}
                                            } else {
                                                binding.imageAttachment.post {
                                                    binding.imageAttachment.load(resolved) {
                                                        crossfade(true)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    binding.layoutMediaContainer.setOnClickListener {
                        val finalUrl = currentLoadedUrl ?: message.mediaUrl ?: localFile?.let { "file://${it.absolutePath}" }
                        if (!finalUrl.isNullOrBlank()) {
                            onMediaClick(message, finalUrl)
                        }
                    }
                }
                "file" -> {
                    binding.layoutVoiceNote.visibility = View.GONE
                    binding.layoutMediaContainer.visibility = View.GONE
                    binding.layoutFileAttachment.visibility = View.VISIBLE

                    val rawContent = message.content.orEmpty().trim()
                    val localPath = message.localFilePath
                    val isRevokedOrDeleted = !localPath.isNullOrBlank() && localPath.startsWith("content://") &&
                            !com.msi.openchat.core.media.PersistableUriManager.isUriAccessible(context, android.net.Uri.parse(localPath))

                    val displayFileName = if (isRevokedOrDeleted) {
                        "$rawContent (Unavailable)"
                    } else if (rawContent.isNotBlank() && rawContent != "Attachment" && rawContent != "📎 Document") {
                        rawContent
                    } else {
                        "Document_${message.id.take(6)}"
                    }
                    binding.textFileName.text = displayFileName
                    binding.textMessageBody.visibility = View.GONE

                    binding.layoutFileAttachment.setOnClickListener {
                        onAttachmentClick(message)
                    }
                }
                else -> {
                    binding.layoutVoiceNote.visibility = View.GONE
                    binding.layoutMediaContainer.visibility = View.GONE
                    binding.layoutFileAttachment.visibility = View.GONE
                    binding.textMessageBody.visibility = View.VISIBLE
                    binding.textMessageBody.text = message.content.orEmpty()
                }
            }
        }

        private fun loadUriMedia(imageView: android.widget.ImageView, context: android.content.Context, uri: android.net.Uri, isVideo: Boolean) {
            imageView.clearColorFilter()
            if (isVideo) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val frame = retriever.getFrameAtTime(1000000)
                    retriever.release()
                    if (frame != null) {
                        imageView.setImageBitmap(frame)
                        return
                    }
                } catch (_: Throwable) {}
            }
            imageView.load(uri) {
                crossfade(true)
            }
        }

        private fun loadImageFile(imageView: android.widget.ImageView, file: java.io.File, isVideo: Boolean) {
            if (isVideo) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(1000000)
                    retriever.release()
                    if (frame != null) {
                        imageView.setImageBitmap(frame)
                        return
                    }
                } catch (_: Throwable) {}
            }
            try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) {
                    sample *= 2
                }
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    return
                }
            } catch (_: Throwable) {}
            imageView.load(file) {
                crossfade(true)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.status == newItem.status &&
                    oldItem.content == newItem.content &&
                    oldItem.mediaUrl == newItem.mediaUrl
        }
    }
}
