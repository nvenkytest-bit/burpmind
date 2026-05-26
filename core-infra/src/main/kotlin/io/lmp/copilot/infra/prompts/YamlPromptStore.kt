package io.lmp.copilot.infra.prompts

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.lmp.copilot.app.prompts.PromptResolver
import io.lmp.copilot.domain.prompts.PromptSpec
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Loads prompt YAML files from disk and from bundled classpath resources.
 *
 * Disk overrides classpath, so users can customize prompts without rebuilding.
 */
class YamlPromptStore(
    private val userPromptsDir: Path?,
    private val bundledPromptsClasspathDir: String = "prompts",
) : PromptResolver {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    private val cache: Map<String, PromptSpec> by lazy { load() }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun load(): Map<String, PromptSpec> {
        val map = linkedMapOf<String, PromptSpec>()

        // Bundled defaults first
        loadBundled().forEach { map[it.id] = it }

        // User overrides
        if (userPromptsDir != null && userPromptsDir.exists()) {
            userPromptsDir.walk()
                .filter { it.isRegularFile() && (it.extension == "yaml" || it.extension == "yml") }
                .forEach { p ->
                    runCatching {
                        val spec = yaml.decodeFromString(PromptSpec.serializer(), p.readText())
                        map[spec.id] = spec
                    }.onFailure {
                        System.err.println("[YamlPromptStore] failed to load ${p}: ${it.message}")
                    }
                }
        }
        return map
    }

    private fun loadBundled(): List<PromptSpec> {
        // Resources are loaded via the classpath; we don't enumerate them at runtime
        // (that's brittle inside a fat JAR). Instead, ship a manifest file.
        val manifest = javaClass.classLoader.getResourceAsStream("$bundledPromptsClasspathDir/index.txt")
            ?: return emptyList()
        return manifest.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { name ->
                val stream = javaClass.classLoader.getResourceAsStream("$bundledPromptsClasspathDir/$name")
                    ?: return@mapNotNull null
                runCatching {
                    yaml.decodeFromString(PromptSpec.serializer(), stream.bufferedReader().readText())
                }.getOrElse {
                    System.err.println("[YamlPromptStore] failed to load bundled prompt $name: ${it.message}")
                    null
                }
            }
    }

    override fun resolveDefault(): PromptSpec =
        cache["chat.default"]
            ?: PromptSpec(
                id = "chat.default",
                version = "1.0.0",
                system = DEFAULT_SYSTEM_PROMPT,
            )

    override fun resolveById(id: String): PromptSpec? = cache[id]

    override fun listAll(): List<PromptSpec> = cache.values.toList()

    companion object {
        /**
         * Conservative fallback used if no YAML prompt file is found. Intentionally minimal.
         * Real prompts ship as YAML and can be edited without recompiling.
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a senior application security engineer assisting a penetration tester " +
                "inside Burp Suite. The user shares HTTP requests, responses, and notes. " +
                "Answer concisely, prefer concrete actions over generic advice, and call out " +
                "uncertainty explicitly. Never invent endpoints or parameters that aren't in the " +
                "provided context."
    }
}
