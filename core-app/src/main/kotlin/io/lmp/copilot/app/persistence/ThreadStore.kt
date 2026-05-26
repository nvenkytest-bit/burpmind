package io.lmp.copilot.app.persistence

import io.lmp.copilot.domain.artifacts.Artifact
import io.lmp.copilot.domain.chat.Message
import io.lmp.copilot.domain.chat.ThreadId
import io.lmp.copilot.domain.chat.ThreadMeta
import io.lmp.copilot.domain.context.ContextItem

/**
 * Append-only persistence for thread data. Implementations may write to the local
 * filesystem (default) or to the Burp project file (via Montoya extensionData).
 *
 * Append-only is non-negotiable: it gives us crash safety, audit trail, and
 * time-travel for free, and avoids the "we can't change the thread model" trap.
 */
interface ThreadStore {
    suspend fun listThreads(): List<ThreadMeta>
    suspend fun loadMeta(id: ThreadId): ThreadMeta?
    suspend fun saveMeta(meta: ThreadMeta)

    suspend fun appendMessage(message: Message)
    suspend fun loadMessages(id: ThreadId): List<Message>

    suspend fun appendArtifact(threadId: ThreadId, artifact: Artifact)
    suspend fun loadArtifacts(id: ThreadId): List<Artifact>

    suspend fun savePinnedContext(id: ThreadId, items: List<ContextItem>)
    suspend fun loadPinnedContext(id: ThreadId): List<ContextItem>

    suspend fun deleteThread(id: ThreadId)
}
