package io.lmp.copilot.infra.ollama

import io.lmp.copilot.domain.llm.ChatChunk
import io.lmp.copilot.domain.llm.ChatRequest
import io.lmp.copilot.domain.llm.HealthStatus
import io.lmp.copilot.domain.llm.LlmProvider
import io.lmp.copilot.domain.llm.ModelInfo
import io.lmp.copilot.domain.llm.OutputFormat
import io.lmp.copilot.domain.llm.ProviderCapabilities
import io.lmp.copilot.domain.llm.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to a local Ollama instance on http://localhost:11434.
 *
 * Streaming uses Ollama's NDJSON response (one JSON object per line). We never load
 * the full body into memory — `Source.readUtf8LineStrict()` walks chunk-by-chunk.
 */
class OllamaProvider(
    private val config: Config = Config(),
) : LlmProvider {

    data class Config(
        val baseUrl: String = "http://localhost:11434",
        val connectTimeoutMillis: Long = 5_000,
        val readTimeoutMillis: Long = 600_000, // long generations
    )

    override val id: ProviderId = ProviderId("ollama")
    override val displayName: String = "Ollama (local)"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        jsonMode = true,
        toolCalling = true,
        embeddings = true,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun healthCheck(): HealthStatus {
        val request = Request.Builder().url("${config.baseUrl}/api/version").get().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    HealthStatus.Ok(versionInfo = response.body?.string()?.trim())
                } else {
                    HealthStatus.Unreachable("HTTP ${response.code}")
                }
            }
        }.getOrElse { HealthStatus.Unreachable(it.message ?: it::class.simpleName ?: "unknown") }
    }

    override suspend fun listModels(): List<ModelInfo> {
        val request = Request.Builder().url("${config.baseUrl}/api/tags").get().build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Ollama /api/tags failed: HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            val parsed = json.decodeFromString(TagsResponse.serializer(), body)
            return parsed.models.map { m ->
                ModelInfo(
                    id = m.name,
                    displayName = m.name,
                    parameterSize = m.details?.parameterSize,
                    quantization = m.details?.quantizationLevel,
                )
            }
        }
    }

    override fun chat(request: ChatRequest): Flow<ChatChunk> = flow {
        val payload = buildPayload(request)
        val httpRequest = Request.Builder()
            .url("${config.baseUrl}/api/chat")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(ChatChunk.Error("Ollama /api/chat returned HTTP ${response.code}: ${response.body?.string().orEmpty()}"))
                    return@use
                }
                val source = response.body?.source() ?: run {
                    emit(ChatChunk.Error("Empty response body from Ollama"))
                    return@use
                }
                // NDJSON: one JSON object per line.
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = try {
                        json.parseToJsonElement(line) as? JsonObject ?: continue
                    } catch (e: Exception) {
                        emit(ChatChunk.Error("Malformed NDJSON chunk: $line", e))
                        continue
                    }
                    val errorField = obj["error"]
                    if (errorField != null) {
                        emit(ChatChunk.Error((errorField as? JsonPrimitive)?.content ?: errorField.toString()))
                        continue
                    }
                    val message = obj["message"] as? JsonObject
                    val thinking = (message?.get("thinking") as? JsonPrimitive)?.content
                    if (!thinking.isNullOrEmpty()) {
                        emit(ChatChunk.ThinkingDelta(thinking))
                    }
                    val content = (message?.get("content") as? JsonPrimitive)?.content
                    if (!content.isNullOrEmpty()) {
                        emit(ChatChunk.TextDelta(content))
                    }
                    val done = (obj["done"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
                    if (done) {
                        val reason = (obj["done_reason"] as? JsonPrimitive)?.content
                        emit(ChatChunk.Done(finishReason = reason))
                    }
                }
            }
        } catch (e: Exception) {
            emit(ChatChunk.Error(e.message ?: "stream failed", e))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildPayload(request: ChatRequest): JsonElement = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        // Ollama: enables a thinking phase on reasoning-capable models (deepseek-r1,
        // qwen3, granite-thinker, …). Models without thinking support ignore it.
        if (request.options.enableThinking) {
            put("think", true)
        }
        putJsonArray("messages") {
            request.messages.forEach { m ->
                add(buildJsonObject {
                    put(
                        "role",
                        when (m.role) {
                            io.lmp.copilot.domain.llm.ChatMessage.Role.System -> "system"
                            io.lmp.copilot.domain.llm.ChatMessage.Role.User -> "user"
                            io.lmp.copilot.domain.llm.ChatMessage.Role.Assistant -> "assistant"
                        },
                    )
                    put("content", m.content)
                })
            }
        }
        when (val format = request.outputFormat) {
            is OutputFormat.Json -> put("format", "json").also {
                // future: schema-constrained decoding once Ollama exposes it
                @Suppress("UNUSED_EXPRESSION") format.schema
            }

            OutputFormat.Text -> { /* default */ }
        }
        putJsonObject("options") {
            request.options.temperature?.let { put("temperature", it) }
            request.options.topP?.let { put("top_p", it) }
            request.options.numCtx?.let { put("num_ctx", it) }
            if (request.options.stopSequences.isNotEmpty()) {
                putJsonArray("stop") {
                    request.options.stopSequences.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
    }

    @Serializable
    private data class TagsResponse(val models: List<TagModel> = emptyList())

    @Serializable
    private data class TagModel(
        val name: String,
        val size: Long? = null,
        val details: ModelDetails? = null,
    )

    @Serializable
    private data class ModelDetails(
        @SerialName("parameter_size") val parameterSize: String? = null,
        @SerialName("quantization_level") val quantizationLevel: String? = null,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
