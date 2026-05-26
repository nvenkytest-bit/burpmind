# BurpMind

> A Burp Suite extension that brings a **local LLM** (via [Ollama](https://ollama.com))
> into your testing workflow — chat, attach requests, get analysis, all on your machine.
> Nothing leaves your laptop.

BurpMind is built for the security tester who wants the productivity of an AI copilot
without sending production traffic, session tokens, or client data to a cloud provider.
You run an LLM locally with Ollama; BurpMind handles the rest — context capture,
streaming chat, markdown rendering, request attachments, and persistence.

## Features

- **Local-first chat.** Streaming chat with any Ollama model (`llama3.1`, `qwen2.5`,
  `deepseek-r1`, `mistral`, etc.). Tokens render live as they arrive.
- **Live thinking display.** When a reasoning model emits internal thoughts
  (`think: true`), BurpMind shows them in a collapsible panel so you can see *why*
  it's saying what it's saying, then auto-collapses when the real answer starts.
- **Request attachments.** Right-click any request in Repeater / Proxy / HTTP history /
  Intruder / Logger → **Add to BurpMind chat**. The request appears as a numbered,
  collapsible bubble that the model can reference as `[1]`, `[2]`, etc.
- **Pinned context.** Mark items as persistent context so they're sent on every turn —
  useful for app overviews, auth flows, or "remember this is the admin endpoint".
- **Markdown done right.** GFM tables, fenced code blocks, task lists, autolinks,
  strikethrough. Renders cleanly inside Burp's native look-and-feel, in both light
  and dark themes.
- **Multi-thread sidebar.** Create, rename, and delete threads. Each thread keeps its
  own pinned context and history.
- **Append-only persistence.** JSONL files under `~/.burpmind/`, schema-versioned for
  forward compatibility.
- **Theme-aware UI.** Adapts to Burp's active Look-and-Feel. No global L&F overrides,
  no breaking Repeater, no surprise color shifts.

## Why this exists

The available Burp + LLM extensions either ship session tokens to cloud APIs, lock
you into a single provider, or have UIs that get in your way. BurpMind is small,
local, and built to grow — the architecture (five modules, dependency rule: domain
← app ← infra/ui ← adapter) makes it cheap to add new providers, tools, or context
sources without touching the chat surface.

## Module layout

```
core-domain/     pure Kotlin: types and interfaces, no IO, no Burp
core-app/        use cases: ChatService, ThreadService, ContextService, HealthService
core-infra/      OllamaProvider, FileThreadStore, YamlPromptStore, EventBus, Registry
ui-swing/        Swing UI (chat panel, settings, sidebar) — knows nothing of Burp
adapter-burp/    Montoya entry point + Burp request source + context menu (the ONLY
                 module that imports burp.api.montoya.*)
```

Dependencies point strictly inward. Only `adapter-burp` links against the Montoya
API, so the core can later be reused for other tools (Caido port, CLI, etc.).

## Requirements

- **Burp Suite** 2025.5 or newer (Community or Pro)
- **JDK 21+** to build (Burp ships its own JRE; the JAR runs there)
- **[Ollama](https://ollama.com)** running locally with at least one model pulled

## Build

```bash
./gradlew :adapter-burp:shadowJar
```

The fat JAR lands at:

```
adapter-burp/build/libs/burpmind-0.1.0-SNAPSHOT.jar
```

Pre-built JARs are also attached to every GitHub Actions run — check the
[Actions](../../actions) tab if you don't want to build locally.

## Install in Burp

1. **Extensions → Installed → Add**
2. Extension type: **Java**
3. Select the JAR above
4. A new **BurpMind** tab appears.

## First-time setup

1. Start Ollama and pull a model:

   ```bash
   ollama serve &
   ollama pull llama3.1:8b
   # or any other model — qwen2.5, deepseek-r1, mistral, etc.
   ```

2. In **BurpMind → Settings**: click **Test connection**, then **Refresh models**,
   then pick a default model from the dropdown.
3. Switch to the **Chat** tab and click **+ New thread**.
4. Right-click any request → **Add to BurpMind chat** (or use the submenu for
   pinning / both).
5. Ask a question. Press **Ctrl/Cmd + Enter** to send.

## On-disk layout

```
~/.burpmind/
├── prompts/                  user prompt overrides (optional)
└── threads/<thread-id>/
    ├── meta.json             thread metadata
    ├── messages.jsonl        append-only chat history
    ├── artifacts.jsonl       structured outputs (checklists, payloads, etc.)
    └── pinned.json           current pinned context items
```

Append-only writes make crash recovery trivial and keep the door open for a
future "time-travel / branch this conversation" feature. Every record carries a
`schemaVersion` so migrations stay clean.

## Roadmap

Implemented (v0.1):

- Phase 1 — Foundation skeleton
- Phase 2 — MVP chat with streaming, threads, pinned context
- Phase 2.5 — Inline request attachments with `[N]` references
- Live thinking display + sticky-scroll throttled streaming
- GFM markdown (tables, task lists, autolinks, strikethrough)

Next up:

- Phase 3 — `/checklist` as a structured Artifact with checkboxes
- Phase 4 — `/payloads` and **Send to Repeater / Intruder** from within the chat
- Phase 5 — Slash commands + prompt library
- Phase 6 — Redaction layer with privacy preview before send
- Phase 7 — Local RAG (PortSwigger Academy, OWASP Testing Guide, your own notes)
- Phase 8 — Extra providers (LM Studio, OpenAI-compatible, vLLM)
- Phase 9 — Tool use / function calling for guided multi-step flows

## Contributing

This is an early-stage project; the architecture is the contribution-friendly part.
If you want to add a new LLM provider, context source, tool, or output renderer,
the existing `Registry<K, V>` slots in `core-infra` are where everything plugs in —
no chat-surface changes required.

For bug reports and ideas, please open an issue.

## Trademarks

Burp Suite is a trademark of PortSwigger Ltd. This is an unofficial community
extension and is not affiliated with, endorsed by, or sponsored by PortSwigger.
Ollama is a trademark of Ollama, Inc.

## License

License TBD. Until one is added, all rights are reserved by the author. If you'd
like to use this in a project, please open an issue.
