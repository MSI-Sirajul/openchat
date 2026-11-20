package com.msi.openchat.feature.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.ai.Chat
import com.msi.openchat.core.ai.GeminiAiRepository
import com.msi.openchat.core.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val TAG = "GeminiAssistantVM"
private const val STREAM_TIMEOUT_MS = 60_000L

sealed interface GeminiUiState {
    data object Idle : GeminiUiState
    data object Loading : GeminiUiState
    data class Streaming(val text: String) : GeminiUiState
    data class Success(val text: String) : GeminiUiState
    data class Error(val message: String) : GeminiUiState
}

data class GeminiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val imageUri: String? = null,
    val isStreaming: Boolean = false
)

data class GeminiAssistantScreenState(
    val messages: List<GeminiChatMessage> = emptyList(),
    val inferenceState: GeminiUiState = GeminiUiState.Idle,
    val selectedImageUri: Uri? = null,
    val lastFailedPrompt: String? = null
)

class GeminiAssistantViewModel(
    private val aiRepository: GeminiAiRepository = ServiceLocator.geminiAiRepository
) : ViewModel() {

    private var activeChat: Chat? = null
    private var activeJob: Job? = null

    private val _screenState = MutableStateFlow(GeminiAssistantScreenState())
    val screenState: StateFlow<GeminiAssistantScreenState> = _screenState.asStateFlow()

    fun attachImage(uri: Uri?) {
        _screenState.value = _screenState.value.copy(selectedImageUri = uri)
    }

    fun clearAttachedImage() {
        _screenState.value = _screenState.value.copy(selectedImageUri = null)
    }

    fun clearError() {
        if (_screenState.value.inferenceState is GeminiUiState.Error) {
            _screenState.value = _screenState.value.copy(
                inferenceState = GeminiUiState.Idle,
                lastFailedPrompt = null
            )
        }
    }

    fun resetConversation() {
        activeJob?.cancel()
        activeChat = null
        _screenState.value = GeminiAssistantScreenState()
    }

    fun sendPrompt(context: Context, rawPrompt: String) {
        val attachedUri = _screenState.value.selectedImageUri
        val promptText = rawPrompt.trim().ifBlank {
            if (attachedUri != null) "Analyze this image and describe what you see." else ""
        }
        if (promptText.isBlank()) return

        activeJob?.cancel()
        val userMsg = GeminiChatMessage(
            isUser = true,
            text = promptText,
            imageUri = attachedUri?.toString()
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholderAssistantMsg = GeminiChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            isStreaming = true
        )

        _screenState.value = _screenState.value.copy(
            messages = _screenState.value.messages + userMsg + placeholderAssistantMsg,
            inferenceState = GeminiUiState.Loading,
            selectedImageUri = null,
            lastFailedPrompt = null
        )

        activeJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            try {
                val bitmap = if (attachedUri != null) {
                    aiRepository.decodeScaledBitmap(context, attachedUri)
                        ?: throw IllegalStateException("Could not load the selected image.")
                } else {
                    null
                }

                val chatSession = activeChat ?: aiRepository.startAssistantChat().also {
                    activeChat = it
                }

                withTimeout(STREAM_TIMEOUT_MS) {
                    aiRepository.sendChatMessageStream(
                        chat = chatSession,
                        prompt = promptText,
                        attachedImage = bitmap
                    ).collect { chunk ->
                        val chunkText = chunk.text.orEmpty()
                        if (chunkText.isNotEmpty()) {
                            accumulated.append(chunkText)
                            val currentText = accumulated.toString()
                            updateAssistantMessage(assistantMsgId, currentText, isStreaming = true)
                            _screenState.value = _screenState.value.copy(
                                inferenceState = GeminiUiState.Streaming(currentText)
                            )
                        }
                    }
                }

                val finalText = accumulated.toString().trim()
                if (finalText.isNotBlank()) {
                    updateAssistantMessage(assistantMsgId, finalText, isStreaming = false)
                    _screenState.value = _screenState.value.copy(
                        inferenceState = GeminiUiState.Success(finalText)
                    )
                } else {
                    removeMessage(assistantMsgId)
                    _screenState.value = _screenState.value.copy(
                        inferenceState = GeminiUiState.Error("Gemini returned an empty response."),
                        lastFailedPrompt = promptText
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini streaming timed out", e)
                removeEmptyStreamingPlaceholder(assistantMsgId, accumulated.toString())
                _screenState.value = _screenState.value.copy(
                    inferenceState = GeminiUiState.Error(
                        "Request timed out after 60 seconds. Please try again."
                    ),
                    lastFailedPrompt = promptText
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI generation failed", e)
                removeEmptyStreamingPlaceholder(assistantMsgId, accumulated.toString())
                _screenState.value = _screenState.value.copy(
                    inferenceState = GeminiUiState.Error(
                        e.localizedMessage ?: "Failed to generate response from Gemini."
                    ),
                    lastFailedPrompt = promptText
                )
            }
        }
    }

    private fun updateAssistantMessage(id: String, text: String, isStreaming: Boolean) {
        val updated = _screenState.value.messages.map { msg ->
            if (msg.id == id) msg.copy(text = text, isStreaming = isStreaming) else msg
        }
        _screenState.value = _screenState.value.copy(messages = updated)
    }

    private fun removeMessage(id: String) {
        _screenState.value = _screenState.value.copy(
            messages = _screenState.value.messages.filterNot { it.id == id }
        )
    }

    private fun removeEmptyStreamingPlaceholder(id: String, partialText: String) {
        if (partialText.isBlank()) {
            removeMessage(id)
        } else {
            updateAssistantMessage(id, partialText.trim(), isStreaming = false)
        }
    }
}
