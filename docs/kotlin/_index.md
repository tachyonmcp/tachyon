---
title: Kotlin
overview_title: "Introduction"
weight: 35
sidebar_order: 35
toc: true
description: |-
  Coroutine-first Kotlin DSL for Tachyon
---

The `tachyon-kotlin` module wraps `ServerBuilder` with a coroutine-first DSL, suspend tool handlers, and type-safe scope classes.

## Dependency

Version is pinned by the `tachyon-bom` — see [Quickstart](../quickstart.md#1-add-the-dependency).

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-kotlin</artifactId>
</dependency>
```

## Entry points

```kotlin
// Start Netty transport — returns the Kotlin TachyonServer
val server = TachyonServer(port = 8080) { /* configure */ }

// Server logic only, no transport — for testing
val server: TachyonServer = buildServer { /* configure */ }
```

Both entry points configure a `TachyonServerBuilder`. `TachyonServer(port)` also binds the
transport and starts serving; `buildServer` returns a configured server you start yourself, which
is what you want in tests.

## Structured value factories

Kotlin factories use receiver blocks for structured values with more than three fields.
`Annotations` follows the same shape because it is commonly nested inside descriptors:

```kotlin
val annotations = Annotations {
    audience = listOf(Role.USER)
    priority = 0.8
}

val icon = Icon {
    src = "https://example.com/icon.svg"
    mimeType = "image/svg+xml"
    sizes = listOf("any")
    theme = "light"
}
```

Required fields fail fast when the block finishes. Flat overloads remain available for source
compatibility, but new Kotlin code should use receiver factories for `Icon`, `Annotations`,
content objects, and resource, prompt, and tool descriptors.

## Full example

```kotlin
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.api.server.domain.PromptMessage
import dev.tachyonmcp.api.server.domain.TextResourceContents
import dev.tachyonmcp.api.server.features.tools.ToolResult

val server = TachyonServer(port = 8080) {
    info {
        name = "demo-server"
        version = "1.0"
        description = "Demo MCP server"
    }
    capabilities {
        tools { listChanged = true }
        resources {
            subscribe = true
            listChanged = true
        }
        prompts { listChanged = true }
    }
    session {
        sessionTtl = 5.minutes
        sessionIdGenerator = SessionIdGenerator { _, _ -> "sess_" + Uuid.random().toHexString() }
    }
    tool(name = "ping", description = "Ping the server") {
        ToolResult.text("pong")
    }
    runtime {
        shutdownGracePeriod = 5.seconds
    }
    resource(
        name = "config",
        uri = "demo://config",
        description = "Server configuration",
        mimeType = "application/json",
    ) {
        TextResourceContents {
            uri = this@resource.uri
            text = """{"env":"prod"}"""
            mimeType = "application/json"
        }
    }
    prompt(name = "greet", description = "Greeting prompt") {
        listOf(PromptMessage.user("Say hello, ${arguments.stringOr("name", "world")}"))
    }
}
```

## Tool handlers

Tool lambdas are `suspend` functions with access to `ToolScope`, including `ctx`, `request`,
and `arguments`. Start with a simple string tool. Save this as `src/main/kotlin/MyMcpServer.kt`
in a Kotlin JVM project with the dependency above and JDK 21:

```kotlin
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.kotlin.server.buildServer

fun main() {
    val server = buildServer {
        capabilities { tools { mode = Mode.ON } }
        info {
            name = "echo-server"
            version = "1.0"
        }
        network {
            host = "127.0.0.1"
            port = 8080
        }
    }
    server.registerTool(
        name = "reverse-echo",
        description = "Echo reverse message",
        inputSchema = JsonSchema.unchecked(
            """
            {
              "type": "object",
              "properties": {
                "message": {"type": "string", "description": "Message to echo"}
              },
              "required": ["message"]
            }
            """,
        ),
    ) {
        text(arguments.stringValue("message").reversed())
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    server.start()
}
```

Call `reverse-echo` with `{"message":"stressed"}` to receive the text `desserts`.
The input schema requires a string `message`; this tool needs no payload data classes or
serialization compiler plugin.

The [echo-kotlin project](https://github.com/tachyonmcp/tachyon/tree/main/examples/echo-kotlin)
pairs this pattern with a typed echo tool. It also shows registering the simple tool after
`buildServer`, using `server.registerTool`. The next [typed example](#typed-tools) uses
kotlinx.serialization for the payloads.

For the experimental class-based escape hatch, extend `AbstractToolHandler` and override `handle(ctx, request)` (sync) or `handleAsync(ctx, request)` (async).

## Resource & prompt handlers

Resource and prompt lambdas are `suspend` functions too — call suspending APIs directly:

```kotlin
resource(
    name = "config",
    uri = "demo://config",
    description = "Application configuration",
    mimeType = "application/json",
    title = "Configuration",
    annotations = Annotations { priority = 0.8 },
    size = 1024,
    icons = listOf(Icon { src = "https://example.com/config.svg" }),
    meta = mapOf("owner" to "team-x"),
) {
    // this: ResourceScope — ctx, uri, params, uriTemplate
    val config = fetchConfig()  // suspend call
    TextResourceContents { text = config }
}

prompt(name = "greet", description = "Greeting prompt") {
    // this: PromptScope — ctx, request, arguments
    listOf(PromptMessage.user("Hello, ${arguments.stringOr("name", "world")}"))
}
```

`prompt(...)` accepts the full `PromptDescriptor` attribute set as named params too:

```kotlin
prompt(
    name = "rewrite",
    description = "Rewrites text in a style",
    title = "Rewrite Tool",
    arguments =
        listOf(
            PromptArgument {
                name = "style"
                required = false
            },
        ),
    inputSchema = schema,
    icons = listOf(Icon { src = "https://example.com/rewrite.svg" }),
    meta = mapOf("owner" to "team-x"),
) {
    val style = arguments.stringOrNull("style") ?: "a neutral"
    listOf(PromptMessage.user("Rewrite this in $style style"))
}
```

`extensionId` is deliberately not one of these named params — it marks a
resource/resourceTemplate/tool/prompt as owned by a specific extension (gating its visibility to
sessions that negotiated that extension; see [Extensions](../extensions/)) and is meant for
extension implementations, not ordinary server code. Set it through the descriptor scope instead,
e.g. `resourceDescriptor(name, uri) { extensionId = MY_EXTENSION_ID }` or
`ResourceTemplateDescriptor { extensionId = MY_EXTENSION_ID }`, then pass the built descriptor to
the `resource(descriptor) { }` or `resourceTemplate(descriptor) { }` overload.

Inside a resource handler, `TextResourceContents { }` and `BlobResourceContents { }` default `uri`
to the requested URI and `mimeType` to the registered resource MIME type. You can override either
property.

For metadata shared across registrations, pass a prebuilt descriptor:

```kotlin
val descriptor = ResourceDescriptor {
    name = "config"
    uri = "demo://config"
    description = "Application configuration"
    mimeType = "application/json"
    title = "Configuration"
}

resource(descriptor) {
    TextResourceContents { text = fetchConfig() }
}
```

Handlers run in a server-lifecycle coroutine scope on the server executor. They bridge to the Java
registries through asynchronous handlers, without blocking a virtual thread. Closing the server
cancels active Kotlin handlers before shutting down the executor.

### Resource templates

Template metadata stays in named parameters. The trailing `block` handles matched requests:

```kotlin
resourceTemplate(
    name = "user-profile",
    uriTemplate = "user://{userId}/profile",
    description = "User profile template",
    mimeType = "application/json",
    title = "User profile",
    annotations = Annotations { priority = 0.8 },
    icons = listOf(
        Icon {
            src = "https://example.com/user.svg"
            mimeType = "image/svg+xml"
        },
    ),
) {
    TextResourceContents {
        text = """{"id":"${param("userId")}"}"""
    }
}
```

Template handlers use the same contextual defaults. Their text builder also exposes `param(name)`
and `sequence(name)`.

For a descriptor shared across registrations, build it once and use the descriptor overload:

```kotlin
val descriptor = ResourceTemplateDescriptor {
    name = "document"
    uriTemplate = "docs://{path}"
    description = "Documentation"
    mimeType = "text/markdown"
}

resourceTemplate(descriptor) {
    TextResourceContents {
        text = loadDocument(param("path"))
    }
}
```

## Tool schemas

`inputSchema` / `outputSchema` accept three shapes on every registration overload
(`tool(...)` in the DSL, `TachyonServer.registerTool(...)` post-build, `ToolDescriptor { }`):

```kotlin
// Jackson JsonNode
tool("a", inputSchema = jacksonNode) { /* ... */ }

// Raw JSON string — parsed by Tachyon
tool(
    "b",
    inputSchema = """{"type":"object","properties":{"msg":{"type":"string"}}}""",
    outputSchema = """{"type":"object","properties":{"echo":{"type":"string"}}}""",
) { /* ... */ }

// kotlinx.serialization JsonObject — requires kotlinx-serialization-json (optional)
tool("c", inputSchema = buildJsonObject { put("type", "object") }) { /* ... */ }
```

Schema roots are validated at registration time: `inputSchema` must declare `"type": "object"`
(tool-call arguments are always an object) or registration fails fast with
`IllegalArgumentException` instead of surfacing later in the MCP client. `outputSchema` accepts
any JSON Schema root — object, array, or scalar (see [Return results](../features/tools.md#return-results)
for the per-protocol-version wire behaviour). Tool descriptions longer than 2048 characters log a
warning — clients may truncate them.

## Typed tools

`typedTool<In, Out>` derives both schemas from your Kotlin types, decodes the call arguments into
`In`, and encodes the returned `Out` as `structuredContent` — no schema literals:

Add `tachyon-kotlin-kt-schema` for runtime schema generation and configure the
[serialization dependency and compiler plugin](kt-schema-json.md#typed-echo-and-simple-reverse-tools).
This complete server uses the echo project's `message` input and `reply` output:

```kotlin
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlinx.serialization.Serializable

@Serializable
data class EchoRequest(val message: String)

@Serializable
data class EchoResponse(val reply: String)

fun main() {
    val server = TachyonServer(port = 8080) {
        info {
            name = "echo-server"
            version = "1.0"
        }
        network { host = "127.0.0.1" }
        json { serde = KxSerializationSerde.Default }
        typedTool<EchoRequest, EchoResponse>(
            name = "echo",
            description = "Echo message",
        ) { input ->
            EchoResponse(input.message)
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}
```

Call `echo` with `{"message":"Hello, MCP!"}`. The result contains
`structuredContent: {"reply":"Hello, MCP!"}` and a text block with that JSON.
The handler receives the decoded `EchoRequest` directly.

Both type arguments must be given explicitly — neither is inferable from the block. The same
registration exists post-build as `server.registerTool<In, Out>(...)`.

The block may return either shape:

- an `Out` — wrapped as a success result carrying it as `structuredContent`;
- a `ToolResult` — passed through untouched, for results that also need `_meta`, a custom text
  block, extra content blocks, `fail(...)` or `inputRequired(...)`.

The two never collide: `ToolResult` is a sealed interface, so no `Out` can also be one.

### Where the schemas come from

`typedTool` resolves schemas through `JsonSchema.generate`, which walks the registered
`JsonSchemaFactory` chain in priority order:

| Source | Provided by |
|---|---|
| Build-time schema resource from the kt-schema annotation processor | `tachyon-core` |
| Runtime reflection over the class | `tachyon-kotlin-kt-schema` |

Add the reflection back-stop to use `typedTool` without generating resources at build time:

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-kotlin-kt-schema</artifactId>
</dependency>
```

It registers itself through `META-INF/services`, so no wiring is needed. To control generation for
one call, pass `schemaGenerator`:

```kotlin
import dev.tachyonmcp.kotlin.server.json.ktschema.ktSchemaGenerator
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig

typedTool<EchoRequest, EchoResponse>(
    name = "echo",
    schemaGenerator = ktSchemaGenerator(JsonSchemaConfig.Default),
) { input -> EchoResponse(input.message) }
```

> **A Kotlin default does not make a property optional.** Out of the box the generated schema
> marks a defaulted property `required`, so adding `val units: String = "metric"` to
> `EchoRequest` would still force every caller to send it. Pass
> `schemaGenerator = ktSchemaGenerator(JsonSchemaConfig.Default)` to let nullable and defaulted
> properties be omitted instead.

`typedTool` and `tachyon-kotlin-kt-schema` are `@ExperimentalApi` — the shape may still change.

## kotlinx.serialization integration

`kotlinx-serialization-json` is an **optional** dependency of `tachyon-kotlin`.
Add it to use `JsonObject` schemas, `arguments.decode<T>()`, and `success(value)`:

```xml
<dependency>
    <groupId>org.jetbrains.kotlinx</groupId>
    <artifactId>kotlinx-serialization-json</artifactId>
</dependency>
```

```kotlin
@Serializable data class EchoArgs(val message: String, val loud: Boolean = false)

@Serializable data class EchoReply(val echo: String)

tool(
    "echo",
    inputSchema = """{"type":"object","properties":{"message":{"type":"string"}}}""",
    outputSchema = """{"type":"object","properties":{"echo":{"type":"string"}}}""",
) {
    val input = arguments.decode<EchoArgs>() // typed decode via configured serde
    success(EchoReply(input.message)) // structuredContent via configured serde
}
```

The Kotlin DSL retains Tachyon's Jackson serde by default. Select kotlinx serialization explicitly:
`json { serde = KxSerializationSerde.Default }`. Configure a strict `Json` via
`json { serde = KxSerializationSerde(Json { ignoreUnknownKeys = false }) }`.
`success(value)` encodes via the configured serde and pairs with the declared `outputSchema` —
the resulting JSON must match whatever shape that schema declares (object, array, or scalar; see
[Return results](../features/tools.md#return-results) for the per-protocol-version wire behaviour).
For a pre-serialized JSON payload that bypasses the serde, use `ToolResult.raw(json, text)`.

### Typed decode/result via configured serde

Kotlin extensions bridge the gap between the existing Java typed API and the
configured serde in the Kotlin DSL:

| Method | Routes through | Behaviour |
|---|---|---|
| `request.arguments().decode<T>()` | server-configured `PayloadDeserializer` | Honors custom `Json` config |
| `scope.success(value)` | server-configured `PayloadSerializer` | Deferred serialization at encode time |
| `scope.success(value, text)` | server-configured `PayloadSerializer` | Structured + human-readable text |

`decode<T>` uses `T::class.java → Args.decode(Class<T>)`, which routes
through the deserializer set in `json { serde = ... }`.

```kotlin
@Serializable data class GreetArgs(val name: String, val greeting: String = "Hello")
@Serializable data class GreetReply(val message: String)

tool(
    name = "greet",
    inputSchema = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
    outputSchema = """{"type":"object","properties":{"message":{"type":"string"}}}""",
) {
    val input = arguments.decode<GreetArgs>() // honors configured serde
    success(GreetReply("${input.greeting}, ${input.name}!"), "greeting response")  // symmetric typed result
}
```

[`typedTool`](#typed-tools) does the same thing without the schema literals.

## Args accessors

Available via `ToolScope.arguments` (or `PromptScope.arguments`):

| Call | Behaviour |
|---|---|
| `arguments.stringValue("k")` / `intValue` / `boolValue` / `doubleValue` | Required — throws when missing |
| `arguments.stringOrNull("k")` / `intOrNull` / `booleanOrNull` / `doubleOrNull` | Returns `null` when missing |
| `arguments.stringOr("k", "d")` / `int("k", 0)` / `boolean("k", true)` / `double("k", 0.0)` | Falls back to default |
| `arguments.decode<T>()` | typed decode via configured serde (Jackson by default) |

## Scope reference

| Scope | Builder method | Properties |
|---|---|---|
| `ServerInfoScope` | `info { }` | `name`, `version`, `description`, `title`, `instructions` |
| `CapabilitiesScope` | `capabilities { }` | `tools()`, `resources()`, `prompts()`, `tasks(connector)`, `logging`, `completionsMode` |
| `NetworkScope` | `network { }` | `host`, `port`, `endpointPath`, `allowedOrigins`, `allowedHeaders`, `allowedHosts`, `maxContentLength` |
| `SessionScope` | `session { }` | `enable()`, `sessionTtl`, `sessionIdGenerator` |
| `RuntimeScope` | `runtime { }` | `shutdownGracePeriod`, `requestTimeout`, `clock` |
| `ToolScope` | tool lambda | `ctx`, `request`, `arguments`; `success(v)`, `text(t)`, `fail(msg)`, `content { }` |
| `ResourceScope` | resource lambda | `ctx`, `uri`, `params`, `uriTemplate` |
| `TemplateScope` | resource-template lambda | `ctx`, `uri`, `params`, `uriTemplate`; contextual `TextResourceContents { }` |
| `PromptScope` | prompt lambda | `ctx`, `request`, `arguments`; `content { }` |
| `CompletionScope` | completion lambda | `ctx`, `request`, `argumentName`, `argumentValue`, `resolvedArguments` |

`argumentName`, `argumentValue`, and `resolvedArguments` are raw client input — escape or
allow-list before using them in a query, command, or path.

## Completions

`promptCompletion` answers `ref/prompt` refs by prompt name; `resourceCompletion` answers
`ref/resource` refs by URI or `uriTemplate`, matched verbatim against what the client sends. A ref
with no handler yields an empty result rather than an error. `CompletionResult { }` builds the
response (`values`, `total`, `hasMore`, `meta`); the protocol caps a response at 100 values and the
dispatcher truncates and forces `hasMore = true` beyond that.

```kotlin
TachyonServer(port = 8080) {
    promptCompletion("rewrite-forecast") {
        CompletionResult {
            values = listOf("plain", "concise", "pirate").filter { it.startsWith(argumentValue) }
        }
    }
    resourceCompletion("myapp://users/{userId}/profile") {
        CompletionResult {
            values = listOf("alice", "bob").filter { it.startsWith(argumentValue) }
            hasMore = false
        }
    }
}
```

## Post-build registration

Every builder-time registration function has a suspend `register*` twin on the built
`TachyonServer`, callable before or after `start()` — the Kotlin equivalent of Java's
`server.tools().register(...)`, `server.resources().register(...)`,
`server.prompts().register(...)`, and `server.completions().registerForPrompt/Resource(...)`.
Each takes either flat named parameters or a prebuilt descriptor.

```kotlin
val server = buildServer { /* base config */ }

server.registerTool(
    ToolDescriptor {
        name = "echo"
        description = "Echo a message"
    },
) {
    text(arguments.stringValue("msg"))
}

server.registerResource(name = "config", uri = "myapp://config") {
    TextResourceContents { text = """{"mode":"demo"}""" }
}

server.registerResourceTemplate(name = "user-profile", uriTemplate = "myapp://users/{userId}/profile") {
    TextResourceContents { text = """{"userId":"${param("userId")}"}""" }
}

server.registerPrompt(name = "rewrite-forecast") {
    content { text("Rewrite this forecast.") }
}

server.registerPromptCompletion("rewrite-forecast") {
    CompletionResult { values = listOf("plain", "concise", "pirate") }
}

server.registerResourceCompletion("myapp://users/{userId}/profile") {
    CompletionResult { values = listOf("alice", "bob") }
}
```

Duplicate handling differs per feature, and matters more here than at build time:

| Call | Registering an existing key |
|---|---|
| `registerTool`, `registerPrompt`, `registerPromptCompletion`, `registerResourceCompletion` | replaces silently |
| `registerResource` | replaces the same URI in place; throws `IllegalArgumentException` if that URI is held under a different name |
| `registerResourceTemplate` | throws `IllegalArgumentException` — unregister first to swap a handler |

When the matching capability is off, `register*` is a no-op that still returns the server, so a
misconfigured capability shows up as a missing feature rather than an exception.

## Netty pipeline customization

```kotlin
TachyonServer(port = 8080) {
    pipelineCustomizer {
        addLast("metrics", MetricsHandler())
    }
}
```

## Returning results

The `ToolResult` factories and the per-protocol-version wire behaviour of `structuredContent` are
documented once, in [Tools → Return results](../features/tools.md#return-results). They apply
unchanged in Kotlin.

Inside a tool lambda, prefer the `ToolScope` shortcuts instead:

| Shortcut | Equivalent |
|---|---|
| `text(t)` | `ToolResult.text(t)` |
| `success(v)` / `success(v, text)` | `ToolResult.structured(...)` via the configured serde |
| `fail(msg)` | `ToolResult.error(msg)` |
| `content { }` | `ToolResult.content(...)` |
| `raw(json, text)` | `ToolResult.raw(json, text)` |
| `empty()` | `ToolResult.empty()` |
| `inputRequired(...)` | `ToolResult.inputRequired(...)` |

The shortcut is `fail`, not `error`: a member `error(String)` would shadow Kotlin's stdlib
`error()`, turning a thrown `IllegalStateException` into a returned value.

With kotlinx-serialization on the classpath, prefer `success(value)` — the configured serde
encodes it into `structuredContent`. Without an explicit `text` argument, Tachyon emits the
serialized JSON as the backwards-compatible text block.

## Testing

Use `TachyonServer(port = 0) { }` for zero-setup E2E tests — it starts Netty on an ephemeral port:

```kotlin
val server = TachyonServer(port = 0) { tool("ping") { ToolResult.text("pong") } }
// server.host() → bound host, server.port() → ephemeral port
```

For a client to drive it with, see [Testkit](../testkit.md).

