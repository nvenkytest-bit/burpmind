# BurpMind

> A Burp Suite extension that brings a **local LLM** (via [Ollama](https://ollama.com))
> into your testing workflow — chat, attach requests, get analysis, all on your machine.
> Nothing leaves your laptop.

## Features

- **Local-first chat.** Streaming chat with any Ollama model (`llama3.1`, `qwen2.5`,
  `deepseek-r1`, `mistral`, etc.). Tokens render live as they arrive.
- **Live thinking display.** When a reasoning model emits internal thoughts,
  BurpMind shows them in a collapsible panel and auto-collapses when the answer
  begins streaming.
- **Request attachments.** Right-click any request in Repeater / Proxy /
  HTTP history / Intruder / Logger → **Add to BurpMind chat**. The request appears
  as a numbered, collapsible bubble that the model can reference as `[1]`, `[2]`, etc.
- **Pinned context.** Mark items as persistent context so they're sent on every turn —
  useful for app overviews, auth flows, or remembering "this is the admin endpoint".
- **Markdown done right.** GFM tables, fenced code blocks, task lists, autolinks,
  strikethrough. Renders cleanly inside Burp's native look-and-feel, in light and
  dark themes.
- **Multi-thread sidebar.** Create, rename, and delete threads. Each thread keeps its
  own pinned context and history.
- **Append-only persistence.** JSONL files under `~/.burpmind/`, schema-versioned for
  forward compatibility.
- **Theme-aware UI.** Adapts to Burp's active Look-and-Feel. No global L&F overrides,
  no breaking Repeater, no surprise color shifts.

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
API, so the core can later be reused for other tools.

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
4. Right-click any request → **Add to BurpMind chat**.
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

Append-only writes make crash recovery trivial. Every record carries a
`schemaVersion` so migrations stay clean.

## Status

BurpMind is under active development. Watch the repo for new releases.

## Trademarks

Burp Suite is a trademark of PortSwigger Ltd. This is an unofficial community
extension and is not affiliated with, endorsed by, or sponsored by PortSwigger.
Ollama is a trademark of Ollama, Inc.

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).
