---
title: Errors
tags: [concept, errors, protocol]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/domain/ServerError.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/domain/ServerErrors.java, tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2025_11_25/codecs/McpResponseMapper.java, tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/codecs/McpResponseMapper.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java]
updated: 2026-09-17
commit: 1a4081f4
---

# 🚨 Errors

Verdict: handlers produce protocol-neutral `ServerError(kind, message, data)` (returned as value, thrown via `RequestMappingException`, or derived from exception). Response mapper per protocol picks wire code + HTTP status. Transport-level problems bypass JSON-RPC as plain-text HTTP.

## 🔢 Kind → wire

`ServerError.Kind` `Kind`. Maps: 2025 `McpResponseMapper.java`, 2026 `McpResponseMapper.java`.

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

Extension gate: `ServerErrors.missingRequiredExtension(id)` ⇒ `MISSING_REQUIRED_CLIENT_CAPABILITY`, message `Requires the '<id>' extension`, `data.requiredCapabilities.extensions.<id>:{}` [ServerErrors#missingRequiredExtension](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/domain/ServerErrors.java). See [[extensions]].

2025 mapper always HTTP 200 (`JsonRpcError` default). ⚠️ `UnsupportedProtocolVersionHandler` always uses **latest** protocol mapper + HTTP 400 `UnsupportedProtocolVersionHandler#channelRead`.

## 🧯 Exception → error

`ServerErrors.fromUnhandledException` `ServerErrors#fromUnhandledException` (used by tool/prompt/resource/completion handlers):

| Thrown | Result |
|---|---|
| `InvalidArgumentException(argName, msg)` (api) | INVALID_PARAMS `invalid argument '<arg>': <msg>` — message trusted |
| `MissingRequiredClientCapabilityException` | MISSING_REQUIRED_CLIENT_CAPABILITY + `requiredCapabilities` data |
| `IllegalArgumentException` | INVALID_PARAMS `"Invalid params"` — message **hidden** (may leak lib internals) |
| anything else | INTERNAL_ERROR with fixed detail (`"Tool handler failed"` …) |

Dispatcher-level `McpDispatcher.handleHandlerError` `McpDispatcher#handleHandlerError`: `CancellationException` ⇒ internal error + `Cancelled`; `RequestMappingException` ⇒ its error; other ⇒ `"Internal error"`. Serialization failure ⇒ `"Failed to encode response"` + `SerializationFailed` outcome `OperationOutcome`.

`tools/call` cancel ⇒ `"Tool call cancelled"` `ToolsCallHandler#handlerError`. Output-schema failure ⇒ **tool result** `isError` (not JSON-RPC error).

## 🌐 Plain HTTP responses (no JSON-RPC)

| Status | When | Proof |
|---|---|---|
| 400 | missing `MCP-Session-Id` (stateful) ; duplicate singleton MCP header ; unparseable body (JSON parse error body) | `McpDispatcher#dispatchTrackedRequestAsync`, `McpHeaderGuardHandler#hasDuplicateSingleton` |

⚠️ A SEP-2243 mirror disagreeing with the body is **not** here — it is a JSON-RPC error, coded per negotiated version (400/-32020 on 2026-07-28, 200/-32001 on 2025-11-25) via `ChannelHandlerUtils#rejectWithServerError` — [[protocol-versions]].
| 403 | DNS-rebinding guard | `DnsRebindingProtectionHandler#reject` |
| 404 | wrong path; unknown session; stateless + session headers | `EndpointValidatorHandler`, `McpOperationHandler#session`, `StatelessValidatorHandler#channelRead` |
| 405 | DELETE on stateless; unknown HTTP method | same |
| 406 | Accept missing `application/json`+`text/event-stream` (POST) / `text/event-stream` (GET) | `AcceptValidationHandler#POST_ACCEPT_TYPES` |
| 413/417 | body > `maxContentLength` (1 MB) | `HttpObjectAggregator` |
| 500 | session lookup failure | `McpOperationHandler#session` |
| 503 | executor rejected (shutting down) | `ChannelHandlerUtils.isRefused` |

Related: [[request-lifecycle]], [[security-guards]], [[protocol-versions]].
