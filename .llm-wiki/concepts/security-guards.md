---
title: Security guards
tags: [concept, security, transport]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpChannelInitializer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/NetworkConfig.java]
updated: 2026-09-14
commit: 582f9c52
---

# 🛡️ Security guards

Verdict: fail-closed HTTP guards before body aggregation. Loopback-only by default; `allowedHosts` widens **Host** for non-browser clients but never widens **Origin**.

| Guard | Rule | Proof |
|---|---|---|
| DNS rebinding | `Host` must be `localhost`, `localhost.`, `127.0.0.1`, `[::1]` (any port) or allowlisted; missing Host on HTTP/1.1 ⇒ 403; multiple Host/Origin ⇒ 403; `Origin` present ⇒ must be loopback; `Origin: null` ⇒ 403 | `DnsRebindingProtectionHandler` |
| allowedHosts parse | trimmed, lowercase, rejects whitespace/control/`/ @ ? #` | `DnsRebindingProtectionHandler#DnsRebindingProtectionHandler`, `DnsRebindingProtectionHandler#validateHostEntry` |
| Endpoint | normalized path ≠ endpoint ⇒ 404 | `EndpointValidatorHandler#channelRead` |
| Header guard | dup `MCP-Protocol-Version`/`MCP-Session-Id`/`Last-Event-ID`/`Mcp-Method`/`Mcp-Name`/`Mcp-Param-*` ⇒ 400 (even identical values; case variants collapse); mirrors on non-2026 (incl. absent version) ⇒ 400; unsupported version left to -32022 handler; header name not echoed | `McpHeaderGuardHandler` |
| Accept | POST needs both `application/json` and `text/event-stream` (wildcards ok, `q=0` rejects) ; GET needs `text/event-stream` ⇒ 406 | `AcceptValidationHandler` |
| Stateless guard | session/Last-Event-ID headers ⇒ 404; DELETE ⇒ 405 | `StatelessValidatorHandler#channelRead` |
| Body limit | 1 MB default, 413 | `McpChannelInitializer#DEFAULT_MAX_CONTENT_LENGTH`, `McpChannelInitializer#initChannel` |
| CORS | Netty `CorsHandler` from `allowedOrigins/allowNullOrigin/allowPrivateNetworks/allowedHeaders` | `NettyServerConfig.buildCorsConfig`, `McpChannelInitializer#initChannel` |
| Body/header agreement (2026) | SEP-2243 mirrors validated vs body | [[protocol-versions]] |
| Pending-request ownership | client response must come from owning session (stateful) / channel (stateless) | `DefaultTachyonServer#failPendingRequest` |
| Error message hygiene | bare IAE message hidden; client-controlled values not echoed in header errors | [[errors]] |
| SSE comment injection | CR/LF flattened | `PostSseStream#doWriteComment` |

Rejection path: `rejectAndClose` marks channel rejected (drops remaining chunks) + `Connection: close` `ChannelHandlerUtils#rejectAndClose`.

Tests: `DnsRebindingProtectionHandlerTest`, `McpHeaderGuardHandlerTest`, `EndpointValidatorHandlerTest`, e2e `DnsRebindingTest`, `AcceptHeaderValidationTest`, `MaxContentLengthTest`, `v2026_07_28/HeaderValidationTest`, `CustomHeaderValidationTest`.

Related: [[netty-pipeline]], [[errors]].
