---
title: Security guards
tags: [concept, security, transport]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpChannelInitializer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/NetworkConfig.java]
updated: 2026-09-16
commit: b3a97d16
---

# 🛡️ Security guards

Verdict: fail-closed HTTP guards, most of them before body aggregation. Loopback-only by default; `allowedHosts` widens **Host** for non-browser clients but never widens **Origin**.

⚠️ A guard that needs the body cannot live pre-aggregation. The header guard rejects only what a *duplicate field line* proves (no body needed) — comparing a mirror's **value** to the body is a separate, post-aggregation guard. Conflating the two once cost every `mcp-remote` client its connection: the preflight mirrors `Mcp-Method: initialize` with no `MCP-Protocol-Version` (SEP-2243's own canonical example), and a pre-aggregation proxy-check answered it 400.

| Guard | Rule | Proof |
|---|---|---|
| DNS rebinding | `Host` must be `localhost`, `localhost.`, `127.0.0.1`, `[::1]` (any port) or allowlisted; missing Host on HTTP/1.1 ⇒ 403; multiple Host/Origin ⇒ 403; `Origin` present ⇒ must be loopback; `Origin: null` ⇒ 403 | `DnsRebindingProtectionHandler` |
| allowedHosts parse | trimmed, lowercase, rejects whitespace/control/`/ @ ? #` | `DnsRebindingProtectionHandler#DnsRebindingProtectionHandler`, `DnsRebindingProtectionHandler#validateHostEntry` |
| Endpoint | normalized path ≠ endpoint ⇒ 404 | `EndpointValidatorHandler#channelRead` |
| Header guard | dup `MCP-Protocol-Version`/`MCP-Session-Id`/`Last-Event-ID`/`Mcp-Method`/`Mcp-Name`/`Mcp-Param-*` ⇒ 400 (even identical values; case variants collapse); header name not echoed | `McpHeaderGuardHandler#isSingleton` |
| Accept | POST needs both `application/json` and `text/event-stream` (wildcards ok, `q=0` rejects) ; GET needs `text/event-stream` ⇒ 406 | `AcceptValidationHandler` |
| Stateless guard | session/Last-Event-ID headers ⇒ 404; DELETE ⇒ 405 | `StatelessValidatorHandler#channelRead` |
| Body limit | 1 MB default, 413 | `McpChannelInitializer#DEFAULT_MAX_CONTENT_LENGTH`, `McpChannelInitializer#initChannel` |
| CORS | Netty `CorsHandler` from `allowedOrigins/allowNullOrigin/allowPrivateNetworks/allowedHeaders` | `NettyServerConfig.buildCorsConfig`, `McpChannelInitializer#initChannel` |
| Body/header agreement (**all** versions) | SEP-2243 mirror present ⇒ must match body, whichever version negotiated — a gateway must not route on a header the server never executes | `McpMirrorValidationHandler`, [[protocol-versions]] |
| Mirror **required** (2026-07-28 only) | the revision that adopted SEP-2243 also demands the mirrors be present | `RequestValidationHandler#requireMirrors` |
| Pending-request ownership | client response must come from owning session (stateful) / channel (stateless) | `DefaultTachyonServer#failPendingRequest` |
| Error message hygiene | bare IAE message hidden; client-controlled values not echoed in header errors | [[errors]] |
| SSE comment injection | CR/LF flattened | `PostSseStream#doWriteComment` |

Rejection path: `rejectAndClose` marks channel rejected (drops remaining chunks) + `Connection: close` `ChannelHandlerUtils#rejectAndClose`.

Tests: `DnsRebindingProtectionHandlerTest`, `McpHeaderGuardHandlerTest`, `McpMirrorValidationHandlerTest`, `EndpointValidatorHandlerTest`, e2e `DnsRebindingTest`, `AcceptHeaderValidationTest`, `MaxContentLengthTest`, `v2026_07_28/HeaderValidationTest`, `CustomHeaderValidationTest`.

Related: [[netty-pipeline]], [[errors]].
