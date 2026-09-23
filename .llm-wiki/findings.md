---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-23
commit: bf825914
---

# 🔎 Findings

Spotted while reading code. Not verified by tests. Fixed in code ⇒ 🗑️ remove row.

- ⚠️ Notifications route onto the POST-SSE stream only from the dispatching thread (ThreadLocal). A handler continuing on another thread ⇒ event goes to the GET stream, or is dropped when there is none (stateful) — surprising for async tools. `OutboundSseStreamMessageRouter#currentSessionId`, `McpDispatcher#invokeHandlerAsync`
- ⚠️ `UnsupportedProtocolVersionHandler` encodes the rejection with `ProtocolVersionHandler#LATEST_PROTOCOL` (not `Protocols#baseline`) + HTTP 400, even for legacy-looking clients. Intentional per SEP-2575? `UnsupportedProtocolVersionHandler#channelRead`
- 🐛 dns-rebinding is installed before cors, and DnsRebindingProtectionHandler rejects every non-loopback Origin (and Origin: null`) with 403 without consulting `allowedOrigins`/`allowNullOrigin. So those two options cannot admit a remote browser origin; allowedOrigins("https://app.example.com") still gets 403 on POST and preflight. Decide: let the guard honour the CORS allowlist, or drop the options. McpChannelInitializer#initChannel, DnsRebindingProtectionHandler#channelRead, NettyServerConfig#buildCorsConfig  
- 🐛 Tachyon's own writers echo the request `Origin` into `Access-Control-Allow-Origin` without `Vary: Origin` (`McpResponseWriter#sendJsonResponse`, `ChannelHandlerUtils#sendAcceptedAsync`, `HttpHelpers#setSseStreamHeaders`). Netty `CorsHandler` overwrites it only for origins it grants, so with an `allowedOrigins` list an admitted loopback origin outside the list still gets its origin echoed — the list does not narrow CORS. Fix: let `CorsHandler` own CORS headers (null `corsConfig` ⇒ defaults) and drop the echo.
- 🐛 `AcceptValidationHandler#channelRead` and `ProtocolVersionHandler` gate on `req.uri().startsWith(endpointPath)` with the raw configured path, while `EndpointValidatorHandler` compares normalized paths. `endpointPath("/mcp/")` + `POST /mcp` ⇒ served, but Accept (406) and protocol-version resolution skipped. Fix: normalize `endpointPath` once (or drop the path checks; `mcp-endpoint` already 404s other paths).

## 🪶 Polish

- 🪶 `SessionConfig` is a sum modelled as a product: `boolean enabled` × 5 `@Nullable` options, guarded in the compact ctor **and** `Builder#build`. Stateless is a *server* property — no session config ⇒ no sessions. shape: drop `enabled`, make every component non-null, `@Nullable SessionConfig ServerConfig#session()` with `stateless()` derived from `== null`, delete `SessionConfig#sessionStoreOrDefault`/`#sessionEventStoreOrDefault` and `Builder#enabled`/`#enabled(boolean)`. Then the impossible state is unrepresentable in the value, not just the builder.

## ❓ Open questions

- **Q:** Multi-node: event log store (`SessionEventStore`) replay across nodes? - **A:** No
- 2026-07-28 + stateful server: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether the 2025 wire should switch.
