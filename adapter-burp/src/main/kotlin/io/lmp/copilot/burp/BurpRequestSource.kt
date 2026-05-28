package io.lmp.copilot.burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.ui.contextmenu.WebSocketMessage
import burp.api.montoya.websocket.Direction
import io.lmp.copilot.domain.context.ContextItem
import io.lmp.copilot.domain.context.ContextSource
import io.lmp.copilot.domain.context.SourceId
import io.lmp.copilot.domain.context.SourceRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns Burp [HttpRequestResponse] handles into normalized [ContextItem]s.
 *
 * Burp's request handles are short-lived references back into the audit tooling.
 * We grab the relevant byte/text representation up front and cache the resolved
 * item by ref string so subsequent lookups don't need Burp internals.
 */
class BurpRequestSource(
    @Suppress("UNUSED_PARAMETER") api: MontoyaApi,
) : ContextSource {

    override val id: SourceId = SourceId("burp.request")
    private val cache = ConcurrentHashMap<String, ContextItem>()

    /** Capture a request/response pair right now, returning a stable [SourceRef]. */
    fun capture(reqRes: HttpRequestResponse, label: String?): SourceRef {
        val item = toContextItem(reqRes, label)
        cache[item.source.ref] = item
        return item.source
    }

    /** Capture just a request (no response). */
    fun captureRequest(request: HttpRequest, label: String?): SourceRef {
        val item = toContextItem(request, response = null, label = label)
        cache[item.source.ref] = item
        return item.source
    }

    /** Convenience: capture and immediately return the [ContextItem]. */
    fun captureAsItem(reqRes: HttpRequestResponse, label: String?): ContextItem {
        val item = toContextItem(reqRes, label)
        cache[item.source.ref] = item
        return item
    }

    fun captureRequestAsItem(request: HttpRequest, label: String?): ContextItem {
        val item = toContextItem(request, response = null, label = label)
        cache[item.source.ref] = item
        return item
    }

    /** Capture a single WebSocket message. */
    fun captureWebSocketAsItem(message: WebSocketMessage, label: String?): ContextItem {
        val item = toContextItem(message, label)
        cache[item.source.ref] = item
        return item
    }

    /**
     * Capture a contiguous set of WebSocket messages as ONE conversation item.
     * Useful when the user selects multiple messages in WebSocket history — the
     * messages form a stream and read better as a single bubble than as N.
     */
    fun captureWebSocketConversationAsItem(messages: List<WebSocketMessage>, label: String?): ContextItem {
        val item = toContextItem(messages, label)
        cache[item.source.ref] = item
        return item
    }

    override suspend fun resolve(ref: SourceRef): ContextItem {
        require(ref.sourceId == id) { "wrong source: ${ref.sourceId}" }
        return cache[ref.ref] ?: error("No cached request for ref=${ref.ref}")
    }

    private fun toContextItem(reqRes: HttpRequestResponse, label: String?): ContextItem {
        val req = reqRes.request()
        val res: HttpResponse? = reqRes.response()
        return toContextItem(req, res, label)
    }

    private fun toContextItem(req: HttpRequest, response: HttpResponse?, label: String?): ContextItem {
        val refId = UUID.randomUUID().toString()
        val sourceRef = SourceRef(sourceId = id, ref = refId, label = label)
        val method = req.method() ?: "?"
        val url = runCatching { req.url() }.getOrNull() ?: req.path()
        val title = "$method $url"
        val body = buildString {
            append("HTTP request:\n```\n")
            append(req.toString().take(MAX_BODY_CHARS))
            append("\n```\n")
            if (response != null) {
                append("\nHTTP response (status ${response.statusCode()}):\n```\n")
                append(response.toString().take(MAX_BODY_CHARS))
                append("\n```\n")
            }
        }
        return ContextItem(
            id = refId,
            source = sourceRef,
            kind = ContextItem.Kind.HttpRequest,
            title = title,
            body = body,
            metadata = buildMap {
                put("method", method)
                put("url", url ?: "")
                if (response != null) put("status", response.statusCode().toString())
            },
        )
    }

    private fun toContextItem(msg: WebSocketMessage, label: String?): ContextItem {
        val refId = UUID.randomUUID().toString()
        val sourceRef = SourceRef(sourceId = id, ref = refId, label = label)
        val arrow = arrowFor(msg.direction())
        val dirLabel = directionLabel(msg.direction())
        val payload = msg.payload()
        val payloadStr = payload.toString()
        val byteLength = payload.length()
        val url = wsEndpointUrl(msg)
        val title = "WS $arrow $url ($byteLength B)"
        val body = buildString {
            append("WebSocket message ($dirLabel) — $byteLength bytes\n")
            append("Endpoint: $url\n")
            append("```\n")
            append(payloadStr.take(MAX_BODY_CHARS))
            if (payloadStr.length > MAX_BODY_CHARS) append("\n… [truncated, ${payloadStr.length - MAX_BODY_CHARS} more chars]")
            append("\n```\n")
        }
        return ContextItem(
            id = refId,
            source = sourceRef,
            kind = ContextItem.Kind.WebSocketMessage,
            title = title,
            body = body,
            metadata = mapOf(
                "direction" to msg.direction().name,
                "url" to url,
                "bytes" to byteLength.toString(),
            ),
        )
    }

    private fun toContextItem(messages: List<WebSocketMessage>, label: String?): ContextItem {
        require(messages.isNotEmpty()) { "no messages" }
        val refId = UUID.randomUUID().toString()
        val sourceRef = SourceRef(sourceId = id, ref = refId, label = label)
        val url = wsEndpointUrl(messages.first())
        val title = "WS conversation @ $url (${messages.size} msgs)"
        val body = buildString {
            append("WebSocket conversation — ${messages.size} messages, endpoint: $url\n")
            append("```\n")
            var remaining = MAX_BODY_CHARS
            for ((i, m) in messages.withIndex()) {
                if (remaining <= 0) {
                    append("… [${messages.size - i} more messages truncated]\n")
                    break
                }
                val arrow = arrowFor(m.direction())
                val payload = m.payload().toString()
                val line = "$arrow $payload\n"
                if (line.length > remaining) {
                    append(line.take(remaining))
                    append(" …\n")
                    remaining = 0
                } else {
                    append(line)
                    remaining -= line.length
                }
            }
            append("```\n")
        }
        return ContextItem(
            id = refId,
            source = sourceRef,
            kind = ContextItem.Kind.WebSocketMessage,
            title = title,
            body = body,
            metadata = mapOf(
                "count" to messages.size.toString(),
                "url" to url,
            ),
        )
    }

    private fun arrowFor(direction: Direction): String =
        if (direction == Direction.CLIENT_TO_SERVER) "→" else "←"

    private fun directionLabel(direction: Direction): String =
        if (direction == Direction.CLIENT_TO_SERVER) "client → server" else "server → client"

    private fun wsEndpointUrl(msg: WebSocketMessage): String {
        val upgrade = msg.upgradeRequest()
        return runCatching { upgrade.url() }.getOrNull()
            ?: runCatching { upgrade.path() }.getOrNull()
            ?: "(unknown)"
    }

    companion object {
        private const val MAX_BODY_CHARS = 16_000
    }
}
