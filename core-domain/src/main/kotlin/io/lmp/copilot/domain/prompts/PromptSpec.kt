package io.lmp.copilot.domain.prompts

import kotlinx.serialization.Serializable

/**
 * Prompts live as YAML files, not Kotlin string literals.
 * This means upgrading a prompt is config, not code.
 */
@Serializable
data class PromptSpec(
    val id: String,
    val version: String,
    val system: String,
    /** Mustache-style template with {{placeholders}}. */
    val userTemplate: String = "",
    val outputSchema: String? = null,
    val modelHints: ModelHints = ModelHints(),
    val tags: List<String> = emptyList(),
) {
    @Serializable
    data class ModelHints(
        val temperature: Double? = null,
        val topP: Double? = null,
        val minContextTokens: Int? = null,
        val requiresJsonMode: Boolean = false,
    )
}
