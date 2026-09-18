---
title: "Migrating from Kotlin SDK"
weight: 90
sidebar_order: 90
toc: true
description: |-
  Port a Kotlin MCP SDK server to Tachyon: type-for-type API translation, dependency swap, and the behaviour changes that don't fail to compile.
---

You have an MCP server on `io.modelcontextprotocol:kotlin-sdk` and its hand-rolled Ktor
transport. Tachyon owns the transport, sessions, and validation for you — but the port
touches every layer, and a few swaps are silent behaviour changes if you copy mechanically.

This guide is the map: the type-for-type translation, plus the three regressions that don't
fail to compile and won't show up until a client hits them.

## The translation at a glance

| Concern | Kotlin MCP SDK | Tachyon (Kotlin) |
|---|---|---|
| Package root | `io.modelcontextprotocol.kotlin.sdk.*` | `dev.tachyonmcp.{api,core,kotlin}.*` |
| JSON node type | kotlinx `JsonElement` / `McpJson` | **Jackson 3** `tools.jackson.databind.JsonNode` |
| Server + HTTP | `Server(...)` + you wire Ktor, sessions, reaper | `TachyonServer(port) { ... }` — transport & sessions built in |
| Identity | `Implementation(name, version, title, websiteUrl, icons)` | `info { name; version; title; websiteUrl; icons.add(...) }` |
| Icon | `sdk.types.Icon` | `dev.tachyonmcp.kotlin.server.domain.Icon { src = …; mimeType = … }` |
| Register a tool | `server.addTool(name, desc, inputSchema: ToolSchema, outputSchema, handler)` | `server.registerTool(name, desc, inputSchema, outputSchema) { }` |
| Handler receiver | `suspend ClientConnection.(CallToolRequest) -> CallToolResult` | `suspend ToolScope.() -> ToolResult` |
| Read arguments | `request.arguments: JsonObject` | `request.arguments(): Args` (typed accessors) |
| Tool result | `CallToolResult(content = listOf(TextContent(x)), isError)` | `ToolResult.text(x)` / `.error(x)` / `success(v)` |
| Schema type | `ToolSchema(properties, required)` | `String` **or** Jackson `JsonNode` **or** kotlinx `JsonObject` |
| Logging | `server.sendLoggingMessage(LoggingMessageNotification(...))` | `ctx.notifications().log(level, logger, data)` |
| Log level | `LoggingLevel.Info/Debug/Error` (PascalCase) | `LoggingLevel.INFO/DEBUG/ERROR` (UPPER) |
| Resources | `server.addResources(...)` per file | `server.resources().register(descriptor, handler)` / `.registerTemplate(...)` |
| Client requests | SDK-specific elicitation helper | `InteractionContext.sendRequest(method, params)` (via `ToolScope.ctx`) |
| JSON config | `McpJson` | `KxSerializationSerde(json = yourJson)` in `json { serde = ... }` |

## 1. Dependencies

Swap the SDK for Tachyon's Kotlin module. Keep `kotlinx-serialization-json` — it's an
*optional* Tachyon dependency, and you need it for `JsonObject` schemas and
`request.arguments().decode<T>()`. Jackson 3 (`tools.jackson.*`) arrives transitively.

```toml
# out: io.modelcontextprotocol:kotlin-sdk
tachyon-kotlin = { module = "dev.tachyonmcp:tachyon-kotlin", version.ref = "tachyon" }
```

## 2. Server and transport — where most of the code disappears

The SDK hands you a `Server` and leaves the streamable-HTTP transport, session lifecycle, and
reaping to you. `TachyonServer { }` is the whole thing:

```kotlin
val server = TachyonServer(port = mcpPort) {
    info {
        name = "example-server"
        title = "Example MCP"
        version = appVersion
        websiteUrl = "https://example.com/docs"
        icons.add(logoIcon)
    }
    capabilities {
        tools(listChanged = true)
        resources(subscribe = false, listChanged = true)
        logging = true
    }
    json { serde = KxSerializationSerde(json = yourJson) }   // reuse your kotlinx Json config
    network {
        host = "127.0.0.1"
        allowedOrigins.add("https://your-client.example.com")
    }
    session { sessionTtl = 10.minutes }
}

server.tools()    // register tools through feature registries or Kotlin extensions
server.port()
server.close()    // wire to your app's stop hook
```

