package io.lmp.copilot.app

import io.lmp.copilot.app.persistence.ThreadStore
import io.lmp.copilot.domain.chat.ThreadId
import io.lmp.copilot.domain.chat.ThreadMeta
import io.lmp.copilot.domain.chat.ThreadView
import java.util.UUID

class ThreadService(private val store: ThreadStore) {

    suspend fun listThreads(): List<ThreadMeta> = store.listThreads().sortedByDescending { it.updatedAt }

    suspend fun createThread(title: String, providerId: String, model: String): ThreadMeta {
        val meta = ThreadMeta(
            id = ThreadId(UUID.randomUUID().toString()),
            title = title,
            providerId = providerId,
            model = model,
        )
        store.saveMeta(meta)
        return meta
    }

    suspend fun loadView(id: ThreadId): ThreadView? {
        val meta = store.loadMeta(id) ?: return null
        return ThreadView(
            meta = meta,
            messages = store.loadMessages(id),
            pinnedContext = store.loadPinnedContext(id),
            artifacts = store.loadArtifacts(id),
        )
    }

    suspend fun deleteThread(id: ThreadId) = store.deleteThread(id)
}
