---
title: Kotlin
overview_title: "Introduction"
weight: 55
sidebar_order: 55
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

[Tasks](../extensions/tasks.md#kotlin) are optional: add `tachyon-extensions-tasks` to use
`tasks(connector) { }`, `ToolScope.tasks`, or `TachyonServer.tasks`.

Import `dev.tachyonmcp.kotlin.server.config.tasks` for builder configuration.
[Skills](../extensions/skills.md#enable-the-extension) similarly use the imported
`dev.tachyonmcp.kotlin.server.config.skills` extension and require
`tachyon-extensions-skills` explicitly.

A minimal Gradle build for the examples below, with JDK 21 ([source](https://github.com/tachyonmcp/tachyon/blob/main/examples/doc-examples/kotlin-gradle/build.gradle.kts)):

<!-- snips: ../../examples/doc-examples/kotlin-gradle/build.gradle.kts#kotlin_gradle_build -->
```kotlin
plugins {
    kotlin("jvm") version "2.2.21"
    application
}

repositories { mavenCentral() }

val tachyonVersion = project.properties["tachyonVersion"]

dependencies {
    implementation(platform("dev.tachyonmcp:tachyon-bom:$tachyonVersion"))
    implementation("dev.tachyonmcp:tachyon-kotlin")
}

kotlin { jvmToolchain(21) }

application { mainClass = "MyMcpServerKt" }
```

## Entry points

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/ServerEntryPoints.kt#kotlin_entry_points -->
```kotlin
// Start Netty transport — returns the Kotlin TachyonServer
val server = TachyonServer(port = 8080) { /* configure */ }

// Server logic only, no transport — for testing
val testServer: TachyonServer = buildServer { /* configure */ }
```

Both entry points configure a `TachyonServerBuilder`. `TachyonServer(port)` also binds the
transport and starts serving; `buildServer` returns a configured server you start yourself, which
is what you want in tests. `start()` needs a port, so set `network { port = ... }` first.

## Tool handlers

Tool lambdas are `suspend` functions with access to `ToolScope`, including `ctx`, `request`,
and `arguments`. Start with a simple string tool. Save this as `src/main/kotlin/MyMcpServer.kt` ([source](https://github.com/tachyonmcp/tachyon/blob/main/examples/doc-examples/kotlin-gradle/src/main/kotlin/MyMcpServer.kt))
in a Kotlin JVM project with the build above, then run it with `gradle run`:

<!-- snips: ../../examples/doc-examples/kotlin-gradle/src/main/kotlin/MyMcpServer.kt#kotlin_reverse_echo_server -->
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

## Configuration example

This complete file configures server info, capabilities, sessions, and runtime settings, and registers a tool, a resource, and a prompt. Save it as `src/main/kotlin/DemoServer.kt` ([source](https://github.com/tachyonmcp/tachyon/blob/main/examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/DemoServer.kt))
in the project from [Tool handlers](#tool-handlers), and set `mainClass` to `DemoServerKt` to run it.
It starts a server on port 8080 and closes it when the JVM stops:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/DemoServer.kt#kotlin_demo_server -->
```kotlin
import dev.tachyonmcp.api.server.domain.PromptMessage
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun main() {
    val server =
        TachyonServer(port = 8080) {
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
                sessionIdGenerator {
                    _,
                    _,
                    ->
                    "sess_" + UUID.randomUUID().toString().replace("-", "")
                }
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
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}
```

## Structured value factories

Kotlin factories use receiver blocks for structured values with more than three fields.
`Annotations` follows the same shape because it is commonly nested inside descriptors:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/StructuredValues.kt#kotlin_structured_factories -->
```kotlin
val annotations =
    Annotations {
        audience = listOf(Role.USER)
        priority = 0.8
    }

val icon =
    Icon {
        src = "https://example.com/icon.svg"
        mimeType = "image/svg+xml"
        sizes = listOf("any")
        theme = "light"
    }
```

Required fields fail fast when the block finishes. Flat overloads remain available for source
compatibility, but new Kotlin code should use receiver factories for `Icon`, `Annotations`,
content objects, and resource, prompt, and tool descriptors.

Experimental binary icons accept raw bytes and encode `src` as a Base64 data URI:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/StructuredValues.kt#kotlin_binary_icon -->
```kotlin
val icon =
    Icon {
        data = imageBytes
        mimeType = "image/png"
        sizes = listOf("32x32")
    }
```

Set either `src` or `data`. Binary data must be nonempty and requires a nonblank `mimeType`.
Java callers can use the experimental `Icon.of(bytes, mimeType, sizes, theme)` overload.

## Resource & prompt handlers

Resource and prompt lambdas are `suspend` functions too — call suspending APIs directly:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/HandlerExamples.kt#kotlin_resource_handler -->
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
    val config = fetchConfig() // suspend call
    TextResourceContents { text = config }
}

prompt(name = "greet", description = "Greeting prompt") {
    // this: PromptScope — ctx, request, arguments
    listOf(PromptMessage.user("Hello, ${arguments.stringOr("name", "world")}"))
}
```

`prompt(...)` accepts the full `PromptDescriptor` attribute set as named params too:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/HandlerExamples.kt#kotlin_prompt_attributes -->
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

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/HandlerExamples.kt#kotlin_resource_descriptor -->
```kotlin
val descriptor =
    ResourceDescriptor {
        name = "config"
        uri = "demo://config"
        description = "Application configuration"
        mimeType = "application/json"
        title = "Configuration"
    }

resource(descriptor) {
    val config = fetchConfig() // suspend call
    TextResourceContents { text = config }
}
```

Handlers run in a server-lifecycle coroutine scope on the server executor. They bridge to the Java
registries through asynchronous handlers, without blocking a virtual thread. Closing the server
cancels active Kotlin handlers before shutting down the executor.

### Resource templates

Template metadata stays in named parameters. The trailing `block` handles matched requests:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/HandlerExamples.kt#kotlin_resource_template -->
```kotlin
resourceTemplate(
    name = "user-profile",
    uriTemplate = "user://{userId}/profile",
    description = "User profile template",
    mimeType = "application/json",
    title = "User profile",
    annotations = Annotations { priority = 0.8 },
    icons =
        listOf(
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

Template handlers use the same contextual defaults. Both `TextResourceContents { }` and
`BlobResourceContents { }` expose `param(name)` and `sequence(name)` inside their builder blocks.

For a descriptor shared across registrations, build it once and use the descriptor overload:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/HandlerExamples.kt#kotlin_resource_template_descriptor -->
```kotlin
val descriptor =
    ResourceTemplateDescriptor {
        name = "document"
        uriTemplate = "docs://{path}"
        description = "Documentation"
        mimeType = "text/markdown"
    }

resourceTemplate(descriptor) {
    val document = loadDocument(param("path")) // suspend call
    TextResourceContents { text = document }
}
```

## Tool schemas

`inputSchema` / `outputSchema` accept a `JsonSchema` or a kotlinx.serialization `JsonObject` on `tool(...)` in the
DSL and `TachyonServer.registerTool(...)` post-build. The DSL overload that takes a JSON string is deprecated; use
`JsonSchema.parse(...)`:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/ToolExamples.kt#kotlin_tool_schema_shapes -->
```kotlin
// JsonSchema — parse a JSON string, or generate one from a class
tool(
    "a",
    inputSchema =
        JsonSchema.parse(
            """{"type":"object","properties":{"msg":{"type":"string"}}}""",
        ),
    outputSchema =
        JsonSchema.parse(
            """{"type":"object","properties":{"echo":{"type":"string"}}}""",
        ),
) { text("a") }

// kotlinx.serialization JsonObject — requires kotlinx-serialization-json (optional)
tool("b", inputSchema = buildJsonObject { put("type", "object") }) { text("b") }
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
This complete server ([source](https://github.com/tachyonmcp/tachyon/blob/main/examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/TypedEchoServer.kt)) uses the echo project's `message` input and `reply` output:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/TypedEchoServer.kt#kotlin_typed_echo_server -->
```kotlin
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlinx.serialization.Serializable

@Serializable
data class EchoRequest(
    val message: String,
)

@Serializable
data class EchoResponse(
    val reply: String,
)

fun main() {
    val server =
        TachyonServer(port = 8080) {
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
one call, pass `schemaGenerator` (`ktSchemaGenerator` lives in `dev.tachyonmcp.kotlin.server.json.ktschema`,
`JsonSchemaConfig` in `me.kpavlov.kt.schema.generator.json`):

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/ToolExamples.kt#kotlin_typed_tool_schema_generator -->
```kotlin
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

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/ToolExamples.kt#kotlin_serialization_types -->
```kotlin
@Serializable
data class EchoArgs(
    val message: String,
    val loud: Boolean = false,
)

@Serializable
data class EchoReply(
    val echo: String,
)
```

Register a tool that decodes the arguments and returns the reply:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/ToolExamples.kt#kotlin_serialization_tool -->
```kotlin
json { serde = KxSerializationSerde.Default }

tool(
    "echo",
    inputSchema =
        JsonSchema.parse(
            """{"type":"object","properties":{"message":{"type":"string"}}}""",
        ),
    outputSchema =
        JsonSchema.parse(
            """{"type":"object","properties":{"echo":{"type":"string"}}}""",
        ),
) {
    val input = arguments.decode<EchoArgs>() // typed decode via configured serde
    success(EchoReply(input.message)) // structuredContent via configured serde
}
```

The Kotlin DSL retains Tachyon's Jackson serde by default, which cannot decode Kotlin data classes.
Select kotlinx serialization explicitly, as above: `json { serde = KxSerializationSerde.Default }`. Configure a strict `Json` via
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

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/ToolExamples.kt#kotlin_greet_types -->
```kotlin
@Serializable
data class GreetArgs(
    val name: String,
    val greeting: String = "Hello",
)

@Serializable
data class GreetReply(
    val message: String,
)
```

Register a tool that decodes them and returns a typed result with explicit text:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/ToolExamples.kt#kotlin_greet_tool -->
```kotlin
tool(
    name = "greet",
    inputSchema =
        JsonSchema.parse(
            """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        ),
    outputSchema =
        JsonSchema.parse(
            """{"type":"object","properties":{"message":{"type":"string"}}}""",
        ),
) {
    val input = arguments.decode<GreetArgs>() // honors configured serde
    success(
        GreetReply("${input.greeting}, ${input.name}!"),
        "greeting response",
    ) // symmetric typed result
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

The `...OrNull` and default-value accessors and `decode<T>()` are extensions from `dev.tachyonmcp.kotlin.server.domain`.

## Scope reference

| Scope | Builder method | Properties |
|---|---|---|
| `ServerInfoScope` | `info { }` | `name`, `version`, `description`, `title`, `instructions` |
| `CapabilitiesScope` | `capabilities { }` | `tools()`, `resources()`, `prompts()`, `logging`, `completionsMode` |
| `TasksScope` | `tasks(connector) { }` (needs `tachyon-extensions-tasks`) | `pageSize`, `keepAlive`, `pollInterval`, `resultPollInterval` |
| `SkillsScope` | `skills(registry) { }` (needs `tachyon-extensions-skills`) | `cacheTtl`, `cacheScope`, `negotiation`, `registry(...)` |
| `NetworkScope` | `network { }` | `host`, `port`, `endpointPath`, `allowedOrigins`, `allowedHeaders`, `allowedHosts`, `maxContentLength`, `maxPipelinedRequests`, `maxPendingSseBytes`, `sseStallTimeout` |
| `SessionScope` | `session { }` | `enable()`, `sessionTtl`, `sessionIdGenerator` |
| `RuntimeScope` | `runtime { }` | `shutdownGracePeriod`, `requestTimeout`, `clock` |
| `ToolScope` | tool lambda | `ctx`, `request`, `arguments`, `tasks`; `success(v)`, `text(t)`, `fail(msg)`, `content { }` |
| `ResourceScope` | resource lambda | `ctx`, `uri`, `params`, `uriTemplate` |
| `TemplateScope` | resource-template lambda | `ctx`, `uri`, `params`, `uriTemplate`; contextual `TextResourceContents { }` |
| `PromptScope` | prompt lambda | `ctx`, `request`, `arguments`; `content { }` |
| `CompletionScope` | completion lambda | `ctx`, `request`, `argumentName`, `argumentValue`, `resolvedArguments` |

Within `network { }`, `address` is mutually exclusive with `host` or `port`.
Within `json { }`, `serde` is mutually exclusive with `serializer` or `deserializer`.
Conflicting settings throw `IllegalArgumentException` in the Kotlin scopes; the Java network
builder throws `IllegalStateException` for conflicting address settings.

`argumentName`, `argumentValue`, and `resolvedArguments` are raw client input — escape or
allow-list before using them in a query, command, or path.

## Completions

`promptCompletion` answers `ref/prompt` refs by prompt name; `resourceCompletion` answers
`ref/resource` refs by URI or `uriTemplate`, matched verbatim against what the client sends. A ref
with no handler yields an empty result rather than an error. `CompletionResult { }` builds the
response (`values`, `total`, `hasMore`, `meta`); the protocol caps a response at 100 values and the
dispatcher truncates and forces `hasMore = true` beyond that.

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/RegistrationExamples.kt#kotlin_completions -->
```kotlin
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
```

## Post-build registration

Every builder-time registration function has a `register*` twin accepting a suspend handler on the built
`TachyonServer`, callable before or after `start()` — the Kotlin equivalent of Java's
`server.tools().register(...)`, `server.resources().register(...)`,
`server.prompts().register(...)`, and `server.completions().registerForPrompt/Resource(...)`.
Each takes either flat named parameters or a prebuilt descriptor.

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/RegistrationExamples.kt#kotlin_post_build_registration -->
```kotlin
val server = buildServer { network { port = 0 } }

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

server.registerResourceTemplate(
    name = "user-profile",
    uriTemplate = "myapp://users/{userId}/profile",
) {
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

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/RegistrationExamples.kt#kotlin_pipeline_customizer -->
```kotlin
pipelineCustomizer {
    addFirst("metrics", MetricsHandler())
}
```

The customizer runs after Tachyon's own handlers, so a handler added with `addLast` never sees inbound requests; use `addFirst`.

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
| `fail { }` | `ToolResult.error(...)` |
| `content { }` | `ToolResult.content(...)` |
| `raw(json, text)` | `ToolResult.raw(json, text)` |
| `empty()` | `ToolResult.empty()` |
| `inputRequired(...)` | `ToolResult.inputRequired(...)` |

Tool/prompt `content { }` and tool `fail { }` declare `EXACTLY_ONCE`, enabling local `val`
assignment inside the block.

Inside these blocks, `image(data, mimeType)` and `audio(data, mimeType)` accept raw `ByteArray`
data. Decode existing Base64 strings with `Base64.getDecoder().decode(data)` before passing them.

The shortcut is `fail`, not `error`: a member `error(String)` would shadow Kotlin's stdlib
`error()`, turning a thrown `IllegalStateException` into a returned value.

With kotlinx-serialization on the classpath, prefer `success(value)` — the configured serde
encodes it into `structuredContent`. Without an explicit `text` argument, Tachyon emits the
serialized JSON as the backwards-compatible text block.

## Testing

Use `TachyonServer(port = 0) { }` for zero-setup E2E tests — it starts Netty on an ephemeral port:

<!-- snips: ../../examples/doc-examples/src/main/kotlin/dev/tachyonmcp/docs/kotlin/ServerEntryPoints.kt#kotlin_testing_server -->
```kotlin
val server = TachyonServer(port = 0) { tool("ping") { ToolResult.text("pong") } }
// server.host() → bound host, server.port() → ephemeral port
```

For a client to drive it with, see [Testkit](../testkit.md).