Use `TachyonServer { }` or `buildServer { }` as the Kotlin construction surface. Both delegate to
the Java `ServerBuilder` and add suspend handlers and Kotlin-specific types on top.

The identity block is easy to under-fill. `info { }` supports `title`, `websiteUrl`, and
`icons` — port all of them, not just `name`/`version`:

```kotlin
import dev.tachyonmcp.kotlin.server.domain.Icon
import java.util.Base64

val logoIcon =
    object {}.javaClass.getResourceAsStream("/logo-32x32.png")!!.use { s ->
        Icon {
            src = "data:image/png;base64,${Base64.getEncoder().encodeToString(s.readAllBytes())}"
            sizes = listOf("32x32")
            mimeType = "image/png"
        }
    }
```

> Map any timeout for pending server-to-client requests to
> `runtime { requestTimeout = ... }`. Long-running tool calls use MCP progress, SSE comments,
> or task augmentation; see the runtime and task guides.

## 3. Tools

`addTool` becomes `registerTool` (import from `dev.tachyonmcp.kotlin.server.features.tools`). The
handler receiver changes from `ClientConnection.(CallToolRequest)` to `ToolScope`, and it
returns a `ToolResult`. `registerTool` returns the `TachyonServer`, so registrations chain.

```kotlin
server.registerTool(
    name = "search",
    description = "Search the index.",
    inputSchema = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
    outputSchema = SearchResult::class.jsonSchemaString,   // nullable
) { // this: ToolScope
    val query = request.arguments().stringValue("query")
    ToolResult.text(json.encodeToString(SearchResult.serializer(), run(query)))
}
```

A thin wrapper that assembles the `{"type":"object","properties":…,"required":…}` envelope and
warns when a description exceeds 2048 chars (clients truncate there) pays off across many tools.

## 4. Arguments — `Args`

`ToolScope.request.arguments()` replaces digging through a `JsonObject`:

| Old                                                 | New                                                                                  |
|-----------------------------------------------------|--------------------------------------------------------------------------------------|
| `arguments["k"]?.jsonPrimitive?.content` (required) | `request.arguments().stringValue("k")` — throws if missing |
| same, optional | `request.arguments().stringOrNull("k")` |
| with a default | `request.arguments().stringOr("k", "d")` |
| `…?.int` etc. | `request.arguments().intValue/boolValue/doubleValue` (+ `…OrNull`, `…(k, default)`) |
| nested object / array | `request.arguments().objectOrNull("k")` / `arrayOrNull("k")` |
| underlying provider node | `request.arguments().unwrap(JsonNode::class.java)` |
| membership | `request.arguments().has("k")` |
| decode whole object | `request.arguments().decode<MyArgs>()` (via configured serde) |

## 5. Results — `ToolResult`

```kotlin
CallToolResult(content = listOf(TextContent(json)))               → ToolResult.text(json)
CallToolResult(content = listOf(TextContent(json)), isError=true) → ToolResult.error(json)
ToolResult.raw(structuredJson, textFallback)                    // pre-serialized JSON, skips serde
success(EchoReply(...))                                         // configured serde → structuredContent
ToolResult.structured(pojo)                                     // Jackson: POJO → structuredContent
```

## 6. Schemas — three shapes, one gotcha

