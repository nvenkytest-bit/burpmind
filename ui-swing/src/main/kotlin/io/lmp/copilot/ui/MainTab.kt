package io.lmp.copilot.ui

import io.lmp.copilot.app.ChatService
import io.lmp.copilot.app.ContextService
import io.lmp.copilot.app.HealthService
import io.lmp.copilot.app.ThreadService
import io.lmp.copilot.domain.chat.ThreadId
import io.lmp.copilot.domain.chat.ThreadMeta
import io.lmp.copilot.domain.context.ContextItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/**
 * The top-level tab Burp shows. Two sub-tabs: Chat and Settings.
 *
 * Holds the currently-selected thread and routes pinned context into the ChatPanel.
 */
class MainTab(
    private val healthService: HealthService,
    private val threadService: ThreadService,
    private val chatService: ChatService,
    private val contextService: ContextService,
    private val scope: CoroutineScope,
    private val settings: CopilotSettings,
) : JPanel(BorderLayout()) {

    private val threadListModel = DefaultListModel<ThreadMeta>()
    private val threadList = JList(threadListModel).apply {
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ThreadMeta) {
                    text = value.title.ifBlank { "(untitled)" }
                }
                return c
            }
        }
        fixedCellHeight = 28
    }

    private val chatPanel = ChatPanel(
        chatService = chatService,
        contextService = contextService,
        scope = scope,
    )

    private val settingsPanel = SettingsPanel(
        healthService = healthService,
        scope = scope,
        settings = settings,
        onModelPicked = { /* nothing extra for now */ },
    )

    init {
        val sidebar = buildSidebar()
        val chatTab = buildChatTab(sidebar)
        val tabs = JTabbedPane().apply {
            addTab("Chat", chatTab)
            addTab("Settings", settingsPanel)
        }
        add(tabs, BorderLayout.CENTER)
        loadThreads()
    }

    private fun buildSidebar(): JComponentSection {
        val newButton = JButton("+ New thread").apply {
            addActionListener { promptNewThread() }
        }
        val deleteButton = JButton("Delete").apply {
            addActionListener {
                val selected = threadList.selectedValue ?: return@addActionListener
                val confirmed = JOptionPane.showConfirmDialog(
                    this@MainTab,
                    "Delete thread \"${selected.title}\"? This cannot be undone.",
                    "Delete thread",
                    JOptionPane.OK_CANCEL_OPTION,
                ) == JOptionPane.OK_OPTION
                if (confirmed) {
                    scope.launch {
                        threadService.deleteThread(selected.id)
                        loadThreads()
                    }
                }
            }
        }
        val buttons = JPanel().apply {
            layout = BorderLayout()
            add(newButton, BorderLayout.WEST)
            add(deleteButton, BorderLayout.EAST)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }

        threadList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val sel = threadList.selectedValue ?: return@addListSelectionListener
            openThread(sel.id)
        }

        val panel = JPanel(BorderLayout()).apply {
            add(buttons, BorderLayout.NORTH)
            add(JScrollPane(threadList), BorderLayout.CENTER)
            preferredSize = Dimension(220, 0)
            minimumSize = Dimension(180, 0)
        }
        return JComponentSection(panel)
    }

    private fun buildChatTab(sidebar: JComponentSection): JSplitPane {
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar.component, chatPanel)
        split.dividerLocation = 220
        split.resizeWeight = 0.0
        return split
    }

    private fun promptNewThread() {
        val name = JOptionPane.showInputDialog(
            this,
            "Thread name (e.g. target host or engagement):",
            "New thread",
            JOptionPane.PLAIN_MESSAGE,
        ) ?: return
        if (name.isBlank()) return
        scope.launch {
            val meta = threadService.createThread(
                title = name.trim(),
                providerId = settings.providerId,
                model = settings.defaultModel,
            )
            loadThreads(selectThreadId = meta.id)
        }
    }

    private fun loadThreads(selectThreadId: ThreadId? = null) {
        scope.launch {
            val threads = threadService.listThreads()
            withContext(Dispatchers.Swing) {
                threadListModel.clear()
                threads.forEach { threadListModel.addElement(it) }
                val toSelect = selectThreadId
                    ?: chatPanel.currentThreadId
                    ?: threads.firstOrNull()?.id
                if (toSelect != null) {
                    val idx = (0 until threadListModel.size).firstOrNull { i ->
                        threadListModel[i].id == toSelect
                    }
                    if (idx != null) threadList.selectedIndex = idx
                }
            }
        }
    }

    private fun openThread(id: ThreadId) {
        scope.launch {
            val view = threadService.loadView(id) ?: return@launch
            withContext(Dispatchers.Swing) { chatPanel.loadThread(view) }
        }
    }

    /**
     * "Add to chat" — append the item as a collapsible message bubble.
     * If no thread exists, create one automatically.
     */
    fun attachToChat(item: ContextItem, threadId: ThreadId?) {
        scope.launch {
            val targetId = ensureThread(threadId, fallbackTitle = item.title)
            chatService.attachToChat(targetId, item)
            reloadThreadInUi(targetId)
        }
    }

    /**
     * "Pin as session context" — top-bar chip, always included on every send.
     * If no thread exists, create one automatically.
     */
    fun pinContext(item: ContextItem, threadId: ThreadId?) {
        scope.launch {
            val targetId = ensureThread(threadId, fallbackTitle = item.title)
            contextService.pin(targetId, item)
            reloadThreadInUi(targetId)
        }
    }

    private suspend fun ensureThread(threadId: ThreadId?, fallbackTitle: String): ThreadId {
        val explicit = threadId ?: chatPanel.currentThreadId
        if (explicit != null) return explicit
        val meta = threadService.createThread(
            title = fallbackTitle.take(60).ifBlank { "Scratch" },
            providerId = settings.providerId,
            model = settings.defaultModel,
        )
        loadThreads(selectThreadId = meta.id)
        return meta.id
    }

    private suspend fun reloadThreadInUi(targetId: ThreadId) {
        val view = threadService.loadView(targetId) ?: return
        withContext(Dispatchers.Swing) {
            chatPanel.loadThread(view)
            // Bring the Chat tab forward
            SwingUtilities.invokeLater {
                val tabs = (this@MainTab.getComponent(0) as? JTabbedPane)
                tabs?.selectedIndex = 0
            }
        }
    }
}

private class JComponentSection(val component: JPanel)

/**
 * Small placeholder shown when no thread is selected. Adapts to the active Burp theme.
 *
 * We use a non-editable [javax.swing.JTextArea] with line-wrap instead of an HTML JLabel
 * because some Burp Look-and-Feels disable JLabel's HTML rendering and the markup would
 * leak through as literal text.
 */
fun placeholderPanel(text: String): JPanel = JPanel(BorderLayout()).apply {
    background = Theme.panelBg
    isOpaque = true
    border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
    val area = javax.swing.JTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = Theme.mutedFg
        background = Theme.panelBg
        font = javax.swing.UIManager.getFont("Label.font") ?: font
        border = null
        alignmentX = java.awt.Component.CENTER_ALIGNMENT
    }
    add(area, BorderLayout.CENTER)
}
