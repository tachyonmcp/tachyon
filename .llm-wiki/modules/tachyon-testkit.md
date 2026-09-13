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
| `McpClient` (abstract, `Closeable`) | `initialize`, `sendInitialized`, `post(...)` (+ headers, session), `postWithOrigin`, `ping`, `notify`, `sendRpc`, `sendStreamingRequest`, `openPostStream`, `openGetStream(lastEventId)`, `awaitNotification`, `getTask` | `tachyon-testkit/src/main/java/dev/tachyonmcp/testkit/McpClient.java:37-516` |
| `Mcp20251125Client(port|uri)` | 2025-11-25 headers | `Mcp20251125Client.java:10-36` |
| `Mcp20260728Client(port|uri)` | `withExtensions(map)`, `discover()`, `_meta` injection, `initialize()` no-op-ish | `Mcp20260728Client.java:20-117` |
| `SseStream` | reactive subscriber of `SseFrame`s: `await(pred, timeout)`, `awaitFirstEventId`, `assertNoneArrived`, `received`, `rawResponse` | `SseStream.java:38-332` |
| `JsonRpcResponseAssert` | `isSuccess().hasId/hasResult/hasTextContent/hasStructuredContent/isToolError/hasResultType`, `isJsonRpcError().hasErrorCode/hasHttpStatusCode/isMethodNotFound/...` | `JsonRpcResponseAssert.java:15-389` |
| `McpHttpResponseAssert` | `hasStatus`, `isRejectedWith(status, body)` | `McpHttpResponseAssert.java:21-87` |
| `DiscoverResponseAssert` | `isSuccess().hasCapabilities(json)` | `DiscoverResponseAssert.java:11-49` |
| `McpTestServers`, `McpTestClients`, `McpTestClientBuilder` | factories | `McpTestServers.java`, `McpTestClients.java` |
| `TestTaskConnector` | in-memory `TaskConnector` | `TestTaskConnector.java` |
| `TestObservationListener` | records observations | `TestObservationListener.java` |

Related: [[testing]], [[tasks]].
