package io.lmp.copilot.ui

import io.lmp.copilot.domain.context.ContextItem
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * Collapsible card rendered for any message that carries a [ContextItem] attachment.
 *
 * The header is always visible: 📎 [N] METHOD URL. Click to expand and see the
 * full request/response body. Uses theme-aware colors so it fits Burp's dark or
 * light L&F without overriding anything globally.
 */
class AttachmentBubble(
    private val number: Int,
    private val item: ContextItem,
) : JPanel(BorderLayout()) {

    private val bodyArea = JTextArea(item.body).apply {
        isEditable = false
        // Wrap long lines (URLs, headers) instead of forcing a horizontal scrollbar.
        // wrapStyleWord = false because raw HTTP can have unbreakable tokens that
        // must wrap mid-string.
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = Theme.textBg
        foreground = Theme.fg
    }

    private val bodyScroll = JScrollPane(bodyArea).apply {
        preferredSize = Dimension(0, 280)
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.border)
        isVisible = false
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val toggleButton = JButton("▸").apply {
        font = font.deriveFont(font.size + 2f)
        foreground = Theme.fg
        isBorderPainted = false
        isContentAreaFilled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = java.awt.Insets(0, 6, 0, 6)
    }

    private val titleLabel = JLabel().apply {
        text = buildHeaderText()
        toolTipText = item.title
        font = font.deriveFont(font.style or Font.BOLD)
        foreground = Theme.fg
        border = BorderFactory.createEmptyBorder(0, 2, 0, 8)
    }

    init {
        val bg = tintBg()
        background = bg
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(6, 8, 6, 8),
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 4, 0, 0, Theme.accent()),
                BorderFactory.createLineBorder(Theme.border),
            ),
        )

        val header = JPanel(BorderLayout()).apply {
            background = bg
            isOpaque = true
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            val left = JPanel(BorderLayout()).apply {
                background = bg
                isOpaque = true
                add(toggleButton, BorderLayout.WEST)
                add(titleLabel, BorderLayout.CENTER)
            }
            add(left, BorderLayout.CENTER)
            add(JLabel("Attachment", SwingConstants.RIGHT).apply {
                foreground = Theme.mutedFg
                font = font.deriveFont(font.size - 1f)
            }, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(bodyScroll, BorderLayout.CENTER)

        toggleButton.addActionListener { setExpanded(!bodyScroll.isVisible) }
        titleLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                setExpanded(!bodyScroll.isVisible)
            }
        })

        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** Fill horizontal width; don't stretch vertically. */
    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    private fun buildHeaderText(): String {
        // Plain text — some Burp L&Fs disable JLabel HTML rendering. Bold styling
        // is applied via the label's font instead. Long URLs are truncated visually;
        // the full string is available in the tooltip.
        val truncated = if (item.title.length > MAX_HEADER_CHARS) {
            item.title.take(MAX_HEADER_CHARS - 1) + "…"
        } else {
            item.title
        }
        return "[$number]  $truncated"
    }

    private fun setExpanded(expanded: Boolean) {
        bodyScroll.isVisible = expanded
        toggleButton.text = if (expanded) "▾" else "▸"
        revalidate()
        repaint()
    }

    private fun tintBg(): Color = Theme.userBubbleBg

    companion object {
        private const val MAX_HEADER_CHARS = 100
    }
}
