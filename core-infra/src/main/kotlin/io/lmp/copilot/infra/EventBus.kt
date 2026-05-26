package io.lmp.copilot.infra

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus. Subscribers consume facts; producers don't know who is listening.
 * The first version is intentionally tiny — coroutines.SharedFlow is enough.
 */
class EventBus {
    private val _events = MutableSharedFlow<DomainEvent>(extraBufferCapacity = 64)
    val events: Flow<DomainEvent> = _events.asSharedFlow()

    suspend fun emit(event: DomainEvent) = _events.emit(event)
    fun tryEmit(event: DomainEvent): Boolean = _events.tryEmit(event)
}

/**
 * Versioned event types. New fields ⇒ bump the `v` int; old subscribers keep working.
 * Never mutate existing event shapes.
 */
sealed interface DomainEvent {
    val v: Int

    data class ProviderConfigured(override val v: Int = 1, val providerId: String, val model: String) : DomainEvent
    data class ThreadCreated(override val v: Int = 1, val threadId: String) : DomainEvent
    data class ThreadSelected(override val v: Int = 1, val threadId: String) : DomainEvent
    data class MessagePosted(override val v: Int = 1, val threadId: String, val messageId: String, val role: String) : DomainEvent
    data class ContextPinned(override val v: Int = 1, val threadId: String, val contextId: String) : DomainEvent
    data class ContextUnpinned(override val v: Int = 1, val threadId: String, val contextId: String) : DomainEvent
    data class StreamStarted(override val v: Int = 1, val threadId: String) : DomainEvent
    data class StreamFinished(override val v: Int = 1, val threadId: String) : DomainEvent
    data class StreamFailed(override val v: Int = 1, val threadId: String, val reason: String) : DomainEvent
}
