---
title: Security guards
tags: [concept, security, transport]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpChannelInitializer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/NetworkConfig.java]
updated: 2026-09-13
commit: 582f9c52
---

# 🛡️ Security guards

Verdict: fail-closed HTTP guards before body aggregation. Loopback-only by default; `allowedHosts` widens **Host** for non-browser clients but never widens **Origin**.

| Guard | Rule | Proof |
|---|---|---|
| DNS rebinding | `Host` must be `localhost`, `localhost.`, `127.0.0.1`, `[::1]` (any port) or allowlisted; missing Host on HTTP/1.1 ⇒ 403; multiple Host/Origin ⇒ 403; `Origin` present ⇒ must be loopback; `Origin: null` ⇒ 403 | `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/DnsRebindingProtectionHandler.java:77-151` |
| allowedHosts parse | trimmed, lowercase, rejects whitespace/control/`/ @ ? #` | `:66-74`, `:180-193` |
| Endpoint | normalized path ≠ endpoint ⇒ 404 | `http/EndpointValidatorHandler.java:30-47` |
| Header guard | dup `MCP-Protocol-Version`/`MCP-Session-Id`/`Last-Event-ID`/`Mcp-Method`/`Mcp-Name`/`Mcp-Param-*` ⇒ 400 (even identical values; case variants collapse); mirrors on non-2026 (incl. absent version) ⇒ 400; unsupported version left to -32022 handler; header name not echoed | `http/McpHeaderGuardHandler.java:71-130` |
| Accept | POST needs both `application/json` and `text/event-stream` (wildcards ok, `q=0` rejects) ; GET needs `text/event-stream` ⇒ 406 | `http/AcceptValidationHandler.java:44-108` |
| Stateless guard | session/Last-Event-ID headers ⇒ 404; DELETE ⇒ 405 | `http/StatelessValidatorHandler.java:21-39` |
| Body limit | 1 MB default, 413 | `McpChannelInitializer.java:50`, `:167` |
| CORS | Netty `CorsHandler` from `allowedOrigins/allowNullOrigin/allowPrivateNetworks/allowedHeaders` | `NettyServerConfig.buildCorsConfig`, `McpChannelInitializer.java:150` |
| Body/header agreement (2026) | SEP-2243 mirrors validated vs body | [[protocol-versions]] |
| Pending-request ownership | client response must come from owning session (stateful) / channel (stateless) | `DefaultTachyonServer.java:875-898` |
| Error message hygiene | bare IAE message hidden; client-controlled values not echoed in header errors | [[errors]] |
| SSE comment injection | CR/LF flattened | `sse/PostSseStream.java:249-260` |

Rejection path: `rejectAndClose` marks channel rejected (drops remaining chunks) + `Connection: close` `ChannelHandlerUtils.java:51-91`.

Tests: `DnsRebindingProtectionHandlerTest`, `McpHeaderGuardHandlerTest`, `EndpointValidatorHandlerTest`, e2e `DnsRebindingTest`, `AcceptHeaderValidationTest`, `MaxContentLengthTest`, `v2026_07_28/HeaderValidationTest`, `CustomHeaderValidationTest`.

Related: [[netty-pipeline]], [[errors]].
