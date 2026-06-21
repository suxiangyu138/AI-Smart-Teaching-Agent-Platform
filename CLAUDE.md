# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build (skip clean if app is running — jar file lock)
mvn compile

# Full rebuild (stop the app first)
mvn clean compile

# Run
mvn spring-boot:run
# or: java -jar target/deepseek-chatbot-1.0.0.jar

# Server starts on http://localhost:8080
```

**Java 25** / **Spring Boot 3.5.15** / **Maven** (no Gradle wrapper).

## Architecture

### Adapter Pattern for Multi-Provider LLM

The core abstraction is `BaseModelAdapter` — each AI provider has a subclass:

```
BaseModelAdapter                    (streamChat, buildUrl, extractContent)
├── DeepSeekAdapter                (deepseek)
├── MoonshotKimiAdapter            (moonshot)
├── QwenAdapter                    (qwen)
├── ZhipuGlmAdapter                (zhipu)
├── MiniMaxAdapter                 (minimax)
└── MiMoAdapter                    (mimo)
```

`ModelAdapterFactory` auto-discovers all `BaseModelAdapter` beans (Spring DI) and routes by `provider` code.

### Request Flow (Streaming SSE)

```
Browser (index.html)
  → POST /api/chat  { provider, modelName, apiKey, baseUrl, temperature, maxTokens, messages }
  → ChatController.chat()
  → ChatService.chat()
    → ModelAdapterFactory.getAdapter(provider)
    → adapter.streamChat(req, onChunk, onError, onComplete)
      → buildNativeBody(req)     // OpenAI-compatible JSON
      → buildUrl(req)            // baseUrl + "/chat/completions"
      → HTTP POST with SSE stream
      → extractContent(data)     // parse delta.content || delta.reasoning_content
      → onChunk callback          // ChatService sends SseEmitter event
  ← SSE events: event:chunk / event:done / event:error
Browser: reader.read() loop, markdown render with marked.js + KaTeX
```

The frontend is a **single `index.html`** with vanilla JS (no framework). All state (provider, model, API key, baseUrl) lives in `localStorage` keyed by provider code.

### API Keys are NOT stored server-side

`apiKey` and `baseUrl` are sent by the frontend with **every request** in the JSON body (`UnifiedChatRequest`). The `ConfigManager` (`~/.deepseek-chatbot/config.json`) stores legacy global settings but is not used by the multi-provider flow.

### Provider List

Defined in `ChatController.providers()` — hardcoded list of provider codes, display names, default base URLs, and model options. Frontend fetches via `GET /api/providers` on load.

## Provider-Specific Quirks

### Moonshot Kimi (`MoonshotKimiAdapter`)
- **temperature**: `kimi-k2.6` / `kimi-k2.5` reasoning models **only accept `temperature: 1`**. Any other value returns HTTP 400. The adapter intentionally omits the temperature field to avoid this.
- **Streaming format**: Reasoning models emit `delta.reasoning_content` (not `delta.content`) during the thinking phase. `BaseModelAdapter.extractContent()` handles both — checks `content` first, falls back to `reasoning_content`.
- Standard models (`moonshot-v1-128k`) use normal `delta.content`.

### General SSE Format
All providers follow OpenAI-compatible streaming. The Kimi API sends `data: [DONE]` as end-of-stream marker — `BaseModelAdapter.streamChat()` breaks the read loop on this.

### extractContent() in BaseModelAdapter
Returns `null` for chunks with no displayable text (e.g., first chunk with `{"role":"assistant","content":""}`). Callers skip null/empty results.

## Rate Limiting

`RateLimitFilter` limits `/api/chat` to **30 requests/minute per IP**. Returns HTTP 429. Applied before requests reach the controller.

## Session & History

- In-memory `ConcurrentHashMap<String, List<ChatMessage>>` in `ChatService`
- Session ID is a UUID substring stored in browser cookie `sid`
- Max 20 messages per session
- History is stored server-side and returned on `GET /api/history`
- `DELETE /api/chat` clears the session

## Frontend SSE Parsing

The browser code parses standard SSE (`event:` / `data:` lines with blank-line delimiters). Only `currentEvent == 'chunk'` triggers content rendering. The `done` event stops the loading spinner. Stream end is also detected by `reader.read()` returning `done: true`.
