---
title: Feature registries
tags: [concept, tools, resources, prompts, completions]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java]
updated: 2026-09-15
commit: 751331f4
---

# 🧰 Feature registries

Verdict: each MCP feature = public façade in `tachyon-api` (`Tools`, `Resources`, `Prompts`, `Completions`, `Tasks`) + `Default*Registry` in core + static `*MethodHandlers.register(map, …)` JSON-RPC adapters. Sync fn wrapped into async fn at registration; dispatch always async. Registries are live — register/unregister after `start()` fires `list_changed`.

## 📋 Registration API shape

`TachyonServer.annotations(...)` (see [[declarative-configuration]]) feeds annotated objects into these same registries with configured codecs, after construction (`DefaultTachyonServer.java:1044`). Used by Spring after singleton initialization; [[spring-boot]].

Rule set lives in `docs/architecture/guidance.md` (AGENTS.md mandates reading it before changing SAMs/registry names). Observed in code:

| Façade | Sync SAM | Async SAM | Extras | Proof |
|---|---|---|---|---|
| `Tools` | `ToolFn` (throws) | `AsyncToolFn` | typed `register(Class<I>,Class<O>, …, TypedToolFn)` auto-generates schemas via `JsonSchema.generate` | `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/Tools.java:20-160` |
| `Resources` | `ResourceFn` | `AsyncResourceFn` | templates, `unregisterByUri`, `findByUri`, `notifyResourceUpdated` | `.../resources/Resources.java:12-200` |
| `Prompts` | `PromptFn` | `AsyncPromptFn` | static `List<PromptMessage>` overload | `.../prompts/Prompts.java:13-106` |
| `Completions` | `CompletionFn` | `AsyncCompletionFn` | keyed by prompt name or uri/template | `.../completions/Completions.java:13-66` |

All: `register(Descriptor, fn)` + `register(Consumer<Descriptor.Builder>, fn)`, `unregister(name)`, `find(name)`, `descriptors()` (name-sorted).

Sync adapters assert VT: `HandlerFutures.assumeVirtualThread()` (Java `assert`) e.g. `DefaultToolRegistry.java:60-68`, `AbstractToolHandler.handle` guardrail `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/AbstractToolHandler.java:70-73`.

## 🗃️ Registries

| Registry | Storage | Notes | Proof |
|---|---|---|---|
| `AbstractRegistry<D,R>` | `ConcurrentHashMap` by name + `ChangeSupport` | `addItem` replaces; `list(limit,cursor,filter)` name-sorted | `tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/AbstractRegistry.java:20-172` |
| `DefaultToolRegistry` | extends above | name `[a-zA-Z0-9_\-./]+` ≤64 (SEP-986); input schema root `type: object`; output schema object; `x-mcp-header` rules; desc > 2048 warn | `.../tools/DefaultToolRegistry.java:80-156` |
| `DefaultPromptRegistry` | extends above | | `.../prompts/DefaultPromptRegistry.java:52-59` |
| `DefaultResourceRegistry` | immutable `Index(byUri)` swapped under `ReentrantLock` (volatile read) + templates CHM + subscriptions CHM | **URI = identity**; same URI other name ⇒ IAE; cursor key = b64(name)+b64(uri); template dup name ⇒ IAE | `.../resources/DefaultResourceRegistry.java:60-307` |
| `DefaultCompletionRegistry` | 2 CHMs | no change events | `.../completions/DefaultCompletionRegistry.java:24-97` |

Mode `OFF` ⇒ registration silently skipped (debug log) in every registry.

## 🎚️ Capability resolution

