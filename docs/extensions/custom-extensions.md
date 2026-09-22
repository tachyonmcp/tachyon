---
title: "Write an extension"
weight: 20
sidebar_order: 20
toc: true
description: |-
  Build your own MCP extension step by step: a custom JSON-RPC method, a call from curl, troubleshooting, and the authoring reference.
---

This guide builds a small extension that adds a custom `com.example/greet` method to a Tachyon server.
You then call it with curl, see what happens when a client forgets to declare it, and learn the
rest of the authoring API.

> **API status:** `ServerExtension` and `ExtensionContext` are `@ExperimentalApi`: they may change in
> a later release.

## Do you need an extension?

Usually not. Pick the simplest option that does the job:

| You want to… | Use |
|---|---|
| Let a model call an action, such as "look up an order" | A [tool](../features/tools.md) |
| Expose data that a client reads | A [resource](../features/resources.md) |
| Offer a reusable prompt template | A [prompt](../features/prompts.md) |
| Add a new protocol method, or show features only to clients that opt in | An extension |

## Key terms

- **Extension ID**: a unique name such as `com.example/greetings`. Start it with a reversed domain
  you own, so it can't clash with other extensions.
- **Advertise**: the server lists the extensions it supports in its capabilities.
- **Declare**: the client lists the extensions it wants to use. By default (`OPTIONAL`) a client can
  call an extension's methods without declaring it. A `REQUIRED` extension rejects such calls.
