package com.msi.openchat.core.ai

import com.example.BuildConfig
import com.msi.openchat.core.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OpenChatAiApiMessage(
    val role: String,
    val content: String
)

class OpenChatAiRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val apiKeyProvider: () -> String = { BuildConfig.OPENROUTER_API_KEY }
) {

    fun sendChatMessageStream(
        conversationHistory: List<OpenChatAiApiMessage>,
        model: String = MODEL_DEEPSEEK_CHAT,
        maxTokens: Int = CHAT_MAX_TOKENS
    ): Flow<String> = flow {
        ensureOpenChatAiEnabled()
        val apiKey = requireConfiguredApiKey()
        val messages = buildList {
            add(OpenChatAiApiMessage(role = "system", content = ASSISTANT_SYSTEM_PROMPT))
            addAll(conversationHistory)
        }

        val candidates = resolveCandidateModels(model)
        var lastError: IllegalStateException? = null

        for (candidateModel in candidates) {
            var currentMaxTokens = maxTokens
            var modelUnavailable = false

            for (attempt in 0..1) {
                val payload = buildChatPayload(
                    model = candidateModel,
                    messages = messages,
                    stream = true,
                    maxTokens = currentMaxTokens
                )
                val request = buildOpenRouterRequest(apiKey, payload)
                var retryWithLowerTokens = false

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string().orEmpty()
                        val parsedMsg = parseOpenRouterError(response.code, errBody)
                        if (response.code == 402) {
                            val affordable = extractAffordableMaxTokens(parsedMsg)
                            if (affordable != null && affordable >= MIN_RETRY_MAX_TOKENS && affordable < currentMaxTokens) {
                                currentMaxTokens = (affordable - TOKEN_SAFETY_BUFFER).coerceAtLeast(MIN_RETRY_MAX_TOKENS)
                                retryWithLowerTokens = true
                                return@use
                            }
                        }
                        if (isModelUnavailableError(response.code, parsedMsg) && candidateModel != MODEL_DEEPSEEK_CHAT) {
                            modelUnavailable = true
                            lastError = IllegalStateException(parsedMsg)
                            return@use
                        }
                        throw IllegalStateException(parsedMsg)
                    }

                    val source = response.body?.source()
                        ?: throw IllegalStateException("OpenRouter returned an empty stream.")

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                        if (!trimmed.startsWith("data:")) continue

                        val data = trimmed.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        val chunkText = extractStreamDeltaText(data)
                        if (chunkText.isNotEmpty()) {
                            emit(chunkText)
                        }
                    }
                    return@flow
                }

                if (modelUnavailable) break
                if (!retryWithLowerTokens) break
            }
        }

        throw (lastError ?: IllegalStateException("No compatible DeepSeek provider is currently available."))
    }.flowOn(Dispatchers.IO)

    suspend fun generateSmartReplies(
        recentMessages: List<String>,
        contactName: String
    ): List<String> {
        ensureOpenChatAiEnabled()
        val transcript = recentMessages.takeLast(10).joinToString("\n")
        val userPrompt = buildString {
            append("Generate exactly 3 short, natural reply suggestions for the user chatting with ")
            append(contactName.ifBlank { "this contact" })
            append(".\nReturn valid JSON only in the format {\"replies\": [\"...\", \"...\", \"...\"]} with each reply under 12 words.\n")
            if (transcript.isNotBlank()) {
                append("Recent messages:\n")
                append(transcript)
            } else {
                append("No messages yet. Suggest 3 friendly conversation starters.")
            }
        }

        val raw = executeChatCompletion(
            model = MODEL_DEEPSEEK_CHAT,
            messages = listOf(
                OpenChatAiApiMessage(
                    role = "system",
                    content = "You generate concise JSON reply suggestions for OpenChat."
                ),
                OpenChatAiApiMessage(role = "user", content = userPrompt)
            ),
            maxTokens = SMART_REPLIES_MAX_TOKENS,
            expectJsonObject = true
        )

        val cleanedJson = stripMarkdownCodeFences(raw)
        return try {
            val json = JSONObject(cleanedJson)
            val repliesArray = json.getJSONArray("replies")
            buildList {
                for (i in 0 until repliesArray.length()) {
                    val item = repliesArray.optString(i).trim()
                    if (item.isNotBlank()) add(item)
                }
            }.take(3)
        } catch (_: Exception) {
            raw.lines()
                .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"').trimEnd('"', ',') }
                .filter { it.isNotBlank() }
                .take(3)
                .ifEmpty { throw IllegalStateException("OpenChat AI returned an unexpected smart reply format.") }
        }
    }

    suspend fun summarizeConversation(
        recentMessages: List<String>,
        contactName: String
    ): String {
        ensureOpenChatAiEnabled()
        if (recentMessages.isEmpty()) {
            throw IllegalStateException("There are no messages in this conversation to summarize yet.")
        }
        val transcript = recentMessages.takeLast(30).joinToString("\n")
        val prompt = "Summarize the key points and any action items from this conversation with " +
            "${contactName.ifBlank { "the contact" }} in 3-5 brief bullet points:\n\n$transcript"

        return executeChatCompletion(
            model = MODEL_DEEPSEEK_CHAT,
            messages = listOf(
                OpenChatAiApiMessage(role = "system", content = ASSISTANT_SYSTEM_PROMPT),
                OpenChatAiApiMessage(role = "user", content = prompt)
            ),
            maxTokens = SUMMARIZE_MAX_TOKENS
        )
    }

    suspend fun polishDraft(draftText: String): String {
        ensureOpenChatAiEnabled()
        val cleaned = draftText.trim()
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("Type a draft message first so OpenChat AI can polish it.")
        }
        val prompt = "Rewrite the following chat message to make it clear, natural, and polite while " +
            "keeping the exact same meaning and length. Return only the rewritten message text:\n\n$cleaned"

        return executeChatCompletion(
            model = MODEL_DEEPSEEK_CHAT,
            messages = listOf(
                OpenChatAiApiMessage(role = "system", content = ASSISTANT_SYSTEM_PROMPT),
                OpenChatAiApiMessage(role = "user", content = prompt)
            ),
            maxTokens = POLISH_DRAFT_MAX_TOKENS
        )
    }

    private suspend fun executeChatCompletion(
        model: String,
        messages: List<OpenChatAiApiMessage>,
        maxTokens: Int = CHAT_MAX_TOKENS,
        expectJsonObject: Boolean = false
    ): String = withTimeout(AI_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            val apiKey = requireConfiguredApiKey()
            var currentMaxTokens = maxTokens

            for (attempt in 0..1) {
                val payload = buildChatPayload(
                    model = model,
                    messages = messages,
                    stream = false,
                    maxTokens = currentMaxTokens,
                    expectJsonObject = expectJsonObject
                )
                val request = buildOpenRouterRequest(apiKey, payload)
                var retryWithLowerTokens = false

                try {
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val parsedMsg = parseOpenRouterError(response.code, body)
                            if (response.code == 402 && attempt == 0) {
                                val affordable = extractAffordableMaxTokens(parsedMsg)
                                if (affordable != null && affordable >= MIN_RETRY_MAX_TOKENS && affordable < currentMaxTokens) {
                                    currentMaxTokens = (affordable - TOKEN_SAFETY_BUFFER).coerceAtLeast(MIN_RETRY_MAX_TOKENS)
                                    retryWithLowerTokens = true
                                    return@use
                                }
                            }
                            throw IllegalStateException(parsedMsg)
                        }
                        return@withContext extractCompletionText(body)
                            ?: throw IllegalStateException("OpenChat AI returned an empty response.")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throw IllegalStateException("Network error contacting OpenChat AI: ${e.localizedMessage ?: "check connection"}", e)
                }

                if (!retryWithLowerTokens) break
            }
            throw IllegalStateException("OpenChat AI request failed to complete.")
        }
    }

    internal fun buildChatPayload(
        model: String,
        messages: List<OpenChatAiApiMessage>,
        stream: Boolean,
        maxTokens: Int = CHAT_MAX_TOKENS,
        expectJsonObject: Boolean = false
    ): String {
        val msgArray = JSONArray()
        for (msg in messages) {
            msgArray.put(
                JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            )
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", msgArray)
            put("max_tokens", maxTokens.coerceAtLeast(MIN_RETRY_MAX_TOKENS))
            put("stream", stream)
            if (expectJsonObject) {
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }
        }.toString()
    }

    private fun buildOpenRouterRequest(apiKey: String, jsonBody: String): Request {
        return Request.Builder()
            .url(OPENROUTER_CHAT_COMPLETIONS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", OPENROUTER_REFERER)
            .addHeader("X-Title", OPENROUTER_APP_TITLE)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    internal fun requireConfiguredApiKey(): String {
        val key = apiKeyProvider().trim()
        if (key.isBlank() || key == PLACEHOLDER_API_KEY) {
            throw IllegalStateException(
                "OpenRouter API key is not configured. Add OPENROUTER_API_KEY in the AI Studio Secrets panel."
            )
        }
        return key
    }

    private fun ensureOpenChatAiEnabled() {
        if (!ServiceLocator.sessionManager.isOpenChatAiEnabled()) {
            throw IllegalStateException("OpenChat AI is turned off. Enable it in Settings to use AI features.")
        }
    }

    companion object {
        const val OPENROUTER_CHAT_COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val MODEL_DEEPSEEK_CHAT = "deepseek/deepseek-chat"
        const val MODEL_DEEPSEEK_R1 = "deepseek/deepseek-r1-0528"
        private const val MODEL_DEEPSEEK_R1_FREE = "deepseek/deepseek-r1-0528:free"

        internal const val CHAT_MAX_TOKENS = 2048
        internal const val SMART_REPLIES_MAX_TOKENS = 256
        internal const val SUMMARIZE_MAX_TOKENS = 512
        internal const val POLISH_DRAFT_MAX_TOKENS = 384
        private const val MIN_RETRY_MAX_TOKENS = 128
        private const val TOKEN_SAFETY_BUFFER = 64

        private const val PLACEHOLDER_API_KEY = "OPENROUTER_API_KEY_DEFAULT_VALUE"
        private const val OPENROUTER_REFERER = "https://openchat.app"
        private const val OPENROUTER_APP_TITLE = "OpenChat AI"
        private const val AI_TIMEOUT_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val AFFORDABLE_TOKENS_REGEX = Regex("""can only afford (\d+)""")

        private const val ASSISTANT_SYSTEM_PROMPT =
            "You are OpenChat AI, a helpful assistant built into the OpenChat messaging app. " +
                "Provide clear, natural, concise responses ready for chat."

        internal fun resolveCandidateModels(requestedModel: String): List<String> {
            return when (requestedModel) {
                MODEL_DEEPSEEK_R1, "deepseek/deepseek-r1" -> listOf(
                    MODEL_DEEPSEEK_R1,
                    MODEL_DEEPSEEK_R1_FREE,
                    MODEL_DEEPSEEK_CHAT
                )
                else -> listOf(requestedModel)
            }.distinct()
        }

        internal fun extractAffordableMaxTokens(errorMessage: String): Int? {
            return AFFORDABLE_TOKENS_REGEX.find(errorMessage)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        internal fun isModelUnavailableError(statusCode: Int, errorMessage: String): Boolean {
            if (statusCode == 404 || statusCode == 410) return true
            val lower = errorMessage.lowercase()
            return lower.contains("no provider serves this model") ||
                lower.contains("no longer available") ||
                lower.contains("no endpoints found")
        }

        internal fun extractStreamDeltaText(dataJson: String): String {
            return try {
                val obj = JSONObject(dataJson)
                val choices = obj.optJSONArray("choices") ?: return ""
                if (choices.length() == 0) return ""
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return ""
                val content = delta.opt("content")
                if (content is String) content else ""
            } catch (_: Exception) {
                ""
            }
        }

        internal fun extractCompletionText(responseJson: String): String? {
            return try {
                val obj = JSONObject(responseJson)
                val choices = obj.optJSONArray("choices") ?: return null
                if (choices.length() == 0) return null
                val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
                message.optString("content").trim().takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        internal fun parseOpenRouterError(statusCode: Int, responseBody: String): String {
            val detail = try {
                val errObj = JSONObject(responseBody).optJSONObject("error")
                errObj?.optString("message")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            return detail ?: "OpenChat AI request failed (HTTP $statusCode)."
        }

        private fun stripMarkdownCodeFences(raw: String): String {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("```")) return trimmed
            return trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }
    }
}
