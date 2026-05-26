package io.lmp.copilot.app

import io.lmp.copilot.app.persistence.ThreadStore
import io.lmp.copilot.domain.chat.ThreadId
import io.lmp.copilot.domain.context.ContextItem
import io.lmp.copilot.domain.context.ContextSource
import io.lmp.copilot.domain.context.SourceId
import io.lmp.copilot.domain.context.SourceRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Mediates between [ContextSource] implementations and pinned-context persistence.
 *
 * Adding a new context source = registering a [ContextSource] here. The chat layer
 * never knows whether something came from Burp, a file, or thin air.
 */
class ContextService(private val store: ThreadStore) {

    private val sources = ConcurrentHashMap<SourceId, ContextSource>()

    fun register(source: ContextSource) {
        sources[source.id] = source
    }

    suspend fun resolve(ref: SourceRef): ContextItem {
        val source = sources[ref.sourceId]
            ?: error("No ContextSource registered for ${ref.sourceId}")
        return source.resolve(ref)
    }

    suspend fun pin(threadId: ThreadId, item: ContextItem) {
        val existing = store.loadPinnedContext(threadId).toMutableList()
        existing.removeAll { it.id == item.id }
        existing.add(item)
        store.savePinnedContext(threadId, existing)
    }

    suspend fun unpin(threadId: ThreadId, contextItemId: String) {
        val existing = store.loadPinnedContext(threadId).filterNot { it.id == contextItemId }
        store.savePinnedContext(threadId, existing)
    }

    suspend fun list(threadId: ThreadId): List<ContextItem> = store.loadPinnedContext(threadId)
}
