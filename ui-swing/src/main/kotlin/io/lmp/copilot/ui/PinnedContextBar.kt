package io.lmp.copilot.ui

import io.lmp.copilot.domain.context.ContextItem
import kotlinx.coroutines.CoroutineScope
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Header strip showing the items currently pinned as context. Each chip has a × to unpin.
 *
 * Lives in the chat panel header. Wired by [MainTab] / [ChatPanel] when items change.
 */
class PinnedContextBar(
    private val scope: CoroutineScope,
    private val onUnpin: (ContextItem) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)) {

    private var items: List<ContextItem> = emptyList()

    init {
        background = Theme.panelBg
        isOpaque = true
        preferredSize = Dimension(0, 32)
        renderEmpty()
    }

    fun setItems(newItems: List<ContextItem>) {
        SwingUtilities.invokeLater {
            items = newItems
            removeAll()
            if (newItems.isEmpty()) renderEmpty() else newItems.forEach { addChip(it) }
            revalidate()
            repaint()
        }
    }

    fun addItem(item: ContextItem) {
        setItems(items + item)
    }

    private fun renderEmpty() {
        add(JLabel("no pinned context").apply { foreground = Theme.mutedFg })
    }

    private fun addChip(item: ContextItem) {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = Theme.chipBg
            isOpaque = true
            border = BorderFactory.createLineBorder(Theme.chipBorder)
        }
        chip.add(
            JLabel(kindIcon(item) + " " + truncate(item.title, 40)).apply {
                foreground = Theme.fg
            },
        )
        chip.add(JButton("×").apply {
            font = font.deriveFont(font.size + 2f)
            isBorderPainted = false
            isContentAreaFilled = false
            foreground = Theme.fg
            margin = java.awt.Insets(0, 4, 0, 4)
            toolTipText = "Unpin"
            addActionListener { onUnpin(item) }
        })
        add(chip)
    }

    private fun kindIcon(item: ContextItem): String = when (item.kind) {
        ContextItem.Kind.HttpRequest -> "→"
        ContextItem.Kind.HttpResponse -> "←"
        ContextItem.Kind.Note -> "✎"
        ContextItem.Kind.File -> "📄"
        ContextItem.Kind.SiteMapSummary -> "≡"
        ContextItem.Kind.Other -> "•"
    }

    private fun truncate(s: String, n: Int): String = if (s.length <= n) s else s.take(n - 1) + "…"
}
