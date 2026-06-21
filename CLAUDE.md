# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Incremental build (skip clean if app is running — jar file lock)
mvn compile

# Full rebuild (stop the app first)
mvn clean compile

# Run (defaults to port 8080)
mvn spring-boot:run
# or: java -jar target/deepseek-chatbot-1.0.0.jar
```

**Java 25** / **Spring Boot 3.5.15** / **Maven** (no Gradle wrapper). There are **no tests** in this project.

## Application Config

`application.properties` is minimal — just `server.port=8080` and logging level. Everything is **in-memory** (session history, rate-limit counters). No database, no Spring profiles, no external dependencies beyond the POM. Config directory `~/.deepseek-chatbot/` is gitignored (contains API keys).

## Architecture

### Adapter Pattern for Multi-Provider LLM

The core abstraction is `BaseModelAdapter` — each AI provider has a `@Component` subclass:

```
BaseModelAdapter                    (streamChat, extractContent)
├── DeepSeekAdapter                (deepseek)   — own body builder + shared static helper
├── MoonshotKimiAdapter            (moonshot)   — own body builder (omits temperature)
├── QwenAdapter                    (qwen)       — delegates to DeepSeekAdapter.buildOpenAiBody()
├── ZhipuGlmAdapter                (zhipu)      — delegates to DeepSeekAdapter.buildOpenAiBody()
├── MiniMaxAdapter                 (minimax)    — delegates to DeepSeekAdapter.buildOpenAiBody()
└── MiMoAdapter                    (mimo)       — delegates to DeepSeekAdapter.buildOpenAiBody()
```

`ModelAdapterFactory` auto-discovers all `BaseModelAdapter` beans via Spring DI constructor injection (`List<BaseModelAdapter>`) and routes by `provider` code.

### Code sharing pattern

Four of the six adapters (Qwen, Zhipu, MiniMax, MiMo) have identical logic — they just call `DeepSeekAdapter.buildOpenAiBody(req, defaultBase)`. Only `DeepSeekAdapter` and `MoonshotKimiAdapter` have custom `buildNativeBody` implementations. The `defaultBase` parameter passed to `buildOpenAiBody` is **not actually used** by that method (it only builds the JSON body); routing is handled by `BaseModelAdapter.buildUrl()` which uses `req.getBaseUrl()` sent from the frontend.

When adding a new standard OpenAI-compatible provider, you only need a 3-line adapter that delegates to `buildOpenAiBody`, plus a new entry in `ChatController.providers()`.

### Request Flow (Streaming SSE)

```
Browser (index.html)
  → POST /api/chat  { provider, modelName, apiKey, baseUrl, temperature, maxTokens, messages }
  → ChatController.chat()
  → ChatService.chat()
    → new ConfigManager().getSystemPrompt()    // appends system prompt to messages
    → ModelAdapterFactory.getAdapter(provider)
    → adapter.streamChat(req, onChunk, onError, onComplete)
      → buildNativeBody(req)     // OpenAI-compatible JSON
      → buildUrl(req)            // req.baseUrl + "/chat/completions"
      → HTTP POST with SSE stream (java.net.http.HttpClient)
      → extractContent(data)     // parse delta.content || delta.reasoning_content
      → onChunk callback          // ChatService sends SseEmitter event
  ← SSE events: event:chunk / event:done / event:error
Browser: reader.read() loop, markdown render with marked.js + KaTeX
```

The frontend is a **single `index.html`** with vanilla JS (no framework). All state (provider, model, API key, baseUrl) lives in `localStorage` keyed by provider code.

### API Keys are NOT stored server-side

`apiKey` and `baseUrl` are sent by the frontend with **every request** in the JSON body (`UnifiedChatRequest`). The `ConfigManager` (`~/.deepseek-chatbot/config.json`) stores legacy global settings. The **only** thing the multi-provider flow reads from `ConfigManager` is `getSystemPrompt()` — and it does so via `new ConfigManager()` (not the Spring bean), which re-reads the config file on every chat request.

### Provider List

Defined in `ChatController.providers()` — hardcoded list of provider codes, display names, default base URLs, and model options. Frontend fetches via `GET /api/providers` on load. This is the **single source of truth** for which providers are available; there's no dynamic discovery.

### URL fallback behavior (beware)

`BaseModelAdapter.buildUrl()` uses `req.getBaseUrl()` from the frontend. If the frontend omits `baseUrl`, it falls back to `https://api.deepseek.com/v1/chat/completions` **regardless of which adapter is active**. In normal operation this never triggers (the frontend always sends the provider's baseUrl), but if you're testing adapters directly without the frontend, you must supply `baseUrl`.

## Provider-Specific Quirks

### Moonshot Kimi (`MoonshotKimiAdapter`)
- **temperature**: `kimi-k2.6` / `kimi-k2.5` reasoning models **only accept `temperature: 1`**. Any other value returns HTTP 400. The adapter intentionally omits the temperature field to avoid this.
- **Streaming format**: Reasoning models emit `delta.reasoning_content` (not `delta.content`) during the thinking phase. `BaseModelAdapter.extractContent()` handles both — checks `content` first, falls back to `reasoning_content`.
- Standard models (`moonshot-v1-128k`) use normal `delta.content`.

### Standard OpenAI-compatible providers
DeepSeek, Qwen, Zhipu (GLM), MiniMax, and MiMo all accept the standard body with `temperature` and `max_tokens` fields. No special handling needed.

