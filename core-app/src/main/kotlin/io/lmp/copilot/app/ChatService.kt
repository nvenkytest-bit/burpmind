package io.lmp.copilot.app

import io.lmp.copilot.app.persistence.ThreadStore
import io.lmp.copilot.app.prompts.PromptResolver
import io.lmp.copilot.domain.chat.Message
import io.lmp.copilot.domain.chat.ThreadId
import io.lmp.copilot.domain.context.ContextItem
import io.lmp.copilot.domain.llm.ChatChunk
import io.lmp.copilot.domain.llm.ChatMessage
import io.lmp.copilot.domain.llm.ChatRequest
import io.lmp.copilot.domain.llm.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * The chat use case.
 *
 * Two ways the user supplies context:
 *
 *  1. **Pinned context** — top-bar chips. Always included with every message until unpinned.
 *  2. **Chat attachments** — right-click → "Add to chat". Appear inline as collapsible bubbles
 *     in the conversation. Numbered `[N]` with stable per-thread IDs so the user can reference
 *     them naturally ("explain [1]", "compare [1] and [2]").
 *
 * Pinned context is for the always-present "this is the endpoint under investigation" case.
 * Attachments are for "discuss this specific thing now" — typically the more common gesture.
 */
class ChatService(
    private val store: ThreadStore,
    private val providerLookup: () -> LlmProvider,
    private val promptResolver: PromptResolver,
) {

    /**
     * Attach an item to the chat history as a new user-side message. Returns the assigned
     * stable attachment number that the UI and prompts will use as `[N]`.
     *
     * The item is persisted immediately; nothing is sent to the model until the user
     * next calls [sendMessage].
     */
    suspend fun attachToChat(threadId: ThreadId, item: ContextItem): Int {
        val number = nextAttachmentNumber(threadId)
        val message = Message(
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            role = Message.Role.User,
            content = "Attached: ${item.title}",
            attachment = item,
            attachmentNumber = number,
        )
        store.appendMessage(message)
        return number
    }

    /**
     * Send a user message in [threadId]. Returns a Flow of [ChatChunk]s.
     * The Flow is hot from the LLM's perspective — collecting drives the network call.
     */
    suspend fun sendMessage(
        threadId: ThreadId,
        userInput: String,
        pinnedContext: List<ContextItem>,
    ): Flow<ChatChunk> {
        val meta = store.loadMeta(threadId)
            ?: error("Thread $threadId does not exist")
        val history = store.loadMessages(threadId)

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            role = Message.Role.User,
            content = userInput,
            contextRefs = pinnedContext.map { it.source },
        )
        store.appendMessage(userMessage)

        val prompt = promptResolver.resolveDefault()

        val messages = buildList {
            add(ChatMessage(ChatMessage.Role.System, prompt.system))
            if (pinnedContext.isNotEmpty() || history.any { it.attachment != null }) {
                add(ChatMessage(ChatMessage.Role.System, REFERENCE_GUIDE))
            }
            if (pinnedContext.isNotEmpty()) {
                add(ChatMessage(ChatMessage.Role.System, renderPinnedBlock(pinnedContext)))
            }
            history.forEach { m ->
                val role = when (m.role) {
                    Message.Role.User -> ChatMessage.Role.User
                    Message.Role.Assistant -> ChatMessage.Role.Assistant
                    Message.Role.System -> ChatMessage.Role.System
                }
                val rendered = renderHistoryMessage(m)
                add(ChatMessage(role, rendered))
            }
            add(ChatMessage(ChatMessage.Role.User, userInput))
        }

        val request = ChatRequest(model = meta.model, messages = messages)
        val provider = providerLookup()

        return flow {
            val contentBuffer = StringBuilder()
            val thinkingBuffer = StringBuilder()
            val assistantId = UUID.randomUUID().toString()
            try {
                provider.chat(request).collect { chunk ->
                    when (chunk) {
                        is ChatChunk.TextDelta -> contentBuffer.append(chunk.text)
                        is ChatChunk.ThinkingDelta -> thinkingBuffer.append(chunk.text)
                        else -> { /* pass through */ }
                    }
                    emit(chunk)
                }
            } catch (t: Throwable) {
                emit(ChatChunk.Error(t.message ?: "stream failed", t))
            } finally {
                val finalText = contentBuffer.toString()
                val finalThinking = thinkingBuffer.toString().takeIf { it.isNotBlank() }
                if (finalText.isNotEmpty() || finalThinking != null) {
                    store.appendMessage(
                        Message(
                            id = assistantId,
                            threadId = threadId,
                            role = Message.Role.Assistant,
                            content = finalText,
                            thinking = finalThinking,
                            createdAt = Clock.System.now(),
                            streaming = false,
                        ),
                    )
                }
            }
        }
    }

    // ---- helpers ----

    private suspend fun nextAttachmentNumber(threadId: ThreadId): Int {
        val highest = store.loadMessages(threadId)
            .mapNotNull { it.attachmentNumber }
            .maxOrNull()
            ?: 0
        return highest + 1
    }

    private fun renderHistoryMessage(m: Message): String {
        val attachment = m.attachment
        return if (attachment != null && m.attachmentNumber != null) {
            buildString {
                append("[Attachment ${m.attachmentNumber}] ")
                append(attachment.title)
                append("\n")
                append(attachment.body.take(MAX_ATTACHMENT_CHARS))
                if (attachment.body.length > MAX_ATTACHMENT_CHARS) append("\n…(truncated)")
            }
        } else {
            m.content
        }
    }

    private fun renderPinnedBlock(items: List<ContextItem>): String = buildString {
        append("# Currently pinned context (persistent across this thread)\n\n")
        items.forEachIndexed { i, item ->
            append("## Pinned ${i + 1}: ${item.title}\n")
            append("_Kind: ${item.kind}_\n\n")
            append("```\n")
            append(item.body.take(MAX_PIN_CHARS))
            if (item.body.length > MAX_PIN_CHARS) append("\n…(truncated)")
            append("\n```\n\n")
        }
    }

    companion object {
        private const val MAX_ATTACHMENT_CHARS = 16_000
        private const val MAX_PIN_CHARS = 8_000

        private val REFERENCE_GUIDE = """
            Conversation reference scheme:
              • The user may attach items to the chat with stable numeric labels in
                square brackets: [1], [2], [3]…  When the user writes "[N]" or
                "attachment N", they are referring to attachment N (do not invent
                attachments that have not been provided).
              • The user may also pin items as persistent context — those appear in
                the system message under "Currently pinned context" and are present
                for every turn.
              • If a question is ambiguous about which item to discuss, ask which one.
              • Never fabricate content for an attachment number that was not supplied.
        """.trimIndent()
    }
}
