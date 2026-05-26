package io.lmp.copilot.domain.artifacts

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A structured LLM output. Anything that isn't plain chat text becomes an Artifact.
 *
 * This is the spine of the extensibility story: persistence, rendering, exporting,
 * and the event bus all key off the Artifact type.
 *
 * Each subtype carries its own schemaVersion so persisted data can be migrated.
 */
@Serializable
sealed interface Artifact {
    val id: String
    val schemaVersion: Int
    val createdAt: Instant
    val title: String
}

@Serializable
data class RawMarkdown(
    override val id: String,
    override val createdAt: Instant = Clock.System.now(),
    override val title: String = "Response",
    val markdown: String,
    override val schemaVersion: Int = 1,
) : Artifact

/**
 * Phase 3 will populate this with real ChecklistItem data — keeping the shell here
 * so Phase 2 can already round-trip the type discriminator through persistence.
 */
@Serializable
data class Checklist(
    override val id: String,
    override val createdAt: Instant = Clock.System.now(),
    override val title: String,
    val items: List<ChecklistItem> = emptyList(),
    override val schemaVersion: Int = 1,
) : Artifact

@Serializable
data class ChecklistItem(
    val id: String,
    val category: String,
    val title: String,
    val rationale: String? = null,
    val steps: List<String> = emptyList(),
    val severity: Severity = Severity.Info,
    val owasp: String? = null,
    val relatedContextId: String? = null,
    val checked: Boolean = false,
) {
    enum class Severity { Critical, High, Medium, Low, Info }
}
