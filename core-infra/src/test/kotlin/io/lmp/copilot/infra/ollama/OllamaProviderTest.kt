package io.lmp.copilot.infra.ollama

import io.lmp.copilot.domain.llm.ChatMessage
import io.lmp.copilot.domain.llm.ChatRequest
import io.lmp.copilot.domain.llm.HealthStatus
import io.lmp.copilot.domain.llm.ChatChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OllamaProvider

    @BeforeTest
    fun setup() {
        server = MockWebServer().apply { start() }
        provider = OllamaProvider(OllamaProvider.Config(baseUrl = server.url("").toString().trimEnd('/')))
    }

    @AfterTest
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `healthCheck returns Ok on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":"0.3.12"}"""))
        val status = provider.healthCheck()
        assertTrue(status is HealthStatus.Ok)
    }

    @Test
    fun `healthCheck returns Unreachable on 500`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val status = provider.healthCheck()
        assertTrue(status is HealthStatus.Unreachable)
    }

    @Test
    fun `listModels parses tags response`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"models":[
                  {"name":"llama3.1:8b","details":{"parameter_size":"8B","quantization_level":"Q4_0"}},
                  {"name":"qwen2.5:7b","details":{"parameter_size":"7B","quantization_level":"Q4_K_M"}}
                ]}
                """.trimIndent(),
            ),
        )
        val models = provider.listModels()
        assertEquals(2, models.size)
        assertEquals("llama3.1:8b", models[0].id)
        assertEquals("8B", models[0].parameterSize)
        assertEquals("Q4_K_M", models[1].quantization)
    }

    @Test
    fun `chat streams NDJSON chunks and emits Done`() = runBlocking {
        val ndjson = listOf(
            """{"model":"x","done":false,"message":{"role":"assistant","content":"Hel"}}""",
            """{"model":"x","done":false,"message":{"role":"assistant","content":"lo "}}""",
            """{"model":"x","done":false,"message":{"role":"assistant","content":"world"}}""",
            """{"model":"x","done":true,"done_reason":"stop","message":{"role":"assistant","content":""}}""",
        ).joinToString("\n") + "\n"

        server.enqueue(MockResponse().setResponseCode(200).setBody(ndjson))

        val req = ChatRequest(
            model = "x",
            messages = listOf(ChatMessage(ChatMessage.Role.User, "hi")),
        )
        val chunks = provider.chat(req).toList()

        val text = chunks.filterIsInstance<ChatChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello world", text)
        assertTrue(chunks.last() is ChatChunk.Done)
    }

    @Test
    fun `chat surfaces server error chunks instead of crashing`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"error":"model 'foo' not found"}""" + "\n",
            ),
        )
        val req = ChatRequest(
            model = "foo",
            messages = listOf(ChatMessage(ChatMessage.Role.User, "hi")),
        )
        val chunks = provider.chat(req).toList()
        assertTrue(chunks.any { it is ChatChunk.Error })
    }
}
