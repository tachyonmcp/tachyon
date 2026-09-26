---
title: Feature registries
tags: [concept, tools, resources, prompts, completions]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java]
updated: 2026-09-25
commit: cbfbcd7f
---

# 🧰 Feature registries

Verdict: each MCP feature = public façade in `tachyon-api` (`Tools`, `Resources`, `Prompts`, `Completions`, `Tasks`) + `Default*Registry` in core + static `*MethodHandlers.register(map, …)` JSON-RPC adapters. Sync fn wrapped into async fn at registration; dispatch always async. Registries are live — register/unregister after `start()` fires `list_changed`.

## 📋 Registration API shape

`TachyonServer.annotations(...)` (see [[declarative-configuration]]) feeds annotated objects into these same registries with configured codecs, after construction ([DefaultTachyonServer#annotations](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java)). Used by Spring after singleton initialization; [[spring-boot]].

Rule set lives in `docs/architecture/guidance.md` (AGENTS.md mandates reading it before changing SAMs/registry names). Observed in code:

| Façade | Sync SAM | Async SAM | Extras | Proof |
|---|---|---|---|---|
| [Tools](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/Tools.java) | [ToolFn](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/ToolFn.java) (throws) | [AsyncToolFn](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/AsyncToolFn.java) | typed `register(Class<I>,Class<O>, …, TypedToolFn)` auto-generates schemas via `JsonSchema.generate` | [Tools](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/Tools.java) |
| [Resources](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/resources/Resources.java) | [ResourceFn](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/resources/ResourceFn.java) | [AsyncResourceFn](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/resources/AsyncResourceFn.java) | templates, `unregisterByUri`, `findByUri`, `notifyResourceUpdated` | [Resources](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/resources/Resources.java) |
| [Prompts](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/prompts/Prompts.java) | [PromptFn](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/prompts/PromptFn.java) | [AsyncPromptFn](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/prompts/AsyncPromptFn.java) | static `List<PromptMessage>` overload | [Prompts](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/prompts/Prompts.java) |
| [Completions](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/completions/Completions.java) | [CompletionFn](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/completions/CompletionFn.java) | [AsyncCompletionFn](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/completions/AsyncCompletionFn.java) | keyed by prompt name or uri/template | [Completions](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/completions/Completions.java) |

All: `register(Descriptor, fn)` + `register(Consumer<Descriptor.Builder>, fn)`, `unregister(name)`, `find(name)`, `descriptors()` (name-sorted).

Sync adapters assert VT: `HandlerFutures.assumeVirtualThread()` (Java `assert`) e.g. [DefaultToolRegistry#register](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/DefaultToolRegistry.java), `AbstractToolHandler.handle` guardrail [AbstractToolHandler#handle](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/AbstractToolHandler.java).

## 🗃️ Registries

| Registry | Storage | Notes | Proof |
|---|---|---|---|
| `AbstractRegistry<D,R>` | `ConcurrentHashMap` by name + `ChangeSupport` | `addItem` replaces; `list(limit,cursor,filter)` name-sorted | `AbstractRegistry` |
| `DefaultToolRegistry` | extends above | name `[a-zA-Z0-9_\-./]+` ≤64 (SEP-986); input schema root `type: object`; output schema object; `x-mcp-header` rules; desc > 2048 warn | `DefaultToolRegistry` |
| `DefaultPromptRegistry` | extends above | | `DefaultPromptRegistry#registerAsync` |
| `DefaultResourceRegistry` | immutable `Index(byUri)` swapped under `ReentrantLock` (volatile read) + templates CHM + subscriptions CHM | **URI = identity**; same URI other name ⇒ IAE; cursor key = b64(name)+b64(uri); template dup name ⇒ IAE | `Index` |
| `DefaultCompletionRegistry` | 2 CHMs | no change events | `DefaultCompletionRegistry` |

Mode `OFF` ⇒ registration silently skipped (debug log) in every registry.

## 🎚️ Capability resolution

`DefaultTachyonServer.resolveCapabilities` `DefaultTachyonServer`:
- `Mode.ON` ⇒ advertise; `OFF` ⇒ no; `AUTO` ⇒ advertise iff registry non-empty (computed **per request**, so post-start registrations show up).
- `tasks` advertised if enabled **or** any tool `taskSupport ∉ {null, FORBIDDEN}`.
- `logging` plain boolean; `logging/setLevel` handler only registered when true `DefaultTachyonServer#registerDefaults`.
- `listChanged` flags → `setupChangeListeners` broadcast `notifications/*/list_changed` to ACTIVE sessions **and** push to `subscriptions/listen` streams `DefaultTachyonServer#host`.

## 📄 Pagination

`Pagination.paginate(sorted, limit, cursor, key)` `Pagination#paginate`: cursor = key of last item of previous page; unknown cursor ⇒ `cursorValid=false` ⇒ `invalidParams("Invalid cursor")`. Default page size 50 (`Pagination#DEFAULT_PAGE_SIZE`), overridable per feature (`FeatureConfig.pageSize`). `resources/templates/list` not paginated (any cursor ⇒ invalid) `ResourcesTemplatesListHandler#handle`.

## 🛠️ `tools/call` specifics

`ToolMethodHandlers.ToolsCallHandler` `ToolsCallHandler`:
- Unknown or extension-disabled tool ⇒ `invalidParams("Unknown tool: …")` (hides extension tools).
- Input schema violations ⇒ invalid params with joined errors.
- Emits DEBUG log `tachyon.tools` started/completed (visible only if log level permits) `ToolsCallHandler#sendLogging`.
- `ToolResult` sealed: `Success | Error | InputRequired | Task` `ToolResult`.
- `Success.structuredValue` POJO serialized via `PayloadSerializer` → `JsonDocument`, then output schema validation; violation ⇒ `ToolResult.error(msg)` (tool-level error, not JSON-RPC), outcome `PayloadFailure` `OperationOutcome`.
- Exceptions: `InvalidArgumentException` ⇒ invalid params with message; bare `IllegalArgumentException` ⇒ "Invalid params" (message hidden); else internal error → [[errors]].
- Task path → [[tasks]].

## 📚 Resources specifics

- `resources/read`: invalid URI (blank, >8192, `URISyntaxException`) ⇒ invalid params; exact URI match first, then **most specific template** (longest literal after stripping `{…}`, tie → name) `DefaultResourceRegistry#isValidResourceUri`, `DefaultResourceRegistry#matchTemplate`; not found ⇒ `RESOURCE_NOT_FOUND` with `{uri}` data.
- Registry entries carry private-cache isolation from metadata-sensitive annotated handlers into response mapping; ordinary handlers keep the public default ([DefaultResourceRegistry#privateCaching](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/resources/DefaultResourceRegistry.java), [ResourceMethodHandlers#readResult](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/resources/ResourceMethodHandlers.java)).
- Template parsing: RFC 6570-ish `UriTemplate` (`UriTemplate#create`) → `Map<String, UriTemplateValue>`.
- `resources/subscribe|unsubscribe` need session; `notifyResourceUpdated(uri)` → subscribed sessions + `subscriptions/listen` streams; dead session ids pruned lazily `DefaultResourceRegistry`.
- MIME guessing from bundled `mime-types.csv` (`ext,mime,isText`) `MimeTypes`.

## 🗣️ Prompts / completions specifics

- `prompts/get`: prompt `inputSchema` (derived from arguments) validated with input validator `PromptsGetHandler#handleAsync`; result `PromptResult.Messages | InputRequired`.
- `completion/complete`: no handler ⇒ empty result (not error); >100 values truncated + `hasMore=true` `CompletionCompleteHandler#MAX_VALUES`, `CompletionCompleteHandler#handleAsync`.
- Completion registration is last-write-wins per target (`DefaultCompletionRegistry#registerForPromptAsync`). Derived handlers (enum auto-completion) instead go through the `@InternalApi` `CompletionRegistry#registerForPromptIfAbsent` / `#registerForResourceIfAbsent`, so an explicit handler is never clobbered. Public `Completions` deliberately has no if-absent method — a registration context carrying a caller's own `Completions` cannot take a derived handler and fails fast — see [[declarative-configuration]].

## 📡 subscriptions/listen (2026-07-28)

`SubscriptionsListenHandler` `SubscriptionsListenHandler` + `SubscriptionRegistry` `.../features/subscriptions/SubscriptionRegistry.java`:
- Only when mapper `supportsSubscriptionsListen`; `taskIds` filter needs tasks extension.
- Task route (session) delivery happens alongside subscription fan-out; see [[tasks]] and [DefaultTachyonServer#notifyTaskStatus](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).
- `activate` under lock: register + ack `notifications/subscriptions/acknowledged` as **first** event (SEP-2575) `SubscriptionRegistry#activate`.
- Returned future and Observation both span the whole stream lifetime; completes on disconnect (`Cancelled`) or genuine transport failure (`StreamFailed(causeType, cause?)`), never early at establishment `SubscriptionsListenHandler#handleAsync`.
- Terminal continuation threading and ack timestamp publication: [[observability]].
- Disconnect ⇒ remove + cancel; shutdown `closeAll` ⇒ graceful `resultType: complete` result `SubscriptionRegistry#closeAll`.
- Fan-out writes via `offerEvent` (never blocks): slow subscriber disconnected, others not held under the lock `SubscriptionRegistry#push`, [[sse-streams]].

## 🧩 Built-in handlers

`registerDefaults` `DefaultTachyonServer#registerDefaults`: `initialize`, `server/discover`, `ping`, `subscriptions/listen`, tools/resources/tasks/prompts/completion handlers, optional `logging/setLevel`. `DiscoverHandler` returns supported versions desc + capabilities + identity + advertised extensions `DiscoverHandler#handle`.

Related: [[tasks]], [[extensions]], [[json-layer]], [[request-lifecycle]].
