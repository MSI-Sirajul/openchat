package com.msi.openchat.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private const val TAG = "GeminiAiRepository"
private const val DEFAULT_MODEL = "gemini-3.8-flash"
private const val FAST_MODEL = "gemini-3.5-flash-lite"
private const val AI_TIMEOUT_MS = 60_000L

class GeminiAiRepository {

    private val assistantModel by lazy {
        Firebase.ai.generativeModel(
            modelName = DEFAULT_MODEL,
            systemInstruction = content {
                text(
                    "You are OpenChat's built-in Gemini AI assistant. " +
                        "Provide clear, helpful, concise answers. When helping with messages, " +
                        "keep tone natural and ready to send."
                )
            }
        )
    }

    private val smartReplySchema = Schema.obj(
        properties = mapOf(
            "replies" to Schema.array(
                items = Schema.string(description = "A concise natural reply option under 12 words"),
                description = "Three distinct reply suggestions matching the conversation tone"
            )
        )
    )

    private val smartReplyModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FAST_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = smartReplySchema
            }
        )
    }

    fun startAssistantChat(history: List<Content> = emptyList()): Chat {
        return assistantModel.startChat(history = history)
    }

    fun sendChatMessageStream(
        chat: Chat,
        prompt: String,
        attachedImage: Bitmap? = null
    ): Flow<GenerateContentResponse> {
        val messageContent = content("user") {
            if (attachedImage != null) {
                image(attachedImage)
            }
            if (prompt.isNotBlank()) {
                text(prompt)
            }
        }
        return chat.sendMessageStream(messageContent)
    }

    suspend fun generateSmartReplies(
        recentMessages: List<String>,
        contactName: String
    ): List<String> {
        val transcript = recentMessages.takeLast(10).joinToString("\n")
        val prompt = buildString {
            append("Generate 3 short, natural reply suggestions for the user chatting with ")
            append(contactName.ifBlank { "this contact" })
            append(".\n")
            if (transcript.isNotBlank()) {
                append("Recent messages:\n")
                append(transcript)
            } else {
                append("No messages yet. Suggest 3 friendly conversation starters.")
            }
        }

        val response = withTimeout(AI_TIMEOUT_MS) {
            smartReplyModel.generateContent(prompt)
        }
        val jsonText = response.text?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Gemini returned an empty smart reply response.")

        val json = JSONObject(jsonText)
        val repliesArray = json.getJSONArray("replies")
        return buildList {
            for (i in 0 until repliesArray.length()) {
                val item = repliesArray.optString(i).trim()
                if (item.isNotBlank()) add(item)
            }
        }.take(3)
    }

    suspend fun summarizeConversation(
        recentMessages: List<String>,
        contactName: String
    ): String {
        if (recentMessages.isEmpty()) {
            throw IllegalStateException("There are no messages in this conversation to summarize yet.")
        }
        val transcript = recentMessages.takeLast(30).joinToString("\n")
        val prompt = "Summarize the key points and any action items from this conversation with " +
            "${contactName.ifBlank { "the contact" }} in 3-5 brief bullet points:\n\n$transcript"

        val response = withTimeout(AI_TIMEOUT_MS) {
            assistantModel.generateContent(prompt)
        }
        return response.text?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Gemini returned an empty summary.")
    }

    suspend fun polishDraft(draftText: String): String {
        val cleaned = draftText.trim()
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("Type a draft message first so Gemini can polish it.")
        }
        val prompt = "Rewrite the following chat message to make it clear, natural, and polite while " +
            "keeping the exact same meaning and length. Return only the rewritten message text:\n\n$cleaned"

        val response = withTimeout(AI_TIMEOUT_MS) {
            assistantModel.generateContent(prompt)
        }
        return response.text?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Gemini returned an empty rewrite.")
    }

    suspend fun decodeScaledBitmap(
        context: Context,
        uri: Uri,
        maxDimensionPx: Int = 1280
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.applicationContext.contentResolver
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return@withContext null

            var sampleSize = 1
            while (origWidth / sampleSize > maxDimensionPx * 2 || origHeight / sampleSize > maxDimensionPx * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            val maxSide = maxOf(decoded.width, decoded.height)
            val scaled = if (maxSide > maxDimensionPx) {
                val scale = maxDimensionPx.toFloat() / maxSide.toFloat()
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            // Ensure compressed inline payload stays well below 20MB
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (out.size() > 15 * 1024 * 1024) {
                Log.w(TAG, "Scaled bitmap exceeded expected size (${out.size()} bytes); re-scaling")
                val smaller = Bitmap.createScaledBitmap(
                    scaled,
                    (scaled.width / 2).coerceAtLeast(1),
                    (scaled.height / 2).coerceAtLeast(1),
                    true
                )
                if (smaller !== scaled) scaled.recycle()
                return@withContext smaller
            }
            scaled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image for Gemini analysis", e)
            null
        }
    }
}
