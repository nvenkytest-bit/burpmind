package io.lmp.copilot.app.prompts

import io.lmp.copilot.domain.prompts.PromptSpec

/**
 * Resolves prompt templates by id. Implementations live in infra.
 *
 * Phase 2 only needs the default chat prompt. Phase 3 will add lookup by tool id.
 */
interface PromptResolver {
    fun resolveDefault(): PromptSpec
    fun resolveById(id: String): PromptSpec?
    fun listAll(): List<PromptSpec>
}
