package io.lmp.copilot.ui

import io.lmp.copilot.app.ChatService
import io.lmp.copilot.app.ContextService
import io.lmp.copilot.app.HealthService
import io.lmp.copilot.app.ThreadService
import io.lmp.copilot.domain.chat.ThreadId
import io.lmp.copilot.domain.context.ContextItem
import kotlinx.coroutines.CoroutineScope
import javax.swing.JComponent

/**
 * Top-level UI entry point. Wires the main tab against application services.
 *
 * The UI module knows nothing about Burp. The adapter constructs this and passes it
 * back to Montoya via the user-interface registration.
 */
class CopilotUi(
    private val healthService: HealthService,
    private val threadService: ThreadService,
    private val chatService: ChatService,
    private val contextService: ContextService,
    private val scope: CoroutineScope,
    private val settings: CopilotSettings,
) {

    private val mainTab: MainTab = MainTab(
        healthService = healthService,
        threadService = threadService,
        chatService = chatService,
        contextService = contextService,
        scope = scope,
        settings = settings,
    )

    fun component(): JComponent = mainTab

    /**
     * "Add to chat" — append the item as a collapsible bubble in the active thread.
     * This is the default right-click action; appropriate for "let's discuss this".
     */
    fun attachToChat(item: ContextItem, threadId: ThreadId? = null) {
        mainTab.attachToChat(item, threadId)
    }

    /**
     * "Pin as session context" — add to the top-bar chip strip so the item is sent
     * with every message in the thread until unpinned. Use sparingly.
     */
    fun pinContext(item: ContextItem, threadId: ThreadId? = null) {
        mainTab.pinContext(item, threadId)
    }
}

/**
 * Mutable settings shared across the UI. Persisted by the adapter layer.
 *
 * Listeners receive a snapshot whenever any field is updated through [update];
 * direct field writes bypass listeners (used for in-flight UI edits that are
 * committed all at once).
 */
class CopilotSettings(
    ollamaBaseUrl: String = "http://localhost:11434",
    defaultModel: String = "llama3.1:8b",
    providerId: String = "ollama",
) {
    @Volatile var ollamaBaseUrl: String = ollamaBaseUrl
    @Volatile var defaultModel: String = defaultModel
    @Volatile var providerId: String = providerId

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** Atomically apply updates and notify listeners exactly once. */
    fun update(block: CopilotSettings.() -> Unit) {
        block()
        listeners.forEach { runCatching { it() } }
    }
}
