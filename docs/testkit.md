---
title: "MCP Testkit"
sidebar_title: "Test a tool"
weight: 13
sidebar_order: 13
toc: true
description: |-
  Test Tachyon servers end to end: shaping clients, dynamic-port servers, and fluent JSON-RPC assertions.
---

`tachyon-testkit` drives a running Tachyon server from tests: protocol-shaping HTTP clients,
in-process port-0 server helpers, and fluent JSON-RPC assertions.

Version is pinned by the `tachyon-bom` — see [Quickstart](quickstart.md#1-add-the-dependency).

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-testkit</artifactId>
    <scope>test</scope>
</dependency>
```

## Servers

`McpTestServers.start` builds a port-0 server, registers handlers, and starts it — closing the
transport if anything fails, so a broken test never leaks a listener:

```java
var server = McpTestServers.start(
    b -> b.session(c -> c.enabled()),
    s -> s.tools().register(descriptor, handler));
var port = server.port();
```

## Clients

`McpTestClients` builds a raw JSON-over-HTTP client for a protocol version — `Mcp20251125Client`
(session-based, `initialize` handshake) or `Mcp20260728Client` (sessionless, self-describing
requests):

```java
try (var client = McpTestClients.latest(port)) {
    client.post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""");
}
```

`McpTestClients.builder(port)` skips the manual `initialize()` dance and returns an
already-initialized client for the chosen protocol version:

```java
try (var client = McpTestClients.builder(port).protocolVersion("2025-11-25").build()) {
    client.sendRpc("""{"jsonrpc":"2.0","id":1,"method":"ping"}""");
}
```

Pass a `URI` instead of a port (`McpTestClients.builder(URI.create("https://staging.example.com/mcp"))`)
to drive a remote server instead of a local one.

## Assertions

`JsonRpcResponseAssert` first selects the JSON-RPC branch, then exposes only assertions valid for
that branch:

```java
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;

var response = client.post("""
    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hi"}}}
    """);

assertThat(response).isSuccess().hasTextContent("echo:hi");
```

Use `hasResult(expected)` or `hasContentExactly(blocks...)` when the complete result or content
array is stable. `hasContent()` requires at least one content block.

Error assertions follow the same staged shape:

```java
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

final HttpResponse<String> response = client.sendRpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"missing","arguments":{}}}""");

assertThatResponse(response)
    .isJsonRpcError()
    .hasErrorCode(-32602)
    .hasErrorMessage("Invalid params");
```

### HTTP status and transport rejections

`JsonRpcResponseAssert` reads the body as a JSON-RPC envelope, so it cannot speak for the HTTP
status, nor for a rejection the transport answers in plain text before an envelope exists —
a duplicate MCP header, a protocol version that cannot validate mirrored headers, an oversized
body. `McpHttpResponseAssert` covers both and chains into the JSON-RPC assertions:

```java
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("echo:hi");
assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(9).hasErrorCode(-32020);
assertThatResponse(response).isRejectedWith(400, "Duplicate MCP header");
```

Prefer `isRejectedWith` over a bare status check for transport rejections: a JSON-RPC error
carries the same `400`, so the status alone does not prove which layer rejected the request.

## Fixtures

Three fixtures cover the parts of a server that are awkward to drive over the wire.

`TestTaskConnector` stands in for the external system behind a [task](features/tasks.md)
connector. Seed it with snapshots, hand `connector()` to the builder, then assert on what
Tachyon asked it for:

```java
var tasks = new TestTaskConnector().start(TaskSnapshot.working("t-1", Instant.now(), 1));

var server = McpTestServers.start(
    b -> b.capabilities(c -> c.tasks(tasks.connector())),
    s -> {});

// later
assertThat(tasks.refreshedTaskIds()).containsExactly("t-1");
```

`publish(snapshot)` stores the snapshot a later `tasks/get` returns, and `deferCancellation()`
makes `tasks/cancel` leave the task non-terminal so you can assert the pending state. `reset()`
clears both the snapshots and the recorded calls.

`TestObservationListener` records the dispatch lifecycle. `started()` and `completed()` return
the recorded calls in order; `failOnStart(...)` / `failOnComplete(...)` make it throw, which is
how you verify that a listener failure never reaches the handler:

```java
var listener = new TestObservationListener();
var server = McpTestServers.start(b -> b.observability(o -> o.listener(listener)), s -> {});
// ...
assertThat(listener.completed()).singleElement()
    .satisfies(c -> assertThat(c.info().method()).isEqualTo("tools/call"));
```

`DiscoverResponseAssert` covers the 2026-07-28 `server/discover` response. `Mcp20260728Client.discover()`
returns it directly:

```java
try (var client = McpTestClients.latest(port)) {
    client.discover().isSuccess().hasCapabilities("""
        {"tools":{}}
        """);
}
```

## Notifications

Every client captures server-to-client notifications delivered over SSE; await one by method
name, or take a snapshot of everything received so far:

```java
client.awaitNotification("notifications/progress")
    .satisfies(params -> assertThat(params.path("progressToken").asString()).isEqualTo("tok-1"));
```

For long-lived streaming POSTs, including `subscriptions/listen`, use `openPostStream`. It verifies
the SSE response and exposes parsed `SseFrame` values through the same `SseStream` API used by GET
subscriptions:

```java
try (var stream = client.openPostStream(null, """
    {"jsonrpc":"2.0","id":1,"method":"subscriptions/listen",
     "params":{"notifications":{"toolsListChanged":true}}}
    """)) {
    stream.await(
    frame -> frame.data().contains("notifications/subscriptions/acknowledged"),
        Duration.ofSeconds(5));
    }
```
