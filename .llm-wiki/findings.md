---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-24
commit: 85184ff0
---

# 🔎 Findings

Spotted while reading code. Runtime verification noted per finding. Fixed in code ⇒ 🗑️ remove row.

- ⚠️ Notifications route onto the POST-SSE stream only from the dispatching thread (ThreadLocal). A handler continuing on another thread ⇒ event goes to the GET stream, or is dropped when there is none (stateful) — surprising for async tools. `OutboundSseStreamMessageRouter#currentSessionId`, `McpDispatcher#invokeHandlerAsync`
- ⚠️ `UnsupportedProtocolVersionHandler` encodes the rejection with `ProtocolVersionHandler#LATEST_PROTOCOL` (not `Protocols#baseline`) + HTTP 400, even for legacy-looking clients. Intentional per SEP-2575? `UnsupportedProtocolVersionHandler#channelRead`
- ⚠️ GET-SSE has no byte budget: `Session#send` checks `isWritable` on the caller thread, then `NettySseConnection#send` queues a loop task. Busy/blocked loop ⇒ writability never flips ⇒ loop task queue grows with the producer. Same class as the fixed POST-SSE bug, lower risk. `Session#send`, `NettySseConnection#send`
- ⚠️ POST-SSE budget is per stream (`PostSseStream#reserve`); no global outbound cap or connection limit. N slow streams ⇒ N × 1 MiB.
- ⚠️ Absolute-form request-target (`POST http://host/mcp HTTP/1.1`) ⇒ 404: `EndpointValidatorHandler#channelRead` compares the raw URI. RFC 9112 §3.2.2: servers MUST accept absolute-form. Fails closed. Fix must also check the authority against `Host` and the DNS-rebinding guard, or an authority-less check becomes a bypass.

## 🪶 Polish

- 🪶 `SessionConfig` is a sum modelled as a product: `boolean enabled` × 5 `@Nullable` options, guarded in the compact ctor **and** `Builder#build`. Stateless is a *server* property — no session config ⇒ no sessions. shape: drop `enabled`, make every component non-null, `@Nullable SessionConfig ServerConfig#session()` with `stateless()` derived from `== null`, delete `SessionConfig#sessionStoreOrDefault`/`#sessionEventStoreOrDefault` and `Builder#enabled`/`#enabled(boolean)`. Then the impossible state is unrepresentable in the value, not just the builder.

## ❓ Open questions

- **Q:** Multi-node: event log store (`SessionEventStore`) replay across nodes? - **A:** No
- 2026-07-28 + stateful server: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether the 2025 wire should switch.