`DefaultTachyonServer.resolveCapabilities` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java:212-271`:
- `Mode.ON` ⇒ advertise; `OFF` ⇒ no; `AUTO` ⇒ advertise iff registry non-empty (computed **per request**, so post-start registrations show up).
- `tasks` advertised if enabled **or** any tool `taskSupport ∉ {null, FORBIDDEN}`.
- `logging` plain boolean; `logging/setLevel` handler only registered when true `:548-550`.
- `listChanged` flags → `setupChangeListeners` broadcast `notifications/*/list_changed` to ACTIVE sessions **and** push to `subscriptions/listen` streams `:433-457`.

## 📄 Pagination

`Pagination.paginate(sorted, limit, cursor, key)` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/Pagination.java:28-56`: cursor = key of last item of previous page; unknown cursor ⇒ `cursorValid=false` ⇒ `invalidParams("Invalid cursor")`. Default page size 50 (`:16`), overridable per feature (`FeatureConfig.pageSize`). `resources/templates/list` not paginated (any cursor ⇒ invalid) `ResourceMethodHandlers.java:72-83`.

## 🛠️ `tools/call` specifics

`ToolMethodHandlers.ToolsCallHandler` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/ToolMethodHandlers.java:79-271`:
- Unknown or extension-disabled tool ⇒ `invalidParams("Unknown tool: …")` (hides extension tools).
- Input schema violations ⇒ invalid params with joined errors.
- Emits DEBUG log `tachyon.tools` started/completed (visible only if log level permits) `:267-270`.
- `ToolResult` sealed: `Success | Error | InputRequired | Task` `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/ToolResult.java:19-322`.
- `Success.structuredValue` POJO serialized via `PayloadSerializer` → `JsonDocument`, then output schema validation; violation ⇒ `ToolResult.error(msg)` (tool-level error, not JSON-RPC), outcome `PayloadFailure` `:227-250`.
- Exceptions: `InvalidArgumentException` ⇒ invalid params with message; bare `IllegalArgumentException` ⇒ "Invalid params" (message hidden); else internal error → [[errors]].
- Task path → [[tasks]].

## 📚 Resources specifics

- `resources/read`: invalid URI (blank, >8192, `URISyntaxException`) ⇒ invalid params; exact URI match first, then **most specific template** (longest literal after stripping `{…}`, tie → name) `DefaultResourceRegistry.java:101-111`, `:368-386`; not found ⇒ `RESOURCE_NOT_FOUND` with `{uri}` data.
- Template parsing: RFC 6570-ish `UriTemplate` (`tachyon-api/src/main/java/dev/tachyonmcp/api/server/domain/UriTemplate.java:46`) → `Map<String, UriTemplateValue>`.
- `resources/subscribe|unsubscribe` need session; `notifyResourceUpdated(uri)` → subscribed sessions + `subscriptions/listen` streams; dead session ids pruned lazily `DefaultResourceRegistry.java:401-451`.
- MIME guessing from bundled `mime-types.csv` (`ext,mime,isText`) `.../resources/MimeTypes.java:24-89`.

## 🗣️ Prompts / completions specifics

- `prompts/get`: prompt `inputSchema` (derived from arguments) validated with input validator `PromptMethodHandlers.java:91-100`; result `PromptResult.Messages | InputRequired`.
- `completion/complete`: no handler ⇒ empty result (not error); >100 values truncated + `hasMore=true` `CompletionMethodHandlers.java:34`, `:78-91`.

## 📡 subscriptions/listen (2026-07-28)

`SubscriptionsListenHandler` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/SubscriptionsListenHandler.java:44-92` + `SubscriptionRegistry` `.../features/subscriptions/SubscriptionRegistry.java`:
- Only when mapper `supportsSubscriptionsListen`; `taskIds` filter needs tasks extension.
- `activate` under lock: register + ack `notifications/subscriptions/acknowledged` as **first** event (SEP-2575) `:66-82`.
- Returned future lives whole stream; observation completes early with `StreamEstablished`.
- Disconnect ⇒ remove + cancel; shutdown `closeAll` ⇒ graceful `resultType: complete` result `:166-178`.

## 🧩 Built-in handlers

`registerDefaults` `DefaultTachyonServer.java:540-554`: `initialize`, `server/discover`, `ping`, `subscriptions/listen`, tools/resources/tasks/prompts/completion handlers, optional `logging/setLevel`. `DiscoverHandler` returns supported versions desc + capabilities + identity + advertised extensions `handlers/DiscoverHandler.java:35-46`.

Related: [[tasks]], [[extensions]], [[json-layer]], [[request-lifecycle]].
