---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-24
commit: 70f05aa6
---

# 🔎 Findings

Spotted while reading code. Runtime verification noted per finding. Fixed in code ⇒ 🗑️ remove row.

- 🐛 **Release blocker:** HTTP/1.1 pipelining order. PR #390 audit reproduced this with a raw socket and two explicitly completed futures: fast B's HTTP response arrives before slow A's, each with its own CORS grant. RFC 9112 §9.3.2 requires request order; JSON-RPC IDs do not repair HTTP response association. The existing delay-based test makes B slower and does not cover this completion order. Fix separately: preserve HTTP response order, including errors and SSE. [McpInitializationHandler#dispatchPreSessionRequest](../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java), [CorsPipeliningTest#eachPipelinedResponseCarriesItsOwnRequestsCorsGrant](../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/CorsPipeliningTest.java).
- 🐛 Empty `Origin` skips admission validation; raw-socket `Origin:` plus a valid ping returns 200. Missing Origin is allowed, but a present empty value is malformed. Remove the `!origin.isEmpty()` exemption. No browser-origin spoofing exploit established. [DnsRebindingProtectionHandler#channelRead](../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/DnsRebindingProtectionHandler.java).
- 🐛 `Expect` errors miss CORS: raw-socket oversized `Expect: 100-continue` returns 413, unsupported expectation returns 417, both without ACAO or Vary even for a listed origin. The override covers `handleOversizedMessage`, while Netty emits these through inherited `newContinueResponse`. Decorate independent final-response headers there using the request's decision. [CorsHttpObjectAggregator#handleOversizedMessage](../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/CorsHttpObjectAggregator.java).
- 🐛 Preflight policy differs from response policy: an unlisted loopback preflight returns 200 without `Vary: Origin`; `HTTPS://App.Example.com:443` also gets no grant despite matching canonical `https://app.example.com` on normal responses. Both reproduced over a raw socket. Netty handles preflight with exact origin matching; `CorsDecision` handles application responses with canonical matching and Vary on misses. Unify those decisions while retaining synchronous preflight handling. [TachyonCorsHandler#TachyonCorsHandler](../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/TachyonCorsHandler.java), [CorsDecision#of](../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/CorsDecision.java).
- 🪶 Public `allowNullOrigin` is removed, but a null-grant branch and its test remain. The installed DNS guard rejects `null` before either can grant it; this is contradictory internal policy, not a demonstrated default-pipeline bypass. [CorsDecision#of](../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/CorsDecision.java), [CorsDecisionTest#nullOriginFollowsTheConfig](../tachyon-core/src/test/java/dev/tachyonmcp/core/transport/netty/http/CorsDecisionTest.java).
- ⚠️ Notifications route onto the POST-SSE stream only from the dispatching thread (ThreadLocal). A handler continuing on another thread ⇒ event goes to the GET stream, or is dropped when there is none (stateful) — surprising for async tools. `OutboundSseStreamMessageRouter#currentSessionId`, `McpDispatcher#invokeHandlerAsync`
- ⚠️ `UnsupportedProtocolVersionHandler` encodes the rejection with `ProtocolVersionHandler#LATEST_PROTOCOL` (not `Protocols#baseline`) + HTTP 400, even for legacy-looking clients. Intentional per SEP-2575? `UnsupportedProtocolVersionHandler#channelRead`
- ⚠️ Absolute-form request-target (`POST http://host/mcp HTTP/1.1`) ⇒ 404: `EndpointValidatorHandler#channelRead` compares the raw URI. RFC 9112 §3.2.2: servers MUST accept absolute-form. Fails closed. Fix must also check the authority against `Host` and the DNS-rebinding guard, or an authority-less check becomes a bypass.

## 🪶 Polish

- 🪶 `SessionConfig` is a sum modelled as a product: `boolean enabled` × 5 `@Nullable` options, guarded in the compact ctor **and** `Builder#build`. Stateless is a *server* property — no session config ⇒ no sessions. shape: drop `enabled`, make every component non-null, `@Nullable SessionConfig ServerConfig#session()` with `stateless()` derived from `== null`, delete `SessionConfig#sessionStoreOrDefault`/`#sessionEventStoreOrDefault` and `Builder#enabled`/`#enabled(boolean)`. Then the impossible state is unrepresentable in the value, not just the builder.

## ❓ Open questions

- **Q:** Multi-node: event log store (`SessionEventStore`) replay across nodes? - **A:** No
- 2026-07-28 + stateful server: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether the 2025 wire should switch.
