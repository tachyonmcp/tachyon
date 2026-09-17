---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-17
commit: e5c536ea
---

# 🔎 Findings

Spotted while reading code. Not verified by tests. Fixed in code ⇒ 🗑️ remove row.

- ⚠️ Notifications route onto the POST-SSE stream only from the dispatching thread (ThreadLocal). A handler continuing on another thread ⇒ event goes to the GET stream, or is dropped when there is none (stateful) — surprising for async tools. `OutboundSseStreamMessageRouter#currentSessionId`, `McpDispatcher#invokeHandlerAsync`
- ⚠️ Enum auto-completion registers through last-write-wins `DefaultCompletionRegistry` maps: an explicit `@McpCompletion` from **another** service registered before the enum prompt/template is silently replaced. Same-service explicit completion is honored (`completed` guard). `TachyonAnnotationProvider#registerEnumCompletion`, `DefaultCompletionRegistry#registerForPromptAsync`
- ⚠️ `UnsupportedProtocolVersionHandler` encodes the rejection with `ProtocolVersionHandler#LATEST_PROTOCOL` (not `Protocols#baseline`) + HTTP 400, even for legacy-looking clients. Intentional per SEP-2575? `UnsupportedProtocolVersionHandler#channelRead`
- 🪶 `HandlerFutures` is `@InternalApi` but lives in public `tachyon-api`, is statically imported by user-facing `AbstractToolHandler`, and is shown to users in `RpcMethodHandler`'s javadoc example. `HandlerFutures`, `RpcMethodHandler`
- 🪶 In-memory stores defaulted in two places: `SessionConfig.Builder#build` (enabled sessions — asserted non-null by `SessionConfigTest`) and `DefaultServerBuilder#build` (needed because `SessionConfig#STATELESS` carries nulls). Same `new InMemory…Store()` literal twice.

## ❓ Open questions

- Multi-node: event log store (`SessionEventStore`) replay across nodes? Only `SessionStore` has CAS/generation fencing; in-memory event log is per process.
- 2026-07-28 + `session.enabled(true)`: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether the 2025 wire should switch.
