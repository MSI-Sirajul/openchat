package com.msi.openchat.feature.ai

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.ai.OpenChatAiApiMessage
import com.msi.openchat.core.ai.OpenChatAiRepository
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

private const val TAG = "OpenChatAiVM"
private const val STREAM_TIMEOUT_MS = 60_000L

sealed interface OpenChatAiUiState {
    data object Idle : OpenChatAiUiState
    data object Loading : OpenChatAiUiState
    data class Streaming(val text: String) : OpenChatAiUiState
    data class Success(val text: String) : OpenChatAiUiState
    data class Error(val message: String) : OpenChatAiUiState
}

enum class DeepSeekModelOption(val modelId: String) {
    CHAT(OpenChatAiRepository.MODEL_DEEPSEEK_CHAT),
    REASONER(OpenChatAiRepository.MODEL_DEEPSEEK_R1)
}

data class OpenChatAiMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val modelOption: DeepSeekModelOption = DeepSeekModelOption.CHAT,
    val isStreaming: Boolean = false
)

data class OpenChatAiScreenState(
    val messages: List<OpenChatAiMessage> = emptyList(),
    val selectedModel: DeepSeekModelOption = DeepSeekModelOption.CHAT,
    val inferenceState: OpenChatAiUiState = OpenChatAiUiState.Idle,
    val lastFailedPrompt: String? = null
)

class OpenChatAiViewModel(
    private val aiRepository: OpenChatAiRepository = ServiceLocator.openChatAiRepository
) : ViewModel() {

    private var activeJob: Job? = null

    private val _screenState = MutableStateFlow(OpenChatAiScreenState())
    val screenState: StateFlow<OpenChatAiScreenState> = _screenState.asStateFlow()

    fun toggleModel() {
        val next = if (_screenState.value.selectedModel == DeepSeekModelOption.CHAT) {
            DeepSeekModelOption.REASONER
        } else {
            DeepSeekModelOption.CHAT
        }
        _screenState.value = _screenState.value.copy(selectedModel = next)
    }

    fun clearError() {
        if (_screenState.value.inferenceState is OpenChatAiUiState.Error) {
            _screenState.value = _screenState.value.copy(
                inferenceState = OpenChatAiUiState.Idle,
                lastFailedPrompt = null
            )
        }
    }

    fun resetConversation() {
        activeJob?.cancel()
        _screenState.value = OpenChatAiScreenState(
            selectedModel = _screenState.value.selectedModel
        )
    }

    fun sendPrompt(rawPrompt: String) {
        val promptText = rawPrompt.trim()
        if (promptText.isBlank()) return

        activeJob?.cancel()
        val selectedModel = _screenState.value.selectedModel
        val priorHistory = _screenState.value.messages
            .filter { !it.isStreaming && it.text.isNotBlank() }
            .map { msg ->
                OpenChatAiApiMessage(
                    role = if (msg.isUser) "user" else "assistant",
                    content = msg.text
                )
            }

        val userMsg = OpenChatAiMessage(
            isUser = true,
            text = promptText,
            modelOption = selectedModel
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholderAssistantMsg = OpenChatAiMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            modelOption = selectedModel,
            isStreaming = true
        )

        _screenState.value = _screenState.value.copy(
            messages = _screenState.value.messages + userMsg + placeholderAssistantMsg,
            inferenceState = OpenChatAiUiState.Loading,
            lastFailedPrompt = null
        )

        activeJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            try {
                val requestHistory = priorHistory + OpenChatAiApiMessage(
                    role = "user",
                    content = promptText
                )

                withTimeout(STREAM_TIMEOUT_MS) {
                    aiRepository.sendChatMessageStream(
                        conversationHistory = requestHistory,
                        model = selectedModel.modelId
                    ).collect { chunkText ->
                        if (chunkText.isNotEmpty()) {
                            accumulated.append(chunkText)
                            val currentText = accumulated.toString()
                            updateAssistantMessage(assistantMsgId, currentText, isStreaming = true)
                            _screenState.value = _screenState.value.copy(
                                inferenceState = OpenChatAiUiState.Streaming(currentText)
                            )
                        }
                    }
                }

                val finalText = accumulated.toString().trim()
                if (finalText.isNotBlank()) {
                    updateAssistantMessage(assistantMsgId, finalText, isStreaming = false)
                    _screenState.value = _screenState.value.copy(
                        inferenceState = OpenChatAiUiState.Success(finalText)
                    )
                } else {
                    removeMessage(assistantMsgId)
                    _screenState.value = _screenState.value.copy(
                        inferenceState = OpenChatAiUiState.Error("OpenChat AI returned an empty response."),
                        lastFailedPrompt = promptText
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "OpenChat AI streaming timed out", e)
                removeEmptyStreamingPlaceholder(assistantMsgId, accumulated.toString())
                _screenState.value = _screenState.value.copy(
                    inferenceState = OpenChatAiUiState.Error(
                        "Request timed out after 60 seconds. Please try again."
                    ),
                    lastFailedPrompt = promptText
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OpenChat AI generation failed", e)
                removeEmptyStreamingPlaceholder(assistantMsgId, accumulated.toString())
                _screenState.value = _screenState.value.copy(
                    inferenceState = OpenChatAiUiState.Error(
                        e.localizedMessage ?: "Failed to generate response from OpenChat AI."
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
