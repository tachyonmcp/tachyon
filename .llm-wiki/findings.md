---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-15
commit: 751331f4
---

# 🔎 Findings

Spotted while reading code. Not verified by tests. Fixed in code ⇒ 🗑️ remove row.

| # | Kind | Finding | Proof |
|---|---|---|---|
| 1 | 🪶 | `McpDispatcher` javadoc says it "binds the v2025_11_25 models/codecs… move behind McpDialect when second version wired" — second version already wired via mappers. Stale. | `McpDispatcher` |
| 2 | ⚠️ | `ServerEngine.responseMapper()` / `DefaultTachyonServer.responseMapper()` always returns the 2025-11-25 mapper (imports `v2025_11_25.McpProtocol.VERSION`); used as fallback for task/progress notifications when session has no protocol. | `DefaultTachyonServer#RESPONSE_MAPPERS`, `DefaultTachyonServer#notifyTaskStatus` |
| 3 | ⚠️ | `broadcastLog` encodes with `Protocols.list().getFirst()` mapper for **all** sessions — relies on ServiceLoader file order (2025 first). | `DefaultTachyonServer#broadcastLog` |
| 4 | ⚠️ | `McpDispatcher.dispatchContext(null)` / `DefaultDispatchContext.stateless` also use `Protocols.list().getFirst()`. | `McpDispatcher#dispatchContext`, `DefaultDispatchContext#stateless` |
| 5 | 🪶 | Channel logger name still legacy `me.kpavlov.tachyon.transport.netty.channel`. | `McpChannelInitializer#CHANNEL_LOGGER_NAME` |
| 6 | 🪶 | `DefaultTaskRegistry.onChange` **replaces** single listener; other registries append via `ChangeSupport`. | `DefaultTaskRegistry#onChange` |
| 7 | 🪶 | `Session` javadoc typo "unique string ID1". | `Session` |
| 8 | ⚠️ | `ObservationListener` marked `@InternalApi` + `@Experimental`, yet public extension point used by `tachyon-opentelemetry` and documented in `ObservabilityConfig.Builder.listener`. Contract unclear. | `ObservationListener`, `Builder#slowRequestThreshold` |
| 9 | 🪶 | In-memory stores defaulted twice: `SessionConfig.Builder.build()` and `DefaultServerBuilder.build()`. | `Builder#build`, `DefaultServerBuilder#build` |
| 10 | ⚠️ | Notifications route onto POST-SSE only from the dispatching thread (ThreadLocal). Handler continuing on another thread ⇒ event goes to GET stream / dropped when none (stateful) — surprising for async tools. | `OutboundSseStreamMessageRouter#logger`, `McpDispatcher#invokeHandlerAsync` |
| 11 | ⚠️ | `DefaultResourceRegistry.unregister(name)` / `find(name)` pick arbitrary match when names collide (documented). | `DefaultResourceRegistry` |
| 12 | 🪶 | `ResourceTemplateEntry` is public record while sibling entries are package-private. | `ResourceTemplateEntry` |
| 13 | ⚠️ | `UnsupportedProtocolVersionHandler` errors use latest protocol mapper + HTTP 400 even for legacy-looking clients. Intentional per SEP-2575? | `UnsupportedProtocolVersionHandler#channelRead` |
| 14 | 🪶 | `HandlerFutures` is `@InternalApi` but lives in public `tachyon-api` and is referenced by user-facing `AbstractToolHandler`. | `HandlerFutures` |
| 15 | 🪶 | `TachyonServerCustomizer` javadoc says discovered beans are applied before customizers. Annotated beans now register after server construction. | [TachyonServerCustomizer](../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonServerCustomizer.java), [TachyonBeanRegistrar#afterSingletonsInstantiated](../integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrar.java) |
| 16 | ⚠️ | Enum auto-completion registers through last-write-wins `DefaultCompletionRegistry` maps: explicit `@McpCompletion` from another service registered **before** the enum prompt/template is silently replaced. Same-service explicit completion is honored. | `TachyonAnnotationProvider#registerEnumCompletion`, `DefaultCompletionRegistry#registerForPromptAsync` |

## ❓ Open questions

- Multi-node: event log store (`SessionEventStore`) replay across nodes? Only `SessionStore` has CAS/generation fencing; in-memory event log is per process.
- 2026-07-28 + `session.enabled(true)`: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- 🐛 testkit `JsonRpcErrorAssert.hasHttpStatusCode` failure message has 3 `%s`, passes 2 args ⇒ `MissingFormatArgumentException` instead of assertion error; `isMethodNotFound()` always demands HTTP 404, so unusable on 2025-11-25 (HTTP 200) `JsonRpcErrorAssert#hasHttpStatusCode`, `JsonRpcErrorAssert#isMethodNotFound`.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper.java`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether 2025 wire should switch.
