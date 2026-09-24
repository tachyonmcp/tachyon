---
title: Security guards
tags: [concept, security, transport]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpChannelInitializer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/NettyServerConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/NetworkConfig.java]
updated: 2026-09-24
commit: 70f05aa6
---

# 🛡️ Security guards

Verdict: fail-closed HTTP guards, most of them before body aggregation. Loopback-only by default; `allowedHosts` widens **Host**, `allowedOrigins` widens **Origin**. `Origin: null` never passes: any page sends it from a sandboxed iframe. A remote browser reaching a non-loopback host needs both.

⚠️ A guard that needs the body cannot live pre-aggregation. The header guard rejects only what a *duplicate field line* proves (no body needed) — comparing a mirror's **value** to the body is a separate, post-aggregation guard. Conflating the two once cost every `mcp-remote` client its connection: the preflight mirrors `Mcp-Method: initialize` with no `MCP-Protocol-Version` (SEP-2243's own canonical example), and a pre-aggregation proxy-check answered it 400.

| Guard | Rule | Proof |
|---|---|---|
| DNS rebinding | `Host` must be `localhost`, `localhost.`, `127.0.0.1`, `[::1]` (any port) or allowlisted; missing Host on HTTP/1.1 ⇒ 403; multiple Host/Origin ⇒ 403; `Origin` present ⇒ must be a serialized origin `http(s)://host[:port]` (no path — not even `/` — query, fragment, user info; port 1..65535), else 403; then loopback host or canonical match in `allowedOrigins`; `Origin: null` ⇒ 403 always. Origin list comes from the `CorsConfig` (`forAnyOrigin` ⇒ loopback only) | `DnsRebindingProtectionHandler#isAllowedOrigin`, `Origins#canonical`, `McpChannelInitializer#McpChannelInitializer` |
| allowedOrigins parse | same serialized-origin shape; `*`, `null`, blank ⇒ IAE at build; stored canonical (lower-case, default port dropped) so `https://App.Example.com:443` = `https://app.example.com`; Spring ⇒ `InvalidConfigurationPropertyValueException` naming `tachyon.network.allowed-origins` | `Origins#requireConfigured`, `NetworkConfig#NetworkConfig`, `TachyonPropertiesApplier#toServedOrigins` |
| allowedHosts parse | trimmed, lowercase, rejects whitespace/control/`/ @ ? #` | `DnsRebindingProtectionHandler#DnsRebindingProtectionHandler`, `DnsRebindingProtectionHandler#validateHostEntry` |
| Endpoint | normalized path ≠ endpoint ⇒ 404. The pipeline's only path check; guards after it match no path, so a trailing-slash or custom `endpointPath` cannot skip them. No percent-decoding ⇒ encoded spellings 404. A `pipelineCustomizer` that removes `mcp-endpoint` serves MCP on every path | `EndpointValidatorHandler#channelRead`, `ServerBuilder#pipelineCustomizer` |
| Endpoint config | `endpointPath` must start with `/`, no `?`/`#`/whitespace/control ⇒ IAE at build, value not echoed | `NetworkConfig#NetworkConfig` |
| Header guard | dup `MCP-Protocol-Version`/`MCP-Session-Id`/`Last-Event-ID`/`Mcp-Method`/`Mcp-Name`/`Mcp-Param-*` ⇒ 400 (even identical values; case variants collapse); header name not echoed | `McpHeaderGuardHandler#isSingleton` |
| Accept | POST needs both `application/json` and `text/event-stream` (wildcards ok, `q=0` rejects) ; GET needs `text/event-stream` ⇒ 406 | `AcceptValidationHandler#channelRead` |
| Stateless guard | session/Last-Event-ID headers ⇒ 404; DELETE ⇒ 405 | `StatelessValidatorHandler#channelRead` |
| Body limit | 1 MB default, 413 | `McpChannelInitializer#DEFAULT_MAX_CONTENT_LENGTH`, `McpChannelInitializer#initChannel` |
| Content-Type | POST needs `application/json` (params ok, case-insensitive) ⇒ 415 + JSON-RPC `-32600`, `id: null`, pre-aggregation. No path match of its own: runs after `mcp-endpoint`, so a trailing-slash `endpointPath` cannot skip it. Browsers skip the preflight for `text/plain`/form/multipart, so without it CORS gates nothing | `ContentTypeValidationHandler` |
| CORS | `TachyonCorsHandler` (extends Netty `CorsHandler`) after the guard and `mcp-endpoint` (other paths 404, no grant), always installed (`NettyServerConfig#corsConfig` non-null; default `NettyServerConfig#defaultCorsConfig`). Preflights: Netty's logic, answered in the same `channelRead`. Responses: Netty's `write()` decoration is disabled — it reads the channel's *last* request, wrong for an async response once a pipelined request arrives. Instead each request's immutable `CorsDecision` (decided once, `TachyonCorsHandler#decide`) is carried to every writer: JSON, 202, plain-text errors, SSE, early rejects after `cors`, 413 (`CorsHttpObjectAggregator`). Handler removed via `pipelineCustomizer` ⇒ `CorsDecision#NONE`. Unset `allowedOrigins` ⇒ any origin, `ACAO: *` (guard admits loopback only, any port). Set ⇒ only listed origins get `ACAO: <origin>`; every request with `Origin` gets `Vary: Origin`, grant or not. Grants GET/POST/DELETE, MCP headers + `Authorization`, no `*`; `McpParamPreflightHandler` appends each requested `Mcp-Param-<token>` to a granted preflight's `Access-Control-Allow-Headers`, exposes `MCP-Session-Id`/`MCP-Protocol-Version`, max-age 1d, never credentials | `NettyServerConfig#buildCorsConfig`, `TachyonCorsHandler#write`, `CorsDecision#of`, `McpParamPreflightHandler#write`, `McpChannelInitializer#initChannel` |
| Body/header agreement (**all** versions) | SEP-2243 mirror present ⇒ must match body, whichever version negotiated — a gateway must not route on a header the server never executes | `McpHeaderMatchHandler`, [[protocol-versions]] |
| Mirror **required** (2026-07-28 only) | the revision that adopted SEP-2243 also demands the mirrors be present; runs after agreement | `RequiredHeadersHandler#requireMirrors` |
| Pending-request ownership | client response must come from owning session (stateful) / channel (stateless) | `DefaultTachyonServer#failPendingRequest` |
| Error message hygiene | bare IAE message hidden; client-controlled values not echoed in header errors | [[errors]] |
| SSE comment injection | CR/LF flattened | `PostSseStream#doWriteComment` |

