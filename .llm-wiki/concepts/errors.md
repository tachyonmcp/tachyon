---
title: Errors
tags: [concept, errors, protocol]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/domain/ServerError.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/domain/ServerErrors.java, tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2025_11_25/codecs/McpResponseMapper.java, tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/codecs/McpResponseMapper.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java]
updated: 2026-09-14
commit: 5bee50aa
---

# 🚨 Errors

Verdict: handlers produce protocol-neutral `ServerError(kind, message, data)` (returned as value, thrown via `RequestMappingException`, or derived from exception). Response mapper per protocol picks wire code + HTTP status. Transport-level problems bypass JSON-RPC as plain-text HTTP.

## 🔢 Kind → wire

`ServerError.Kind` `tachyon-api/src/main/java/dev/tachyonmcp/api/server/domain/ServerError.java:19-38`. Maps: 2025 `.../v2025_11_25/codecs/McpResponseMapper.java:88-103`, 2026 `.../v2026_07_28/codecs/McpResponseMapper.java:110-131`.

| Kind | 2025-11-25 code | 2026-07-28 code | 2026 HTTP |
|---|---|---|---|
| `PARSE_ERROR` | -32700 | -32700 | 200 |
| `INVALID_REQUEST` | -32600 | -32600 | 200 |
| `METHOD_NOT_FOUND` | -32601 | -32601 | **404** |
| `INVALID_PARAMS` | -32602 | -32602 | **400** |
| `INTERNAL_ERROR` | -32603 | -32603 | 200 |
| `RESOURCE_NOT_FOUND` | -32002 | **-32602** | 200 |
| `HEADER_MISMATCH` | -32001 | -32020 | 400 |
| `MISSING_REQUIRED_CLIENT_CAPABILITY` | -32003 | -32021 | 400 |
| `UNSUPPORTED_PROTOCOL_VERSION` | -32004 | -32022 | 400 |

Extension gate: `ServerErrors.missingRequiredExtension(id)` ⇒ `MISSING_REQUIRED_CLIENT_CAPABILITY`, message `Requires the '<id>' extension`, `data.requiredCapabilities.extensions.<id>:{}` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/domain/ServerErrors.java:75-78`. See [[extensions]].

2025 mapper always HTTP 200 (`JsonRpcError` default). ⚠️ `UnsupportedProtocolVersionHandler` always uses **latest** protocol mapper + HTTP 400 `UnsupportedProtocolVersionHandler.java:40-47`.

## 🧯 Exception → error

`ServerErrors.fromUnhandledException` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/domain/ServerErrors.java:43-52` (used by tool/prompt/resource/completion handlers):

| Thrown | Result |
|---|---|
| `InvalidArgumentException(argName, msg)` (api) | INVALID_PARAMS `invalid argument '<arg>': <msg>` — message trusted |
| `MissingRequiredClientCapabilityException` | MISSING_REQUIRED_CLIENT_CAPABILITY + `requiredCapabilities` data |
| `IllegalArgumentException` | INVALID_PARAMS `"Invalid params"` — message **hidden** (may leak lib internals) |
| anything else | INTERNAL_ERROR with fixed detail (`"Tool handler failed"` …) |

Dispatcher-level `McpDispatcher.handleHandlerError` `McpDispatcher.java:459-490`: `CancellationException` ⇒ internal error + `Cancelled`; `RequestMappingException` ⇒ its error; other ⇒ `"Internal error"`. Serialization failure ⇒ `"Failed to encode response"` + `SerializationFailed` outcome `:662-680`.

`tools/call` cancel ⇒ `"Tool call cancelled"` `ToolMethodHandlers.java:252-256`. Output-schema failure ⇒ **tool result** `isError` (not JSON-RPC error).

## 🌐 Plain HTTP responses (no JSON-RPC)

| Status | When | Proof |
|---|---|---|
| 400 | missing `MCP-Session-Id` (stateful) ; duplicate MCP header / SEP-2243 mirror w/o 2026 version ; unparseable body (JSON parse error body) | `McpDispatcher.java:298-302`, `http/McpHeaderGuardHandler.java:89-116` |
| 403 | DNS-rebinding guard | `http/DnsRebindingProtectionHandler.java:119-121` |
| 404 | wrong path; unknown session; stateless + session headers | `EndpointValidatorHandler`, `McpOperationHandler.java:173-177`, `StatelessValidatorHandler.java:21-39` |
| 405 | DELETE on stateless; unknown HTTP method | same |
| 406 | Accept missing `application/json`+`text/event-stream` (POST) / `text/event-stream` (GET) | `http/AcceptValidationHandler.java:44-64` |
| 413/417 | body > `maxContentLength` (1 MB) | `HttpObjectAggregator` |
| 500 | session lookup failure | `McpOperationHandler.java:166-171` |
| 503 | executor rejected (shutting down) | `ChannelHandlerUtils.isRefused` |

Related: [[request-lifecycle]], [[security-guards]], [[protocol-versions]].
