package io.lmp.copilot.burp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import io.lmp.copilot.app.ChatService
import io.lmp.copilot.app.ContextService
import io.lmp.copilot.app.HealthService
import io.lmp.copilot.app.ThreadService
import io.lmp.copilot.infra.EventBus
import io.lmp.copilot.infra.ollama.OllamaProvider
import io.lmp.copilot.infra.persistence.FileThreadStore
import io.lmp.copilot.infra.prompts.YamlPromptStore
import io.lmp.copilot.ui.CopilotSettings
import io.lmp.copilot.ui.CopilotUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Montoya entry point. This is the only class Burp instantiates directly
 * (registered in META-INF/MANIFEST.MF via the shadow plugin).
 */
class BurpExtensionMain : BurpExtension {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    override fun initialize(api: MontoyaApi) {
        api.extension().setName("BurpMind")

        // 1. Persistence dir under the user's home. Phase 2 keeps things simple;
        //    Phase 5 will optionally relocate this into the Burp project file.
        val configDir = Path.of(System.getProperty("user.home"), ".burpmind")
        configDir.toFile().mkdirs()

        // 2. Wire infra
        val settings = loadSettings(api, configDir)
        val provider = OllamaProvider(OllamaProvider.Config(baseUrl = settings.ollamaBaseUrl))
        val providerHolder = ProviderHolder(provider)
        val store = FileThreadStore(configDir)
        val promptStore = YamlPromptStore(userPromptsDir = configDir.resolve("prompts"))
        val eventBus = EventBus()

        // 3. Wire application services
        val healthService = HealthService { providerHolder.current }
        val threadService = ThreadService(store)
        val chatService = ChatService(
            store = store,
            providerLookup = { providerHolder.current },
            promptResolver = promptStore,
        )
        val contextService = ContextService(store)

        // 4. Register Burp context source
        val burpRequestSource = BurpRequestSource(api)
        contextService.register(burpRequestSource)

        // 5. UI — must touch Swing on the EDT.
        // NOTE: deliberately do NOT change the Look-and-Feel here. L&F is JVM-global,
        // so installing FlatLaf (or anything else) would corrupt Burp's own theme for
        // Repeater, Extensions, etc. We inherit Burp's theme via UIManager lookups.
        SwingUtilities.invokeLater {
            val ui = CopilotUi(
                healthService = healthService,
                threadService = threadService,
                chatService = chatService,
                contextService = contextService,
                scope = scope,
                settings = settings,
            )
            api.userInterface().registerSuiteTab("BurpMind", ui.component())

            // Context menu — right-click menu in Repeater/Proxy/etc.
            // "Add to chat" is the default (one-time inclusion as a chat bubble);
            // "Pin as session context" is the older always-on behavior.
            api.userInterface().registerContextMenuItemsProvider(
                CopilotContextMenuProvider(
                    burpRequestSource = burpRequestSource,
                    onAddToChat = { item -> ui.attachToChat(item) },
                    onPinRequest = { item -> ui.pinContext(item) },
                ),
            )

            // React to settings changes by rebuilding the provider and persisting prefs.
            settings.addChangeListener {
                providerHolder.replace(OllamaProvider(OllamaProvider.Config(baseUrl = settings.ollamaBaseUrl)))
                persistSettings(api, settings)
            }

            api.logging().logToOutput("BurpMind loaded. Config dir: $configDir")
        }

        api.extension().registerUnloadingHandler {
            api.logging().logToOutput("BurpMind unloading.")
            supervisor.cancel()
            persistSettings(api, settings)
        }
    }

    private fun loadSettings(api: MontoyaApi, configDir: Path): CopilotSettings {
        val pref = api.persistence().preferences()
        return CopilotSettings(
            ollamaBaseUrl = pref.getString(K_OLLAMA_URL) ?: "http://localhost:11434",
            defaultModel = pref.getString(K_MODEL) ?: "llama3.1:8b",
            providerId = pref.getString(K_PROVIDER) ?: "ollama",
        )
    }

    private fun persistSettings(api: MontoyaApi, settings: CopilotSettings) {
        val pref = api.persistence().preferences()
        pref.setString(K_OLLAMA_URL, settings.ollamaBaseUrl)
        pref.setString(K_MODEL, settings.defaultModel)
        pref.setString(K_PROVIDER, settings.providerId)
    }

    companion object {
        private const val K_OLLAMA_URL = "llm-copilot.ollama.baseUrl"
        private const val K_MODEL = "llm-copilot.defaultModel"
        private const val K_PROVIDER = "llm-copilot.providerId"
    }
}

/** Lets us swap the active provider at runtime without re-wiring services. */
private class ProviderHolder(@Volatile var current: io.lmp.copilot.domain.llm.LlmProvider) {
    fun replace(next: io.lmp.copilot.domain.llm.LlmProvider) {
        current = next
    }
}
