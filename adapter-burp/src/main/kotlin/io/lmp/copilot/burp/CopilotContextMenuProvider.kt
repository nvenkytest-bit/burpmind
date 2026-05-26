package io.lmp.copilot.burp

import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import io.lmp.copilot.domain.context.ContextItem
import java.awt.Component
import javax.swing.JMenu
import javax.swing.JMenuItem

/**
 * Adds a right-click menu in Repeater, Proxy, HTTP history, Intruder, Logger.
 *
 * Two attach modes:
 *  • "Add to chat" (default, common) — appears as a collapsible message in the active thread.
 *    Numbered `[N]` so it can be referenced naturally in conversation.
 *  • "Pin as session context" (advanced, rare) — top-bar chip; sent with every message.
 *  • "Add to chat AND pin" — both at once for the rare case where you want both behaviors.
 *
 * For multiple selected items the "Add to chat" form is offered as a batch action.
 */
class CopilotContextMenuProvider(
    private val burpRequestSource: BurpRequestSource,
    private val onAddToChat: (ContextItem) -> Unit,
    private val onPinRequest: (ContextItem) -> Unit,
) : ContextMenuItemsProvider {

    override fun provideMenuItems(event: ContextMenuEvent): MutableList<Component> {
        val items = mutableListOf<Component>()
        val pairs = event.selectedRequestResponses()
        val messageRR = event.messageEditorRequestResponse()

        val singleRR = when {
            pairs.isNotEmpty() -> pairs.first()
            messageRR.isPresent -> messageRR.get().requestResponse()
            else -> null
        }

        if (singleRR != null) {
            val parent = JMenu("BurpMind")

            parent.add(JMenuItem("Add to chat").apply {
                addActionListener {
                    val item = burpRequestSource.captureAsItem(singleRR, describe(singleRR))
                    onAddToChat(item)
                }
            })

            parent.add(JMenuItem("Pin as session context").apply {
                addActionListener {
                    val item = burpRequestSource.captureAsItem(singleRR, describe(singleRR))
                    onPinRequest(item)
                }
            })

            parent.add(JMenuItem("Add to chat AND pin").apply {
                addActionListener {
                    val item = burpRequestSource.captureAsItem(singleRR, describe(singleRR))
                    onAddToChat(item)
                    onPinRequest(item)
                }
            })

            items += parent

            // Also expose the most common action at the top level so it's one click.
            items += JMenuItem("Add to BurpMind chat").apply {
                addActionListener {
                    val item = burpRequestSource.captureAsItem(singleRR, describe(singleRR))
                    onAddToChat(item)
                }
            }
        }

        if (pairs.size > 1) {
            items += JMenuItem("Add ${pairs.size} requests to BurpMind chat").apply {
                addActionListener {
                    pairs.forEach { rr ->
                        val item = burpRequestSource.captureAsItem(rr, describe(rr))
                        onAddToChat(item)
                    }
                }
            }
        }

        return items
    }

    private fun describe(reqRes: burp.api.montoya.http.message.HttpRequestResponse): String {
        val req = reqRes.request()
        val method = req.method() ?: "?"
        val url = runCatching { req.url() }.getOrNull() ?: req.path()
        return "$method $url"
    }
}
