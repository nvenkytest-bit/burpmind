package io.lmp.copilot.domain.tools

import io.lmp.copilot.domain.artifacts.Artifact
import io.lmp.copilot.domain.context.ContextItem
import io.lmp.copilot.domain.llm.LlmProvider
import kotlinx.serialization.Serializable

/**
 * A slash command / right-click action.
 *
 * The contract is intentionally shaped as a superset of the MCP tool contract,
 * so Phase 9 can expose these to external MCP clients without refactoring.
 */
interface Tool {
    val spec: ToolSpec
    suspend fun execute(ctx: ToolContext): ToolResult
}

@JvmInline
@Serializable
value class ToolId(val value: String) {
    override fun toString(): String = value
}

@Serializable
data class ToolSpec(
    val id: ToolId,
    /** Slash command form, e.g. "/checklist". May be null for tools only triggered via right-click. */
    val slashCommand: String?,
    val displayName: String,
    val description: String,
    /** JSON Schema for parameters. Empty schema means no params. */
    val parametersSchema: String = "{}",
    /** Hint to the UI which renderer to use for the resulting Artifact. */
    val resultRendererHint: String? = null,
    val privacyClass: PrivacyClass = PrivacyClass.Default,
) {
    enum class PrivacyClass {
        /** Honours the active redaction policy. */
        Default,

        /** Always sends raw context regardless of policy (e.g. local-only decode). */
        AlwaysLocal,
    }
}

/** Everything a Tool needs to execute, injected by the dispatcher. */
data class ToolContext(
    val rawInput: String,
    val parsedArgs: Map<String, String> = emptyMap(),
    val pinnedContext: List<ContextItem> = emptyList(),
    val provider: LlmProvider,
    val model: String,
)

sealed interface ToolResult {
    /** The tool produced a structured artifact to attach to the thread. */
    data class Produced(val artifact: Artifact) : ToolResult

    /** The tool failed — never surface as a thread-poisoning crash. */
    data class Failed(val message: String, val cause: Throwable? = null) : ToolResult

    /** The tool understood the command but had nothing to emit (e.g. /note). */
    data object NoOp : ToolResult
}
