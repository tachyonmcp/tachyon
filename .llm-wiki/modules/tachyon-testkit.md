---
title: tachyon-testkit
tags: [module, testing]
sources: [tachyon-testkit/src/main/java/dev/tachyonmcp/testkit/]
updated: 2026-09-13
commit: 582f9c52
---

# 🧪 tachyon-testkit

Verdict: published test library. Raw JDK `HttpClient` MCP clients per protocol version + AssertJ asserts on JSON-RPC/HTTP/SSE. Used by `e2e` for wire-level checks that the official SDK can't express.

| Type | Role | Proof |
|---|---|---|
| `McpClient` (abstract, `Closeable`) | `initialize`, `sendInitialized`, `post(...)` (+ headers, session), `postWithOrigin`, `ping`, `notify`, `sendRpc`, `sendStreamingRequest`, `openPostStream`, `openGetStream(lastEventId)`, `awaitNotification`, `getTask` | `McpClient` |
| `Mcp20251125Client(port|uri)` | 2025-11-25 headers | `Mcp20251125Client` |
| `Mcp20260728Client(port|uri)` | `withExtensions(map)`, `discover()`, `_meta` injection, `initialize()` no-op-ish | `Mcp20260728Client` |
| `SseStream` | reactive subscriber of `SseFrame`s: `await(pred, timeout)`, `awaitFirstEventId`, `assertNoneArrived`, `received`, `rawResponse` | `SseStream` |
| `JsonRpcResponseAssert` | `isSuccess().hasId/hasResult/hasTextContent/hasStructuredContent/isToolError/hasResultType`, `isJsonRpcError().hasErrorCode/hasHttpStatusCode/isMethodNotFound/...` | `JsonRpcResponseAssert` |
| `McpHttpResponseAssert` | `hasStatus`, `isRejectedWith(status, body)` | `McpHttpResponseAssert` |
| `DiscoverResponseAssert` | `isSuccess().hasCapabilities(json)` | `DiscoverResponseAssert` |
| `McpTestServers`, `McpTestClients`, `McpTestClientBuilder` | factories | `McpTestServers.java`, `McpTestClients.java` |
| `TestTaskConnector` | in-memory `TaskConnector` | `TestTaskConnector.java` |
| `TestObservationListener` | records observations | `TestObservationListener.java` |

Related: [[testing]], [[tasks]].
