---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-13
commit: 5821ad56
---

# 🔎 Findings

Spotted while reading code. Not verified by tests. Fixed in code ⇒ 🗑️ remove row.

| # | Kind | Finding | Proof |
|---|---|---|---|
| 1 | 🪶 | `McpDispatcher` javadoc says it "binds the v2025_11_25 models/codecs… move behind McpDialect when second version wired" — second version already wired via mappers. Stale. | `tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java:57-59` |
| 2 | ⚠️ | `ServerEngine.responseMapper()` / `DefaultTachyonServer.responseMapper()` always returns the 2025-11-25 mapper (imports `v2025_11_25.McpProtocol.VERSION`); used as fallback for task/progress notifications when session has no protocol. | `DefaultTachyonServer.java:153-161`, `:515-531` |
| 3 | ⚠️ | `broadcastLog` encodes with `Protocols.list().getFirst()` mapper for **all** sessions — relies on ServiceLoader file order (2025 first). | `DefaultTachyonServer.java:727` |
| 4 | ⚠️ | `McpDispatcher.dispatchContext(null)` / `DefaultDispatchContext.stateless` also use `Protocols.list().getFirst()`. | `McpDispatcher.java:107-112`, `DefaultDispatchContext.java:56-58` |
| 5 | 🪶 | Channel logger name still legacy `me.kpavlov.tachyon.transport.netty.channel`. | `McpChannelInitializer.java:52` |
| 6 | 🪶 | `DefaultTaskRegistry.onChange` **replaces** single listener; other registries append via `ChangeSupport`. | `DefaultTaskRegistry.java:151-153` |
| 7 | 🪶 | `Session` javadoc typo "unique string ID1". | `tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java:19-23` |
| 8 | ⚠️ | `ObservationListener` marked `@InternalApi` + `@Experimental`, yet public extension point used by `tachyon-opentelemetry` and documented in `ObservabilityConfig.Builder.listener`. Contract unclear. | `server/observability/ObservationListener.java:20-22`, `ObservabilityConfig.java:84-92` |
| 9 | 🪶 | In-memory stores defaulted twice: `SessionConfig.Builder.build()` and `DefaultServerBuilder.build()`. | `SessionConfig.java:140-146`, `DefaultServerBuilder.java:304-306` |
| 10 | ⚠️ | Notifications route onto POST-SSE only from the dispatching thread (ThreadLocal). Handler continuing on another thread ⇒ event goes to GET stream / dropped when none (stateful) — surprising for async tools. | `OutboundSseStreamMessageRouter.java:20-59`, `McpDispatcher.java:385-393` |
| 11 | ⚠️ | `DefaultResourceRegistry.unregister(name)` / `find(name)` pick arbitrary match when names collide (documented). | `DefaultResourceRegistry.java:170-214` |
| 12 | 🪶 | `ResourceTemplateEntry` is public record while sibling entries are package-private. | `features/resources/ResourceTemplateEntry.java:11` |
| 13 | ⚠️ | `UnsupportedProtocolVersionHandler` errors use latest protocol mapper + HTTP 400 even for legacy-looking clients. Intentional per SEP-2575? | `UnsupportedProtocolVersionHandler.java:40-47` |
| 14 | 🪶 | `HandlerFutures` is `@InternalApi` but lives in public `tachyon-api` and is referenced by user-facing `AbstractToolHandler`. | `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java:17` |

## ❓ Open questions

- Multi-node: event log store (`SessionEventStore`) replay across nodes? Only `SessionStore` has CAS/generation fencing; in-memory event log is per process.
- 2026-07-28 + `session.enabled(true)`: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
