package io.lmp.copilot.infra.persistence

import io.lmp.copilot.app.persistence.ThreadStore
import io.lmp.copilot.domain.artifacts.Artifact
import io.lmp.copilot.domain.chat.Message
import io.lmp.copilot.domain.chat.ThreadId
import io.lmp.copilot.domain.chat.ThreadMeta
import io.lmp.copilot.domain.context.ContextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Default [ThreadStore] implementation backed by the local filesystem.
 *
 * Layout:
 *   <root>/threads/<threadId>/
 *     meta.json              (overwrite)
 *     messages.jsonl         (append-only, one Message per line)
 *     artifacts.jsonl        (append-only)
 *     pinned.json            (overwrite, current pinned set)
 *
 * Append-only files are crash-safe: a partial line can be detected and skipped on load.
 */
class FileThreadStore(private val root: Path) : ThreadStore {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
        classDiscriminator = "type"
    }
    private val writeLocks = HashMap<String, Mutex>()
    private val locksMutex = Mutex()

    init {
        root.createDirectories()
        threadsDir().createDirectories()
    }

    private fun threadsDir(): Path = root.resolve("threads")
    private fun dir(id: ThreadId): Path = threadsDir().resolve(id.value)
    private suspend fun lock(id: ThreadId): Mutex = locksMutex.withLock {
        writeLocks.getOrPut(id.value) { Mutex() }
    }

    override suspend fun listThreads(): List<ThreadMeta> = withContext(Dispatchers.IO) {
        if (threadsDir().notExists()) return@withContext emptyList()
        threadsDir().listDirectoryEntries()
            .filter { it.isDirectory() }
            .mapNotNull { dir ->
                val metaFile = dir.resolve("meta.json")
                if (metaFile.exists()) runCatching {
                    json.decodeFromString(ThreadMeta.serializer(), metaFile.readText())
                }.getOrNull() else null
            }
    }

    override suspend fun loadMeta(id: ThreadId): ThreadMeta? = withContext(Dispatchers.IO) {
        val f = dir(id).resolve("meta.json")
        if (f.notExists()) null
        else runCatching { json.decodeFromString(ThreadMeta.serializer(), f.readText()) }.getOrNull()
    }

    override suspend fun saveMeta(meta: ThreadMeta) = withContext(Dispatchers.IO) {
        lock(meta.id).withLock {
            dir(meta.id).createDirectories()
            dir(meta.id).resolve("meta.json").writeText(json.encodeToString(ThreadMeta.serializer(), meta))
        }
    }

    override suspend fun appendMessage(message: Message): Unit = withContext(Dispatchers.IO) {
        lock(message.threadId).withLock {
            dir(message.threadId).createDirectories()
            val line = json.encodeToString(Message.serializer(), message) + "\n"
            Files.writeString(
                dir(message.threadId).resolve("messages.jsonl"),
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            Unit
        }
    }

    override suspend fun loadMessages(id: ThreadId): List<Message> = withContext(Dispatchers.IO) {
        val f = dir(id).resolve("messages.jsonl")
        if (f.notExists()) return@withContext emptyList()
        Files.readAllLines(f).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(Message.serializer(), line) }.getOrNull()
        }
    }

    override suspend fun appendArtifact(threadId: ThreadId, artifact: Artifact): Unit = withContext(Dispatchers.IO) {
        lock(threadId).withLock {
            dir(threadId).createDirectories()
            val line = json.encodeToString(Artifact.serializer(), artifact) + "\n"
            Files.writeString(
                dir(threadId).resolve("artifacts.jsonl"),
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            Unit
        }
    }

    override suspend fun loadArtifacts(id: ThreadId): List<Artifact> = withContext(Dispatchers.IO) {
        val f = dir(id).resolve("artifacts.jsonl")
        if (f.notExists()) return@withContext emptyList()
        Files.readAllLines(f).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(Artifact.serializer(), line) }.getOrNull()
        }
    }

    override suspend fun savePinnedContext(id: ThreadId, items: List<ContextItem>) = withContext(Dispatchers.IO) {
        lock(id).withLock {
            dir(id).createDirectories()
            val payload = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ContextItem.serializer()),
                items,
            )
            dir(id).resolve("pinned.json").writeText(payload)
        }
    }

    override suspend fun loadPinnedContext(id: ThreadId): List<ContextItem> = withContext(Dispatchers.IO) {
        val f = dir(id).resolve("pinned.json")
        if (f.notExists()) return@withContext emptyList()
        runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ContextItem.serializer()),
                f.readText(),
            )
        }.getOrElse { emptyList() }
    }

    override suspend fun deleteThread(id: ThreadId) = withContext(Dispatchers.IO) {
        val d = dir(id)
        if (d.exists()) {
            d.toFile().deleteRecursively()
        }
        Unit
    }
}