- **MCP version**: MCP protocol revisions are named by date. This guide uses `2026-07-28`, the
  latest. See [what clients must send](_index.md#what-clients-must-send) for `2025-11-25`.

## Build your first extension

You need the finished project from the [quickstart](../quickstart.md): the `greeting-server`
directory with its `pom.xml`. `tachyon-core` already contains everything an extension needs.

### 1. Create the extension

Create `src/main/java/GreetingsExtension.java`:

```java
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import java.util.Map;

public class GreetingsExtension implements ServerExtension {

    @Override
    public String extensionId() {
        return "com.example/greetings";
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    @Override
    public void bootstrap(ExtensionContext context) {
        context.registerHandler("com.example/greet", (interaction, params) -> {
            var name = params == null ? "stranger" : params.stringOr("name", "stranger");
            return Map.of("message", "Hello, " + name + "!");
        });
    }
}
```

What each part does:

- `extensionId()` returns the extension's unique ID.
- `advertiseMode()` returns `ALWAYS`, so every client can see that the server supports it.
- `bootstrap(...)` runs once, when the server is built. Here it registers a handler for the
  `com.example/greet` method.
- The handler reads the optional `name` parameter and returns a map. Tachyon sends the map back as
  the JSON-RPC result.

### 2. Register the extension

Replace `src/main/java/MyMcpServer.java` with:

```java
import dev.tachyonmcp.core.server.TachyonServer;

public final class MyMcpServer {
    public static void main(String[] args) {
        final var server = TachyonServer.builder()
                .name("my-server")
                .version("1.0")
                .withExtensions(new GreetingsExtension())
                .session(session -> session.enabled())
                .host("127.0.0.1")
                .port(8080)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }
}
```

`withExtensions(...)` adds the extension. `session(...)` turns on server sessions. MCP `2026-07-28`
clients don't need them. `2025-11-25` clients declare extensions once, in `initialize`, so without
sessions the declaration lasts only for the `initialize` request. Reusing the TCP connection
does not preserve it; later calls to this `REQUIRED` extension are rejected.

### 3. Run the server

From `greeting-server`, run:

```bash
mvn -q compile exec:exec
```

Leave this terminal running. Stop the server with **Ctrl+C** when you finish.

### 4. Call the method

Open a second terminal and run:

```bash
curl --fail-with-body --silent --show-error http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: com.example/greet' \
  --data-binary '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "com.example/greet",
    "params": {
      "name": "Ada",
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {
          "extensions": {"com.example/greetings": {}}
        }
      }
    }
  }'
```

Expected response:

```json
{"jsonrpc":"2.0","id":1,"result":{"message":"Hello, Ada!"}}
```

The request has three parts that every MCP `2026-07-28` call needs:

- The `Mcp-Method` header repeats the body's `method`.
- `_meta."io.modelcontextprotocol/protocolVersion"` repeats the `MCP-Protocol-Version` header.
- `_meta."io.modelcontextprotocol/clientCapabilities"` lists what the client supports. Its
  `extensions` entry declares `com.example/greetings`.

### 5. Call it without declaring the extension

Run the same command, but change the `extensions` line to `"extensions": {}`. The call still
succeeds: by default an extension is `OPTIONAL`, so the server serves clients that don't declare it,
as SEP-2133's fallback rule allows. Inside the handler, `interaction.isExtensionEnabled("com.example/greetings")`
returns `false` for this call, which lets you fall back to core behavior.

To make the extension mandatory, override `negotiation()` in `GreetingsExtension`:

```java
@Override
public ExtensionNegotiation negotiation() {
    return ExtensionNegotiation.REQUIRED;
}
```

Import `dev.tachyonmcp.api.server.extensions.ExtensionNegotiation`, restart the server, and repeat
the call. The server now rejects it. The response has HTTP status `400`, so curl also prints
`The requested URL returned error: 400` and exits with code `22`:

```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32021,"message":"Requires the 'com.example/greetings' extension","data":{"requiredCapabilities":{"extensions":{"com.example/greetings":{}}}}}}
```

`-32021` means the client must declare a capability first. The `data` field names the extension to
declare.

You now have a working extension. The rest of this page explains the other options.

## Troubleshooting

| Response | Cause | Fix |
|---|---|---|
| `-32021 Requires the '<id>' extension`, HTTP `400` | A `REQUIRED` extension: the `2026-07-28` request doesn't declare it | Add the extension ID under `_meta."io.modelcontextprotocol/clientCapabilities".extensions` |
| `-32003 Requires the '<id>' extension` | A `REQUIRED` extension: a `2025-11-25` client didn't declare it in `initialize`, or the server has no sessions | Declare it in `initialize` `capabilities.extensions` and enable sessions on the server, or use `OPTIONAL` |
| `-32020 Header mismatch: Mcp-Method is required…`, HTTP `400` | The `Mcp-Method` header is missing or differs from the body's `method` | Send `Mcp-Method` with the same value as `method` |
| `-32601 Method not found`, HTTP `404` | The method name is misspelled, or the extension isn't registered | Check the name in `registerHandler` and the `withExtensions(...)` call |
| `-32603 Internal error` | The handler threw an exception | Check the server log; validate input inside the handler instead of throwing |

## Reference

### The `ServerExtension` interface

| Method | Required | Purpose |
|---|---|---|
| `extensionId()` | Yes | Unique ID, such as `com.example/greetings` |
| `advertiseMode()` | Yes | When the server lists the extension; see [advertisement](#advertisement) |
| `serverSettings()` | No | Settings sent to clients with the advertisement; empty by default |
| `negotiation()` | No | `OPTIONAL` (default) or `REQUIRED`; see [negotiation policy](_index.md#negotiation-policy) |
| `bootstrap(ExtensionContext)` | No | Registers methods and features at startup |
| `onConnectionInit(InteractionContext, ExtensionSettings)` | No | Runs when a client declares the extension |
| `methods()` | No | Claims methods whose handlers are registered outside `bootstrap` |
| `shutdown()` | No | Releases resources when the server stops |

`ExtensionContext`, passed to `bootstrap`, gives access to the tool, resource, prompt, and
completion registries, the handler executor, and runtime settings. It doesn't expose the network
transport.

### Custom methods

`context.registerHandler(method, handler)` adds a JSON-RPC method that the extension owns:

- **Name**: prefix it with your extension's vendor, such as `com.example/greet`, so it can't clash
  with core MCP methods or other extensions.
- **Parameters**: `params` is a read-only `JsonObject`. The interface marks it `@Nullable`, so
  guard against `null`. Read values with accessors such as `stringOr`, `intOpt`, and `objectOpt`.
- **Result**: the return value is serialized as the JSON-RPC result. Return `null` for an empty
  result.
- **Errors**: return a `ServerError` for an error response, using
  `ServerError.Kind.INVALID_PARAMS` for expected input errors. `McpDispatcher` serializes it as a
  JSON-RPC error envelope. Reserve exceptions for unexpected failures; normal results remain
  successful responses.
- **Negotiation**: the extension's [negotiation policy](_index.md#negotiation-policy) decides whether
  undeclared clients may call the method. Declaring the extension is enough; calls need no
  extension-specific `_meta` entry.

Methods registered in `bootstrap` belong to the extension automatically. Use `methods()` only for
methods whose handlers are registered elsewhere.

### Extension-gated features

A tool, resource, or prompt can belong to an extension. Set `extensionId(...)` on its descriptor:

```java
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;

@Override
public void bootstrap(ExtensionContext context) {
    context.tools().register(
            ToolDescriptor.builder()
                    .name("farewell")
                    .description("Says goodbye")
                    .extensionId(extensionId())
                    .build(),
            (interaction, request) -> ToolResult.text("Goodbye!"));
}
```

Clients that didn't declare the extension don't see the tool in `tools/list`. Calling it fails with
`-32602 Unknown tool: farewell`. Without `extensionId(...)`, every client can see and call a feature
registered in `bootstrap`.

### Server and client settings

`serverSettings()` returns settings that the server advertises with the extension:

```java
@Override
public ExtensionSettings serverSettings() {
    return ExtensionSettings.of(Map.of("version", "1.0"));
}
```

A client sends its own settings as the value of its declaration, for example
`"extensions": {"com.example/greetings": {"language": "fr"}}`. Read them in `onConnectionInit`:

```java
@Override
public void onConnectionInit(InteractionContext interaction, ExtensionSettings clientSettings) {
    var language = clientSettings.values().stringOr("language", "en");
}
```

### Advertisement

`advertiseMode()` controls whether `capabilities.extensions` lists the extension and its
`serverSettings()`:

| Mode | Advertised |
|---|---|
| `ALWAYS` | Always |
| `NEGOTIATED` | Only when the client declared the extension in the same request |
| `NEVER` | Never; for internal extensions such as the Kotlin coroutine runtime |

Advertisement doesn't affect negotiation: a client that already knows a `NEVER` extension's ID can
still declare and use it.

### Lifecycle

| Callback | When it runs |
|---|---|
| `bootstrap(ExtensionContext)` | Once, when the server is built |
| `onConnectionInit(InteractionContext, ExtensionSettings)` | When a client declares the extension: at `initialize` on MCP `2025-11-25`, on each declaring request on MCP `2026-07-28` |
| `shutdown()` | When the server stops |

Release resources in `shutdown()`. For example, an extension that owns a scheduler stops it there:

```java
private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

@Override
public void shutdown() {
    scheduler.shutdown();
}
```

---

**See also:** [Extensions](_index.md) · [Tools](../features/tools.md) · [Quickstart](../quickstart.md)
