package io.lmp.copilot.domain.chat

import io.lmp.copilot.domain.artifacts.Artifact
import io.lmp.copilot.domain.context.ContextItem
import io.lmp.copilot.domain.context.SourceRef
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class ThreadId(val value: String) {
    override fun toString(): String = value
}

@Serializable
data class ThreadMeta(
    val id: ThreadId,
    val title: String,
    val providerId: String,
    val model: String,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = createdAt,
    val tags: List<String> = emptyList(),
    val schemaVersion: Int = 1,
)

@Serializable
data class Message(
    val id: String,
    val threadId: ThreadId,
    val role: Role,
    val content: String,
    val createdAt: Instant = Clock.System.now(),
    /** When an Assistant message produced one or more artifacts, list their ids here. */
    val artifactIds: List<String> = emptyList(),
    /** Which pinned context items were sent with this message (for audit + reproducibility). */
    val contextRefs: List<SourceRef> = emptyList(),
    /**
     * When non-null, this message represents the user attaching an item to the chat
     * (right-click → "Add to chat"). The bubble is rendered as a collapsible card
     * and the attachment body is included in the prompt with a numeric `[N]` label.
     *
     * Added in schemaVersion 2. Records persisted with schemaVersion 1 simply have null.
     */
    val attachment: ContextItem? = null,
    /**
     * Stable per-thread number used as the `[N]` label in prompts and the UI.
     * Only meaningful when [attachment] != null. Assigned at attach time; never reused.
     */
    val attachmentNumber: Int? = null,
    /**
     * Model's internal reasoning text, separate from [content]. Captured from
     * backends that expose it (Ollama `thinking`, OpenAI `reasoning_content`, etc.).
     * Shown in a collapsible "Thinking" section in the UI; not fed back to the model
     * as part of conversation history.
     *
     * Added in schemaVersion 3.
     */
    val thinking: String? = null,
    /** True while tokens are still streaming in. */
    val streaming: Boolean = false,
    val schemaVersion: Int = 3,
) {
    enum class Role { User, Assistant, System }
}

/**
 * A complete in-memory view of a thread, assembled by [io.lmp.copilot.app.ThreadService].
 * Persistence is append-only; this is the projection.
 */
data class ThreadView(
    val meta: ThreadMeta,
    val messages: List<Message>,
    val pinnedContext: List<ContextItem>,
    val artifacts: List<Artifact>,
)
