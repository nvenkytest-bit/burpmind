package io.lmp.copilot.domain.context

import kotlinx.serialization.Serializable

/**
 * Anything that can produce a [ContextItem] for a chat thread.
 *
 * Implementations live in infra (Burp request, file, site map, …).
 * The chat layer never knows where context came from.
 */
interface ContextSource {
    val id: SourceId

    /** Resolve a reference (e.g. a Burp request id) into a self-contained [ContextItem]. */
    suspend fun resolve(ref: SourceRef): ContextItem
}

@JvmInline
@Serializable
value class SourceId(val value: String) {
    override fun toString(): String = value
}

@Serializable
data class SourceRef(
    val sourceId: SourceId,
    /** Provider-specific identifier. For BurpRequestSource this is the request handle id. */
    val ref: String,
    /** Optional, human-readable hint (e.g. "Repeater tab #4"). */
    val label: String? = null,
)

/**
 * A normalized, self-contained piece of context. After resolution, the item carries
 * everything needed to feed it to a prompt — no further IO needed.
 */
@Serializable
data class ContextItem(
    val id: String,
    val source: SourceRef,
    val kind: Kind,
    val title: String,
    /** Markdown-friendly rendering used in prompts. */
    val body: String,
    /** Bytes-on-the-wire form, kept for accurate analysis if the renderer is lossy. */
    val rawBytes: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    enum class Kind { HttpRequest, HttpResponse, WebSocketMessage, Note, File, SiteMapSummary, Other }
}
