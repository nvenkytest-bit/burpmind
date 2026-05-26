package io.lmp.copilot.burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
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

    companion object {
        private const val MAX_BODY_CHARS = 16_000
    }
}
