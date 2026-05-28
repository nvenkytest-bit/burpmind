package io.lmp.copilot.burp

import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import burp.api.montoya.ui.contextmenu.WebSocketContextMenuEvent
import burp.api.montoya.ui.contextmenu.WebSocketMessage
import burp.api.montoya.websocket.Direction
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

    /**
     * Right-click menu inside Burp's WebSocket views (Proxy → WebSockets history,
     * the WebSocket message editor, etc.). Montoya routes WS context-menu invocations
     * to this overload instead of [provideMenuItems] above.
     */
    override fun provideMenuItems(event: WebSocketContextMenuEvent): MutableList<Component> {
        val items = mutableListOf<Component>()
        val selected = event.selectedWebSocketMessages()
        val editor = event.messageEditorWebSocket()

        val single = when {
            selected.size == 1 -> selected.first()
            selected.isEmpty() && editor.isPresent -> editor.get().webSocketMessage()
            else -> null
        }

        if (single != null) {
            val parent = JMenu("BurpMind")

            parent.add(JMenuItem("Add to chat").apply {
                addActionListener {
                    val item = burpRequestSource.captureWebSocketAsItem(single, describeWs(single))
                    onAddToChat(item)
                }
            })

            parent.add(JMenuItem("Pin as session context").apply {
                addActionListener {
                    val item = burpRequestSource.captureWebSocketAsItem(single, describeWs(single))
                    onPinRequest(item)
                }
            })

            parent.add(JMenuItem("Add to chat AND pin").apply {
                addActionListener {
                    val item = burpRequestSource.captureWebSocketAsItem(single, describeWs(single))
                    onAddToChat(item)
                    onPinRequest(item)
                }
            })

            items += parent

            items += JMenuItem("Add WebSocket message to BurpMind chat").apply {
                addActionListener {
                    val item = burpRequestSource.captureWebSocketAsItem(single, describeWs(single))
                    onAddToChat(item)
                }
            }
        }

        if (selected.size > 1) {
            // Multi-select: WS messages form a stream, so the natural default is one
            // bubble containing the full conversation. We also offer "separately" for
            // users who prefer per-message bubbles (matches the HTTP multi-select shape).
            val messages = selected.toList()
            items += JMenuItem("Add ${messages.size} WebSocket messages to BurpMind chat (as conversation)").apply {
                addActionListener {
                    val item = burpRequestSource.captureWebSocketConversationAsItem(
                        messages,
                        describeBatch(messages),
                    )
                    onAddToChat(item)
                }
            }
            items += JMenuItem("Add ${messages.size} WebSocket messages to BurpMind chat (separately)").apply {
                addActionListener {
                    messages.forEach { m ->
                        val item = burpRequestSource.captureWebSocketAsItem(m, describeWs(m))
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

    private fun describeWs(msg: WebSocketMessage): String {
        val arrow = if (msg.direction() == Direction.CLIENT_TO_SERVER) "→" else "←"
        val upgrade = msg.upgradeRequest()
        val url = runCatching { upgrade.url() }.getOrNull()
            ?: runCatching { upgrade.path() }.getOrNull()
            ?: "(?)"
        val bytes = msg.payload().length()
        return "WS $arrow $url ($bytes B)"
    }

    private fun describeBatch(messages: List<WebSocketMessage>): String {
        val first = messages.first()
        val url = runCatching { first.upgradeRequest().url() }.getOrNull()
            ?: runCatching { first.upgradeRequest().path() }.getOrNull()
            ?: "(?)"
        return "WS conversation @ $url (${messages.size} msgs)"
    }
}