Rejection path: `rejectAndClose` marks channel rejected (drops remaining chunks) + `Connection: close` `ChannelHandlerUtils#rejectAndClose`.

Tests: `DnsRebindingProtectionHandlerTest`, `McpHeaderGuardHandlerTest`, `McpHeaderMatchHandlerTest`, `EndpointValidatorHandlerTest`, e2e `AbstractDnsRebindingTest` (raw-socket `Host` rebinding incl. `0.0.0.0`/`[::]`, look-alike origins, preflight, preflight to other path; per version), `AbstractCorsOriginAllowlistTest` (canonical `allowedOrigins` admit, IPv6, other port/scheme/path 403, `null` 403, loopback miss ⇒ `Vary` only; per version), `CorsPipeliningTest` (two pipelined requests, each response keeps its own grant, strict order), `CorsDecisionTest`, `AbstractContentTypeValidationTest` (tagged tool proves no dispatch; `ContentTypeValidationTrailingSlashEndpointTest` reruns it against `endpointPath("/mcp/")`), `AbstractBrowserClientTest` (per version), `AcceptHeaderValidationTest`, `MaxContentLengthTest`, `v2025_11_25/HeaderValidationTest` (optional mirrors, mcp-remote `initialize` preflight), `v2026_07_28/HeaderValidationTest`, `CustomHeaderValidationTest`.

Related: [[netty-pipeline]], [[errors]].
