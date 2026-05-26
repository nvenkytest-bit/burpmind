package io.lmp.copilot.domain.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A single LLM backend (Ollama today; LM Studio / vLLM / OpenAI-compatible tomorrow).
 *
 * This is THE seam between application code and any specific model API.
 * Application code never knows what JSON shape Ollama wants.
 */
interface LlmProvider {
    val id: ProviderId
    val displayName: String
    val capabilities: ProviderCapabilities

    suspend fun listModels(): List<ModelInfo>

    /** Streaming-first. Implementations emit small chunks as they arrive from the backend. */
    fun chat(request: ChatRequest): Flow<ChatChunk>

    /** Cheap call to verify the backend is reachable. */
    suspend fun healthCheck(): HealthStatus
}

@JvmInline
@Serializable
value class ProviderId(val value: String) {
    override fun toString(): String = value
}

@Serializable
data class ProviderCapabilities(
    val streaming: Boolean = true,
    val jsonMode: Boolean = false,
    val toolCalling: Boolean = false,
    val vision: Boolean = false,
    val embeddings: Boolean = false,
    val maxContextTokens: Int? = null,
)

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val parameterSize: String? = null,
    val quantization: String? = null,
    val contextLength: Int? = null,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val options: ChatOptions = ChatOptions(),
    val outputFormat: OutputFormat = OutputFormat.Text,
)

@Serializable
data class ChatMessage(
    val role: Role,
    val content: String,
) {
    enum class Role { System, User, Assistant }
}

@Serializable
data class ChatOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val numCtx: Int? = null,
    val timeoutMillis: Long = 120_000,
    val stopSequences: List<String> = emptyList(),
    /**
     * Request the model emit reasoning ("thinking") tokens separately from the final
     * answer when the backend supports it (Ollama's `think` parameter, OpenAI o1
     * `reasoning_content`, etc.). Backends without thinking support ignore this.
     */
    val enableThinking: Boolean = true,
)

@Serializable
sealed interface OutputFormat {
    @Serializable
    data object Text : OutputFormat

    /**
     * Force the model to emit valid JSON. Optionally constrain to a JSON schema
     * (providers may ignore the schema if they don't support constrained decoding).
     */
    @Serializable
    data class Json(val schema: String? = null) : OutputFormat
}

/** A piece of streamed output. Subclassed so non-text events can flow on the same channel. */
sealed interface ChatChunk {
    /** A piece of the final, user-facing assistant answer. */
    data class TextDelta(val text: String) : ChatChunk

    /**
     * A piece of the model's internal reasoning (DeepSeek-R1, Qwen3, etc.). These tokens
     * are typically shown in a collapsible "thinking" section and NOT fed back to the
     * model as part of conversation history.
     */
    data class ThinkingDelta(val text: String) : ChatChunk

    data class Done(val finishReason: String? = null, val stats: ChatStats? = null) : ChatChunk
    data class Error(val message: String, val cause: Throwable? = null) : ChatChunk
}

@Serializable
data class ChatStats(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalDurationNanos: Long? = null,
)

sealed interface HealthStatus {
    data class Ok(val versionInfo: String? = null) : HealthStatus
    data class Unreachable(val reason: String) : HealthStatus
    data class Misconfigured(val reason: String) : HealthStatus
}
