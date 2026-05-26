package io.lmp.copilot.ui

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import io.lmp.copilot.app.ChatService
import io.lmp.copilot.app.ContextService
import io.lmp.copilot.domain.chat.Message
import io.lmp.copilot.domain.chat.ThreadId
import io.lmp.copilot.domain.chat.ThreadView
import io.lmp.copilot.domain.context.ContextItem
import io.lmp.copilot.domain.llm.ChatChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * The chat conversation panel.
 *
 *  - Top: pinned-context bar (collapsible representation of attached items).
 *  - Middle: scrolling list of message bubbles. Assistant text streams in live.
 *  - Bottom: input box + send button.
 *
 * Streaming is rendered into a mutable buffer that is re-rendered as Markdown→HTML
 * on each chunk. For Phase 2 this is fine; Phase 5 will tighten the rendering loop.
 */
class ChatPanel(
    private val chatService: ChatService,
    private val contextService: ContextService,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()) {

    var currentThreadId: ThreadId? = null
        private set

    private val pinnedBar = PinnedContextBar(
        scope = scope,
        onUnpin = { item -> unpin(item) },
    )

    private val messagesContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Theme.panelBg
        isOpaque = true
    }
    private val messagesScroll = JScrollPane(messagesContainer).apply {
        verticalScrollBar.unitIncrement = 16
        border = BorderFactory.createEmptyBorder()
        viewport.background = Theme.panelBg
    }

    private val inputArea = JTextArea(3, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        font = font.deriveFont(font.size + 1f)
    }
    private val sendButton = JButton("Send (⌘↩)")
    private val cancelButton = JButton("Cancel").apply { isEnabled = false }
    private val headerLabel = JLabel(" no thread selected").apply {
        border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
    }

    private var streamJob: Job? = null
    private var streamingBubble: MessageBubble? = null

    private val markdownParser: Parser
    private val markdownRenderer: HtmlRenderer

    init {
        val options = MutableDataSet().apply {
            set(
                Parser.EXTENSIONS,
                listOf(
                    TablesExtension.create(),
                    AutolinkExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListExtension.create(),
                ),
            )
            // Make GFM tables more permissive: don't require strict alignment.
            set(TablesExtension.COLUMN_SPANS, false)
            set(TablesExtension.APPEND_MISSING_COLUMNS, true)
            set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
            set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, false)
            // Render LF as <br> so single-newlines inside model output break visually.
            set(HtmlRenderer.SOFT_BREAK, "<br/>\n")
        }
        markdownParser = Parser.builder(options).build()
        markdownRenderer = HtmlRenderer.builder(options).build()

        add(buildHeader(), BorderLayout.NORTH)
        add(messagesScroll, BorderLayout.CENTER)
        add(buildInputArea(), BorderLayout.SOUTH)

        showEmptyState()
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.border)
        background = Theme.panelBg
        isOpaque = true
        add(headerLabel, BorderLayout.WEST)
        add(pinnedBar, BorderLayout.CENTER)
    }

    private fun buildInputArea(): JPanel {
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(cancelButton)
            add(Box.createHorizontalStrut(6))
            add(sendButton)
        }
        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JScrollPane(inputArea), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        sendButton.addActionListener { sendCurrent() }
        cancelButton.addActionListener { streamJob?.cancel() }
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val isModifier = e.isMetaDown || e.isControlDown
                if (isModifier && e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    sendCurrent()
                }
            }
        })
        return panel
    }

    fun loadThread(view: ThreadView) {
        SwingUtilities.invokeLater {
            currentThreadId = view.meta.id
            headerLabel.text = " ${view.meta.title}   ·   ${view.meta.model}"
            pinnedBar.setItems(view.pinnedContext)
            renderMessages(view.messages)
            inputArea.isEnabled = true
            sendButton.isEnabled = true
        }
    }

    fun appendPinned(item: ContextItem) {
        pinnedBar.addItem(item)
    }

    private fun unpin(item: ContextItem) {
        val tid = currentThreadId ?: return
        scope.launch {
            contextService.unpin(tid, item.id)
            val current = contextService.list(tid)
            withContext(Dispatchers.Swing) { pinnedBar.setItems(current) }
        }
    }

    private fun showEmptyState() {
        messagesContainer.removeAll()
        messagesContainer.add(
            placeholderPanel(
                "Create or select a thread on the left to start chatting. " +
                    "Right-click a request in Repeater/Proxy and choose " +
                    "\"Add to BurpMind chat\" to attach it as context.",
            ),
        )
        messagesContainer.revalidate()
        messagesContainer.repaint()
        inputArea.isEnabled = false
        sendButton.isEnabled = false
    }

    private fun renderMessages(messages: List<Message>) {
        messagesContainer.removeAll()
        if (messages.isEmpty()) {
            messagesContainer.add(
                placeholderPanel(
                    "Empty thread. Pin context with right-click → " +
                        "\"Add to BurpMind chat\", then ask away.",
                ),
            )
        } else {
            messages.forEach { messagesContainer.add(buildBubble(it)) }
        }
        messagesContainer.add(Box.createVerticalGlue())
        messagesContainer.revalidate()
        messagesContainer.repaint()
        scrollToBottom()
    }

    private fun buildBubble(message: Message): javax.swing.JComponent {
        val attachment = message.attachment
        val number = message.attachmentNumber
        if (attachment != null && number != null) {
            return AttachmentBubble(number, attachment)
        }
        val bubble = MessageBubble(message.role, renderMarkdown(message.content))
        val thinking = message.thinking
        if (!thinking.isNullOrBlank()) {
            // Historical messages: show the thinking section collapsed by default.
            bubble.setThinking(thinking, expanded = false)
        }
        return bubble
    }

    private fun sendCurrent() {
        val tid = currentThreadId ?: return
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        if (streamJob?.isActive == true) return // ignore double-fire while streaming

        inputArea.text = ""
        val userBubble = MessageBubble(Message.Role.User, escapeHtml(text))
        appendBubble(userBubble)

        val assistantBubble = MessageBubble(Message.Role.Assistant, "<i>…</i>")
        assistantBubble.setStatus("⏳ waiting…")
        appendBubble(assistantBubble)
        streamingBubble = assistantBubble
        cancelButton.isEnabled = true
        sendButton.isEnabled = false

        streamJob = scope.launch {
            val contentBuffer = StringBuilder()
            val thinkingBuffer = StringBuilder()
            val pinned = contextService.list(tid)
            val startNanos = System.nanoTime()
            var firstThinkingAt = 0L
            var firstContentAt = 0L
            var thinkingCollapsedOnContent = false

            // Throttle UI repaints. JEditorPane rebuilds its entire DOM on every
            // setText() call, so re-rendering on every NDJSON chunk causes flicker
            // and pegs the EDT. We batch by re-rendering at most every CONTENT_RENDER_MS
            // (for the markdown bubble) and THINKING_RENDER_MS (for the reasoning panel).
            val contentRenderMs = CONTENT_RENDER_MS
            val thinkingRenderMs = THINKING_RENDER_MS
            var lastContentRender = 0L
            var lastThinkingRender = 0L

            // Live ticker so the user sees the elapsed time even when no tokens arrive
            // for a while (model is loading, slow GPU, etc.).
            val ticker = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(500)
                    withContext(Dispatchers.Swing) {
                        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                        val status = when {
                            firstContentAt > 0L -> "✍️ writing · ${formatMs(elapsedMs)}"
                            firstThinkingAt > 0L -> "💭 thinking · ${formatMs(elapsedMs)}"
                            else -> "⏳ waiting · ${formatMs(elapsedMs)}"
                        }
                        assistantBubble.setStatus(status)
                    }
                }
            }

            val flow = chatService.sendMessage(tid, text, pinned)
            flow.onEach { chunk ->
                when (chunk) {
                    is ChatChunk.ThinkingDelta -> {
                        if (firstThinkingAt == 0L) firstThinkingAt = System.nanoTime()
                        thinkingBuffer.append(chunk.text)
                        val now = System.currentTimeMillis()
                        if (now - lastThinkingRender >= thinkingRenderMs) {
                            lastThinkingRender = now
                            val snapshot = thinkingBuffer.toString()
                            val wasAtBottom = isScrolledToBottom()
                            withContext(Dispatchers.Swing) {
                                assistantBubble.setThinking(snapshot, expanded = true)
                                if (wasAtBottom) scrollToBottom()
                            }
                        }
                    }
                    is ChatChunk.TextDelta -> {
                        if (firstContentAt == 0L) firstContentAt = System.nanoTime()
                        contentBuffer.append(chunk.text)
                        val now = System.currentTimeMillis()
                        if (now - lastContentRender >= contentRenderMs) {
                            lastContentRender = now
                            val html = renderMarkdown(contentBuffer.toString())
                            val wasAtBottom = isScrolledToBottom()
                            withContext(Dispatchers.Swing) {
                                assistantBubble.setBodyHtml(html)
                                if (!thinkingCollapsedOnContent && thinkingBuffer.isNotEmpty()) {
                                    assistantBubble.collapseThinking()
                                    thinkingCollapsedOnContent = true
                                }
                                if (wasAtBottom) scrollToBottom()
                            }
                        }
                    }
                    is ChatChunk.Done -> {
                        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                        val thinkingMs = if (firstContentAt > 0L && firstThinkingAt > 0L) {
                            (firstContentAt - firstThinkingAt) / 1_000_000
                        } else null
                        val finalHtml = renderMarkdown(contentBuffer.toString())
                        val finalThinking = thinkingBuffer.toString()
                        withContext(Dispatchers.Swing) {
                            // Final flush — render any tokens that arrived after the
                            // last throttled tick so the persisted content matches the UI.
                            assistantBubble.setBodyHtml(finalHtml)
                            if (finalThinking.isNotEmpty()) {
                                assistantBubble.setThinking(
                                    finalThinking,
                                    expanded = !thinkingCollapsedOnContent,
                                )
                            }
                            assistantBubble.setStatus(
                                buildString {
                                    append("✓ ")
                                    append(formatMs(elapsedMs))
                                    if (thinkingMs != null) append(" · 💭 ").append(formatMs(thinkingMs))
                                    append(" · ").append(contentBuffer.length).append(" chars")
                                },
                            )
                            cancelButton.isEnabled = false
                            sendButton.isEnabled = true
                            scrollToBottom()
                        }
                    }
                    is ChatChunk.Error -> {
                        withContext(Dispatchers.Swing) {
                            val color = toHex(Theme.errorFg)
                            assistantBubble.setBodyHtml(
                                "<span style='color:$color;'>Error: ${escapeHtml(chunk.message)}</span>",
                            )
                            assistantBubble.setStatus("✗ error")
                            cancelButton.isEnabled = false
                            sendButton.isEnabled = true
                        }
                    }
                }
            }.collect()
            ticker.cancel()
        }
        streamJob?.invokeOnCompletion {
            SwingUtilities.invokeLater {
                cancelButton.isEnabled = false
                sendButton.isEnabled = true
            }
        }
    }

    private fun formatMs(ms: Long): String =
        if (ms < 1_000) "${ms}ms"
        else if (ms < 60_000) String.format("%.1fs", ms / 1000.0)
        else String.format("%dm %ds", ms / 60_000, (ms % 60_000) / 1000)

    private companion object {
        /** Minimum gap between full markdown re-renders. ~12 FPS. */
        const val CONTENT_RENDER_MS = 80L

        /**
         * Thinking is plain monospace text, much cheaper to repaint than markdown,
         * so we can refresh more often without flicker.
         */
        const val THINKING_RENDER_MS = 120L
    }

    private fun appendBubble(bubble: javax.swing.JComponent) {
        SwingUtilities.invokeLater {
            // Strip trailing vertical glue (Box.Filler), append bubble, re-add glue.
            val components = messagesContainer.components.toList()
            val trailingGlue = components.lastOrNull { it is javax.swing.Box.Filler }
            if (trailingGlue != null) messagesContainer.remove(trailingGlue)
            messagesContainer.add(bubble)
            messagesContainer.add(Box.createVerticalGlue())
            messagesContainer.revalidate()
            messagesContainer.repaint()
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = messagesScroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    /**
     * Returns whether the user is currently parked at (or very close to) the bottom of
     * the messages list. We use this to implement "sticky" auto-scroll: keep following
     * the stream if the user has not scrolled away, but leave them alone if they have.
     */
    private fun isScrolledToBottom(): Boolean {
        val bar = messagesScroll.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - 40
    }

    private fun renderMarkdown(text: String): String {
        if (text.isEmpty()) return ""
        return try {
            val doc = markdownParser.parse(text)
            val raw = markdownRenderer.render(doc)
            postProcessForHtmlEditorKit(raw)
        } catch (_: Exception) {
            "<pre>${escapeHtml(text)}</pre>"
        }
    }

    /**
     * Swing's HTMLEditorKit is essentially an HTML 3.2 renderer: modern CSS like
     * `border-collapse` is ignored, but the legacy `border`, `cellpadding`, `cellspacing`
     * and `width` attributes ARE honoured. flexmark emits plain `<table>` tags, so we
     * splice the legacy attributes in here. Same trick lets `<pre>` blocks render with
     * a subtle background.
     */
    private fun postProcessForHtmlEditorKit(html: String): String {
        return html
            .replace("<table>", "<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\" width=\"100%\">")
            .replace("<pre><code", "<pre><code")
    }
}

/**
 * A single message bubble.
 *
 * Layout:
 *   ┌──────────────────────────────────────────────────┐
 *   │ ▌ Assistant                     💭 thinking 2.4s │  ← header (role + status)
 *   │   ─────────────────────────────                  │
 *   │   ▸ 💭 Thinking (1247 chars)                     │  ← collapsible (optional)
 *   │   ─────────────────────────────                  │
 *   │   [rendered markdown content]                    │  ← main editor
 *   └──────────────────────────────────────────────────┘
 *
 * The thinking section is created lazily — only models that emit reasoning produce one.
 * It auto-expands while thinking is streaming, then auto-collapses once content starts
 * arriving (you can click to expand again).
 */
private class MessageBubble(role: Message.Role, initialHtml: String) : JPanel(BorderLayout()) {

    private val bg: Color = when (role) {
        Message.Role.User -> Theme.userBubbleBg
        Message.Role.Assistant -> Theme.assistantBubbleBg
        Message.Role.System -> Theme.systemBubbleBg
    }

    private val editor = WrappingEditorPane().apply {
        editorKit = HTMLEditorKit()
        contentType = "text/html"
        isEditable = false
        isOpaque = true
    }

    private val statusLabel = JLabel("").apply {
        font = font.deriveFont(font.size - 1f)
        foreground = Theme.mutedFg
        border = BorderFactory.createEmptyBorder(4, 8, 0, 12)
    }

    /** Stacks thinking section (when present) above the editor. */
    private val center = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = bg
        isOpaque = true
    }

    private var thinkingSection: ThinkingSection? = null

    init {
        val stripe = when (role) {
            Message.Role.User -> Theme.userStripe
            Message.Role.Assistant -> Theme.assistantStripe
            Message.Role.System -> Theme.systemStripe
        }
        background = bg
        editor.background = bg
        editor.foreground = Theme.fg
        editor.border = BorderFactory.createEmptyBorder(4, 12, 8, 12)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(6, 8, 6, 8),
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, stripe),
                BorderFactory.createLineBorder(Theme.border),
            ),
        )
        val roleLabel = JLabel(
            when (role) {
                Message.Role.User -> " You"
                Message.Role.Assistant -> " Assistant"
                Message.Role.System -> " System"
            },
        ).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            foreground = Theme.fg
            border = BorderFactory.createEmptyBorder(4, 12, 0, 12)
            background = bg
            isOpaque = true
        }
        val header = JPanel(BorderLayout()).apply {
            background = bg
            isOpaque = true
            add(roleLabel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        // Wrap the editor in a holder so we can splice thinking above it.
        center.add(editor)

        add(header, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        setBodyHtml(initialHtml)
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = true
    }

    /** Allow the bubble to grow horizontally to fill the chat viewport, but not vertically. */
    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    fun setBodyHtml(html: String) {
        val fgHex = toHex(Theme.fg)
        val borderHex = toHex(Theme.border)
        editor.text = """
            <html><head><style>
              body { font-family: sans-serif; font-size: 11pt; color: $fgHex;
                     word-wrap: break-word; overflow-wrap: anywhere; }
              p, li { margin: 4px 0; }
              pre, code { font-family: monospace; white-space: pre-wrap;
                          word-break: break-all; }
              pre { padding: 6px 8px; }
              ul, ol { margin: 4px 0 4px 20px; padding: 0; }
              a { color: $fgHex; text-decoration: underline; }
              /* Tables: Swing's HTMLEditorKit doesn't support `border-collapse` or
                 modern CSS, but it honours the legacy `border`, `cellpadding`,
                 `cellspacing`, and basic td/th styling. We render the markup with
                 those attributes (see renderMarkdown) and style the cells here. */
              table { margin: 6px 0; }
              th, td { border: 1px solid $borderHex; padding: 4px 8px;
                       vertical-align: top; }
              th { font-weight: bold; }
            </style></head>
            <body>$html</body></html>
        """.trimIndent()
        editor.caretPosition = 0
    }

    /** Set the small right-aligned status text (e.g. "💭 thinking 2.4s"). */
    fun setStatus(text: String) {
        statusLabel.text = text
    }

    /**
     * Update the thinking section's content. Lazily creates the section the first time
     * non-empty thinking text arrives. Pass [expanded] = true to show the body open.
     */
    fun setThinking(text: String, expanded: Boolean) {
        if (text.isEmpty() && thinkingSection == null) return
        val section = thinkingSection ?: ThinkingSection(bg).also {
            thinkingSection = it
            // Insert at the top of the center stack, above the editor.
            center.add(it, 0)
            center.revalidate()
        }
        section.setText(text, expanded)
    }

    fun collapseThinking() {
        thinkingSection?.setExpanded(false)
    }
}

/**
 * Collapsible "Thinking" panel that renders reasoning tokens from models that expose them.
 * Lives inside [MessageBubble.center] and shares its background color so visually it's
 * part of the bubble rather than a separate card.
 */
private class ThinkingSection(parentBg: Color) : JPanel(BorderLayout()) {

    private val area = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11)
        background = Theme.textBg
        foreground = Theme.mutedFg
    }
    private val scroll = JScrollPane(area).apply {
        preferredSize = Dimension(0, 180)
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.border)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        isVisible = false
    }
    private val toggle = JButton("▸").apply {
        font = font.deriveFont(font.size + 1f)
        foreground = Theme.mutedFg
        isBorderPainted = false
        isContentAreaFilled = false
        margin = java.awt.Insets(0, 6, 0, 6)
    }
    private val titleLabel = JLabel("💭 Thinking").apply {
        foreground = Theme.mutedFg
        border = BorderFactory.createEmptyBorder(0, 2, 0, 8)
    }
    private val charsLabel = JLabel("").apply {
        foreground = Theme.mutedFg
        font = font.deriveFont(font.size - 1f)
    }

    init {
        background = parentBg
        isOpaque = true
        border = BorderFactory.createEmptyBorder(2, 8, 6, 8)

        val header = JPanel(BorderLayout()).apply {
            background = parentBg
            isOpaque = true
            val left = JPanel(BorderLayout()).apply {
                background = parentBg
                isOpaque = true
                add(toggle, BorderLayout.WEST)
                add(titleLabel, BorderLayout.CENTER)
            }
            add(left, BorderLayout.CENTER)
            add(charsLabel, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        val toggleAction = java.awt.event.ActionListener { setExpanded(!scroll.isVisible) }
        toggle.addActionListener(toggleAction)
        titleLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                setExpanded(!scroll.isVisible)
            }
        })
    }

    fun setText(text: String, expanded: Boolean) {
        area.text = text
        charsLabel.text = " (${text.length} chars)"
        if (expanded != scroll.isVisible) setExpanded(expanded)
        // While thinking is streaming, keep the area scrolled to the latest tokens.
        if (expanded) {
            area.caretPosition = area.document.length
        }
        revalidate()
        repaint()
    }

    fun setExpanded(expanded: Boolean) {
        scroll.isVisible = expanded
        toggle.text = if (expanded) "▾" else "▸"
        revalidate()
        repaint()
    }
}

/**
 * A [JEditorPane] subclass that re-layouts its HTML against the parent's current width
 * on every preferred-size query. This is the standard Swing recipe for making HTML
 * text actually wrap — without it, JEditorPane reports a preferred width derived from
 * the longest unbreakable line and BoxLayout happily makes the bubble that wide.
 */
private class WrappingEditorPane : JEditorPane() {

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getPreferredSize(): Dimension {
        val target = parent?.width ?: 0
        if (target > 50) {
            // Force the HTML view to lay out at the parent's available width.
            // Height is recomputed from the new wrap; passing 0 lets Swing measure.
            setSize(Dimension(target, 0))
        }
        return super.getPreferredSize()
    }

    /** Same as above: a bubble in BoxLayout will respect this too. */
    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }
}

private fun toHex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\n", "<br/>")