### General SSE Format
All providers follow OpenAI-compatible streaming. The Kimi API sends `data: [DONE]` as end-of-stream marker — `BaseModelAdapter.streamChat()` breaks the read loop on this.

### extractContent() in BaseModelAdapter
Returns `null` for chunks with no displayable text (e.g., first chunk with `{"role":"assistant","content":""}`). Callers skip null/empty results.

## Rate Limiting

`RateLimitFilter` limits `/api/chat` to **30 requests/minute per IP**. Returns HTTP 429. Sliding window — resets exactly 60 seconds after the first request in a burst. Only intercepts `/api/chat` path; all other endpoints pass through unchanged.

## Session & History

- In-memory `ConcurrentHashMap<String, List<ChatMessage>>` in `ChatService`
- Session ID is an 8-char UUID substring stored in browser cookie `sid`
- Max 20 messages per session (oldest trimmed when limit exceeded)
- History is stored server-side and returned on `GET /api/history`
- `DELETE /api/chat` clears the session
- System prompt is prepended to every request's message list by `ChatService`

## Frontend SSE Parsing

The browser code parses standard SSE (`event:` / `data:` lines with blank-line delimiters). Only `currentEvent == 'chunk'` triggers content rendering. The `done` event stops the loading spinner. Stream end is also detected by `reader.read()` returning `done: true`.

## K12+University Stage System

Four stages with escalating knowledge boundaries and temperature:

```
primary (小学, 0.1) → junior (初中, 0.15) → senior (高中, 0.2) → university (大学拓展, 0.3)
```

Stage constants live in `UnifiedChatRequest`: `STAGE_PRIMARY`, `STAGE_JUNIOR`, `STAGE_SENIOR`, `STAGE_UNIVERSITY`. Temperature map: `STAGE_TEMPERATURE` (`Map<String, Double>`). Stage ordering: `STAGE_ORDER` (`Map<String, Integer>`).

**Key rules:**
- `ChatService.buildStageDirective()` dynamically appends stage-specific constraints to every request's system prompt
- `buildMessages()` applies stage-appropriate temperature from `STAGE_TEMPERATURE` when not explicitly set
- University extend toggle (`allowUniversityExtend`) only active in senior mode; unlocks university-level answers + university knowledge base
- RAG retrieval filters by stage: only docs ≤ current stage are visible; university docs require `allowUniversityExtend=true`
- University docs have 0.6 weight vs 1.0 for K12 docs

## RAG Architecture (`com.chatbot.rag`)

### Pipeline

```
PDF file → PdfDocumentParser (PDFBox) → raw text
        → MathChunkingStrategy (chapter-split + formula-protected chunking, 600-char chunks, 100-char overlap)
        → EmbeddingService (cloud API or local n-gram fallback)
        → InMemoryVectorStore (cosine similarity index + JSON persistence)
        → RagService.buildRagContext() (retrieve + augment system prompt)
```

### Key components

| Class | Role |
|-------|------|
| `RagService` | Orchestrates full pipeline; `buildRagContext()` for chat augmentation; `indexPdf()`/`indexDirectory()` for ingestion |
| `PdfDocumentParser` | Apache PDFBox 3.0.4 wrapper; max 50MB; rejects encrypted PDFs |
| `MathChunkingStrategy` | Chapter-aware splitting; LaTeX formula protection (`$$...$$` blocks stay intact); auto-detects stage/knowledge-point/question-type via keyword matching |
| `EmbeddingService` | Dual-mode: `embedCloud()` calls OpenAI-compatible embeddings API, `embedLocal()` uses n-gram TF-IDF (256-dim, no API key needed) |
| `InMemoryVectorStore` | Cosine similarity search; stage-filtered (`passesStageFilter`); keyword fallback; JSON persistence to `~/.deepseek-chatbot/vector_store/`; university docs weighted 0.6 |
| `KnowledgeBaseController` | REST API at `/api/knowledge/*` for stats, PDF list, indexing, clearing; auto-detects stage from directory path |

### Embedding modes

- **Cloud mode** (API key present): calls `{baseUrl}/embeddings` with model `text-embedding-ada-002`. Falls back to local mode on failure.
- **Local mode** (no API key): character 2-gram + word hash into 256-dim vector. Works offline, no cost.

### Knowledge base directory structure

```
~/.deepseek-chatbot/math-library/
├── primary/        → auto-detected as STAGE_PRIMARY
├── junior/         → auto-detected as STAGE_JUNIOR
├── senior/         → auto-detected as STAGE_SENIOR
└── university/     → auto-detected as STAGE_UNIVERSITY
```

`detectStageFromPath()` in `KnowledgeBaseController` matches path keywords: "university/大学/高等" → university, "senior/高中/高考" → senior, "primary/小学" → primary, "junior/初中/中考" → junior.

## Legacy / Unused Code

- **`DeepSeekClient.java`**: Standalone DeepSeek-only client (non-Spring). Uses `ConfigManager` directly, supports both stream and non-stream calls. Appears to be **superseded** by the adapter pattern — not wired into any controller or service. Don't confuse it with the active code path.
- **`ModelProviderConfig.java`**: Model class with provider/config fields. Not used in the current request flow (`UnifiedChatRequest` is the active request model). May have been intended for frontend use.