`inputSchema`/`outputSchema` accept a raw **String**, a Jackson **`JsonNode`**, or a kotlinx
**`JsonObject`** on every overload. To skip hand-written schemas entirely, see
[typed tools](_index.md#typed-tools) — they derive both schemas from your Kotlin types.

- **Jackson 3**: `tools.jackson.databind.JsonNode`, *not* `com.fasterxml.jackson…`. Convert a
  kotlinx `JsonObject` once with `ObjectMapper().readTree(obj.toString())`.
- The root must declare `"type":"object"` — validated at *registration*, so a typo in a raw
  string fails at boot, not at call time.
- Generating schema strings from `@Serializable` types (e.g. `KClass.jsonSchemaString` from a
  schema-generator library) drops straight into the String overload and kills the
  data-class-vs-schema drift the SDK's `ToolSchema` also invited.

## 7. Logging

The `LoggingMessageNotification` wrapper is gone. From a handler, publish through
`ctx.notifications()`. Note the enum casing; data may be any JSON-serializable object.

```kotlin
// before: server.sendLoggingMessage(LoggingMessageNotification(...LoggingLevel.Info, data = McpJson...))
ctx.notifications().log(LoggingLevel.INFO, loggerName, entry)
```

`Info/Debug/Error` → `INFO/DEBUG/ERROR`. The interaction context routes the notification to the
current client and applies its selected threshold.

## 8. Resources

```kotlin
val server = TachyonServer(port = mcpPort) {
    // Concrete resource: appears in resources/list.
    resource(
        name = "readme",
        uri = "example://docs/readme",
        description = "Project documentation",
        mimeType = "text/markdown",
        title = "README",
    ) {
        TextResourceContents {
            text = read(uri) ?: error("not found")
        }
    }

    // URI template: appears in resources/templates/list.
    resourceTemplate(
        name = "docs",
        uriTemplate = "example://docs/{path}",
        description = "Docs",
        mimeType = "text/markdown",
    ) {
        TextResourceContents {
            text = read(this@resourceTemplate.uri) ?: error("not found")
        }
    }
}
```

Static and template result builders inherit the requested URI and registered MIME type.
`TextResourceContents { }` and `BlobResourceContents { }` let you override either value. Template
text builders also keep `param("path")` and `sequence("segments")` in scope.

Use a receiver factory when you need a reusable descriptor:

```kotlin
val docs = ResourceTemplateDescriptor {
    name = "docs"
    uriTemplate = "example://docs/{path}"
    description = "Docs"
    mimeType = "text/markdown"
}

val server = TachyonServer(port = mcpPort) {
    resourceTemplate(docs) {
        TextResourceContents {
            text = read(this@resourceTemplate.uri) ?: error("not found")
        }
    }
}
```

`list_changed` fires automatically when the registry changes (given `listChanged = true`) — drop
any manual `broadcastNotification("notifications/resources/list_changed", …)`. Use the resource
**`name`** as your remove key; make it unique (the URI is a safe choice — bare filenames collide
across directories, and `remove(name)` won't find a stale entry keyed differently).

## 9. Client requests

Client requests now hang off `InteractionContext` (available as `ToolScope.ctx`) instead of a
`Server` + `sessionId` pair:

```kotlin
val response = ctx.sendRequest("elicitation/create", params).join()
```

## Checklist

- [ ] Swap dependency; keep `kotlinx-serialization-json` if you use decode/structured/JsonObject schemas.
- [ ] Delete the custom Ktor transport/session code — `TachyonServer { }` replaces it.
- [ ] Port full identity: `title`, `websiteUrl`, `icons` — not just `name`/`version`.
- [ ] `addTool` → `registerTool`; receiver → `ToolScope`; arguments → `request.arguments()`; results → `ToolResult`.
- [ ] Fix Jackson imports to `tools.jackson.*`; convert kotlinx schemas with `readTree(...)`.
- [ ] Re-emit `required` on every input schema.
- [ ] `LoggingLevel.Info` → `INFO`; route handler logs through `ctx.notifications().log(...)`.
- [ ] Register concrete resources if clients call `resources/list`; drop manual list-changed broadcasts.
- [ ] Client requests, including elicitation, → `ctx.sendRequest(method, params)`.
- [ ] Re-audit every input-validation check the SDK path enforced — confirm it still runs against a trusted value.

Continue with [Tools](../features/tools.md), [Resources](../features/resources.md), and the
[Kotlin DSL](./).
Hit a rough edge? [Open an issue](https://github.com/tachyonmcp/tachyon/issues/new) on the Tachyon repo.
