package io.lmp.copilot.app

import io.lmp.copilot.domain.llm.HealthStatus
import io.lmp.copilot.domain.llm.LlmProvider
import io.lmp.copilot.domain.llm.ModelInfo

/**
 * "Can we talk to the model?" — the simplest use case, but the one that proves
 * the whole stack from UI → app → provider → backend is wired correctly.
 */
class HealthService(private val provider: () -> LlmProvider) {

    suspend fun ping(): HealthStatus = runCatching {
        provider().healthCheck()
    }.getOrElse { HealthStatus.Unreachable(it.message ?: it::class.simpleName ?: "unknown error") }

    suspend fun listModels(): Result<List<ModelInfo>> = runCatching {
        provider().listModels()
    }
}
