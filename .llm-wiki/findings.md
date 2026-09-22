---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-22
commit: 58f386e8
---

# 🔎 Findings

Spotted while reading code. Not verified by tests. Fixed in code ⇒ 🗑️ remove row.

- ⚠️ Notifications route onto the POST-SSE stream only from the dispatching thread (ThreadLocal). A handler continuing on another thread ⇒ event goes to the GET stream, or is dropped when there is none (stateful) — surprising for async tools. `OutboundSseStreamMessageRouter#currentSessionId`, `McpDispatcher#invokeHandlerAsync`
- ⚠️ `UnsupportedProtocolVersionHandler` encodes the rejection with `ProtocolVersionHandler#LATEST_PROTOCOL` (not `Protocols#baseline`) + HTTP 400, even for legacy-looking clients. Intentional per SEP-2575? `UnsupportedProtocolVersionHandler#channelRead`
- ⚠️ `examples/weather-mcp-spring-boot` asserts the new starter timer `mcp.server.operation.duration` (README, `WeatherApplicationTest`) but pins the released `tachyon-bom:1.0.0-beta.28`, which still emits `mcp.server.operations`. Green only under `examples-snapshot` (`-Dtachyon.version=1.0.0-SNAPSHOT`); the published-release `examples.yml` job fails for it until the pin moves to the release carrying the rename. Bump `tachyon.version` in the release docs commit, then 🗑️ this row.
- 🪶 A declared extension call missing its `_meta.<extensionId>` envelope gets `-32602 "Missing required client capability: <id>"`. The message names the wrong problem: the client did declare the capability, and the missing piece is the envelope, which is also a different case from the real `-32021` Missing Required Client Capability. `McpDispatcher#extensionNegotiationRejection`
- 🐛 Raw extension methods (`ExtensionContext#registerHandler`) map **every** handler exception to `-32603 Internal error`, HTTP 200 — including `InvalidArgumentException`, whose javadoc promises invalid-params, and bare `IllegalArgumentException`. Tools, prompts, resources, and completions route failures through `ServerErrors#fromUnhandledException` (⇒ `-32602`); the raw-handler path in `McpDispatcher#handleHandlerError` uses `ServerErrors.internalError("Internal error")` instead. Seen with curl against a snapshot build. `DefaultTachyonServer#registerHandler`, `McpDispatcher#handleHandlerError`
- 🪶 `HandlerFutures` is `@InternalApi` but lives in public `tachyon-api`, is statically imported by user-facing `AbstractToolHandler`, and is shown to users in `RpcMethodHandler`'s javadoc example. `HandlerFutures`, `RpcMethodHandler`

## 🪶 Polish

- 🪶 `SessionConfig` is a sum modelled as a product: `boolean enabled` × 5 `@Nullable` options, guarded in the compact ctor **and** `Builder#build`. Stateless is a *server* property — no session config ⇒ no sessions. shape: drop `enabled`, make every component non-null, `@Nullable SessionConfig ServerConfig#session()` with `stateless()` derived from `== null`, delete `SessionConfig#sessionStoreOrDefault`/`#sessionEventStoreOrDefault` and `Builder#enabled`/`#enabled(boolean)`. Then the impossible state is unrepresentable in the value, not just the builder.

## ❓ Open questions

- **Q:** Multi-node: event log store (`SessionEventStore`) replay across nodes? - **A:** No
- 2026-07-28 + stateful server: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether the 2025 wire should switch.
