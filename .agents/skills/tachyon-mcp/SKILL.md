---
name: tachyon-mcp
description: Build MCP (Model Context Protocol) servers using the [Tachyon MCP](https://github.com/tachyonmcp/tachyon).
compatibility: Designed for Claude Code on JDK 21+ projects
version: 1.0.0-beta.28
metadata:
    author: Konstantin Pavlov
---
# Tachyon MCP Server Skill️

Make **Java 21+** MCP server. Tachyon lib. Transport = Streamable HTTP (Netty).

## Dependency

Import `tachyon-bom` once, then add modules with no `<version>`:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.tachyonmcp</groupId>
            <artifactId>tachyon-bom</artifactId>
            <version>1.0.0-beta.28</version> <!-- get latest version from Maven Central -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>dev.tachyonmcp</groupId>
        <artifactId>tachyon-core</artifactId> <!-- or tachyon-kotlin for the Kotlin DSL -->
    </dependency>
</dependencies>
```

## Core

- `TachyonServer.builder()` → `ServerBuilder`. Start here.
- `.build()` (only terminal method) → `TachyonServer` (`AutoCloseable`), no transport bound yet.
- `TachyonServer.start()` (blocking) → binds the Netty transport.
- `TachyonServer`: `.tools()`, `.resources()`, `.prompts()`, `.tasks()`, `.completions()` first; then `.start()`, `.port()` (throws before `.start()`), `.close()`, `.config()`.
- Dynamic registration: `.tools().register(...)`, `.resources().register(...)`, `.prompts().register(...)`, `.completions().registerForPrompt(...)`/`.registerForResource(...)` — all work before or after `.start()`.
- Every function gets `dev.tachyonmcp.api.runtime.InteractionContext` → protocol + optional session + notifications.
- ⚡ **Virtual threads**: All synchronous functions (`ToolFn`, `ResourceFn`, `PromptFn`, `CompletionFn`) run on a virtual thread per request. Blocking for I/O is fine — never use `synchronized` (pins carrier thread). Use `ReentrantLock` instead.

## Quickstart

```java
var server = TachyonServer.builder()
    .info(b -> b.name("my-server").version("1.0"))
    .port(8080)
    .build();
server.start();
// server.port() → real bound port (matters when port=0); throws before start()
```

## `ServerBuilder` methods

| Method | What |
|---|---|
| `.info(cfg)` | name, version, description, title, websiteUrl, instructions |
| `.capabilities(cfg)` | tools/resources/prompts/tasks/completions/logging |
| `.session(cfg)` | enabled (off by default = stateless), sessionTtl, SessionIdGenerator, experimental persistence stores |
| `.network(cfg)` | host, port, endpointPath, timeouts, CORS, maxContentLength, ioEngine |
| `.runtime(cfg)` | shutdownGracePeriod, requestTimeout, clock |
| `.monitoring(cfg)` | slow-request diagnostics (off by default) |
| `.name(s)` `.port(p)` | shorthands |
| `.withTools(registrar)` | bootstrap through `Tools.register/registerAsync` |
| `.withResources(registrar)` | bootstrap resources/templates through their façade |
| `.withPrompts(registrar)` | bootstrap through `Prompts.register/registerAsync` |
| `.withCompletions(registrar)` | bootstrap completion functions |
| `.withExtensions(ext...)` | `ServerExtension` plugin(s), vararg — `.extension(ext)` still works but is deprecated |
| `.json(cfg)` | serde + input/output schema validators |
| ~~`.jsonSchemaValidator(v)`~~ | removed — use `.json(cfg -> cfg.inputSchemaValidator(v).outputSchemaValidator(v))` |
| `.pipelineCustomizer(c)` | raw Netty pipeline escape hatch |

## Tools 🔧

Use independent `ToolFn` and `AsyncToolFn` SAMs. Both receive the full `ToolRequest`; call
`request.arguments()` for parsed `Args`.

```java
server.tools().register(
    ToolDescriptor.builder().name("hello").description("Say hello").build(),
    (ctx, request) -> ToolResult.text(
        "Hello, " + request.arguments().stringOr("name", "world")));
```

Use `registerAsync(descriptor, fn)` for a `CompletionStage`. `ToolHandler` and
`AbstractToolHandler` are experimental class-based escape hatches, not registration types.

`ToolResult` (not generic): `.text(t)` · `.error(msg)` (isError=true) · `.content(ContentBlock...)` · `.of(payload)` (structuredContent; serialized JSON auto-added as text block) · `.of(payload, text)` · `.raw(json, text)` (pre-serialized JSON) · `.inputRequired(reqs, state)` · `.empty()` · `.withMeta(map)` / `.withMeta(key, value)`

Full: `resources/java/ToolHandlerExample.java`

## Resources

Static fixed URI:

```java
.resource(
    resource -> resource
        .name("name")
        .uri("myapp://data/item")
        .description("Description")
        .mimeType("application/json"),
    (ctx, request) ->
        TextResourceContents.of(request.uri(), jsonData, "application/json"))
```

Template via builder:

```java
.resourceTemplate(
    template -> template
        .name("template-name")
        .uriTemplate("myapp://data/{id}")
        .description("Description")
        .mimeType("application/json"),
    (ctx, request) -> {
        var id = ((UriTemplateValue.Scalar) request.params().get("id")).value();
        return TextResourceContents.of(request.uri(), data, "application/json");
    })
```

Static resources and templates share `ResourceFn`. Its canonical shape is
`(InteractionContext, ResourceRequest)`. `ResourceRequest` carries `uri`, immutable template
`params`, nullable `uriTemplate`, and request `_meta`. `UriTemplate` performs matching. Static
resources get an empty params map and null template. Static functions may ignore unused request
fields, but still receive the full request.

Use explicit async methods. Sync functions run on virtual threads.

```java
server.resources().registerAsync(
    ResourceDescriptor.builder().name("config").uri("myapp://config").build(),
    (ctx, request) -> httpClient.sendAsync(httpRequest, BodyHandlers.ofString())
        .thenApply(rsp -> TextResourceContents.of(request.uri(), rsp.body(), "application/json")));
```

Full: `resources/java/ResourceFnExample.java`

## Prompts

Sync returns `PromptResult`:

```java
.prompt(
    prompt -> prompt
        .name("rewrite-forecast")
        .description("Rewrites a forecast in a given style"),
    (ctx, request) ->
        PromptResult.messages(List.of(PromptMessage.user("Rewrite this in pirate style."))))
```

Async returns `CompletionStage`:

```java
.asyncPrompt(
    prompt -> prompt.name("rewrite-forecast"),
    (ctx, request) -> service.rewrite(request)
        .thenApply(message -> PromptResult.messages(List.of(PromptMessage.user(message)))))
```

Full: `resources/java/PromptFnExample.java`

## Config️

### Capabilities `capabilities(cfg -> ...)`

Configs: `FeatureConfig` (tools/prompts: `mode`, `listChanged`, `pageSize`), `ResourcesConfig` (+ `subscribe`), 
`TasksConfig` (`enabled`, `connector` (a `TaskConnector`), `pageSize`, 
`keepAlive` (default 5 min — retention window for a terminal task's result), 
`pollInterval` (default none — suggested `tasks/get` polling cadence, wire-visible); `list`/`cancel`/`requests` 
are derived read-only accessors reflecting which `TaskConnector` operations are wired, not settable flags).

`TaskConnector.builder().get(fn).cancel(fn).update(fn).list(fn).awaitResult(fn).build()` — `get`,
`cancel`, and `update` are required by the modern Tasks extension. `list`/`awaitResult` are optional
and `@Deprecated(forRemoval = false)`: legacy MCP 2025-11-25
(pre-SEP-2663) surface, kept for compatibility.

Default `Mode.AUTO` advertises only registered features. Force `Mode.ON`/`Mode.OFF`. **`OFF` also blocks registration**: registry `register` becomes a debug-logged no-op, not merely hidden from `initialize`.

| Method | Effect |
|---|---|
| `.tools(FeatureConfig)` / `.resources(ResourcesConfig)` / `.prompts(FeatureConfig)` / `.tasks(TasksConfig)` | set the full nested config |
| `.tools()` / `.tools(listChanged)` / `.noTools()` | shortcut: tools |
| `.resources()` / `.resources(subscribe, listChanged)` / `.noResources()` | shortcut: resources |
| `.prompts()` / `.prompts(listChanged)` / `.noPrompts()` | shortcut: prompts |
| `.tasks(connector)` | tasks with a `TaskConnector` — also auto-registers the `io.modelcontextprotocol/tasks` wire extension |
| `.toolsMode(m)` / `.toolsListChanged(b)` / `.toolsPageSize(n)` (+ `resources*`/`prompts*`/`tasks*` siblings) | flat per-field setters; chain onto the shortcuts above, e.g. `c.tools().toolsPageSize(20)` |
| `.completions()` | arg autocomplete |
| `.logging()` | logging notifications |

Kotlin DSL nests instead:
`capabilities { tools { mode = Mode.ON; pageSize = 20 }; tasks(taskConnector) { pollInterval = 1.seconds } }`.

Enable logging before publishing structured messages from a handler. `log` accepts every MCP
severity; `info`, `warning`, and `error` are conveniences. The client-selected threshold is applied
per session, with `INFO` used until the client sends `logging/setLevel`.

```java
.capabilities(c -> c.logging())
.tool(
    tool -> tool.name("work").description("Does work"),
    (context, args) -> {
        context.notifications().log(
            LoggingLevel.NOTICE,
            "jobs",
            Map.of("status", "started"));
        return ToolResult.empty();
    })
```

### Network `network(cfg -> ...)`
| Method | Default |
|---|---|
| `.host(s)` | `127.0.0.1` |
| `.port(p)` | **required** before `start()` |
| `.endpointPath(p)` | `/mcp` |
| `.readerIdleTimeout(d)` / `.writerIdleTimeout(d)` | 60s / 5min |
| `.heartbeatInterval(d)` | 15s (`<= 0` disables) |
| `.maxContentLength(b)` | 1MB |
| `.allowedOrigins(...)` | none (all denied) |
| `.allowNullOrigin(b)` / `.allowPrivateNetworks(b)` | false |
| `.allowedHeaders(...)` | none |
| `.ioEngine(e)` | `NettyIoEngine.AUTO` (io_uring → epoll → kqueue → NIO) |

Native transports need optional runtime jars (`netty-transport-native-epoll` / `-kqueue` / `-io_uring` with `${os.detected.classifier}`); otherwise `AUTO` falls back to NIO. Explicit unavailable engines throw `UnsupportedOperationException`. See `docs/configuration.md`.

⏳ **Long-tool keep-alive**: `readerIdleTimeout` (60s) closes connections with no **inbound** bytes; waiting clients send none, so >60s tools are reaped mid-compute. Set it to `Duration.ZERO` to disable idle-inbound closing; don't merely raise it. Emit an early server→client message. POST upgrades to SSE; a scheduler sends `:\r\n` every `heartbeatInterval` (15s) for the whole run:
- `ctx.notifications().progress(token, ...)` — forward the client's `ToolRequest.progressToken()`; **`null` token is silently dropped** (no client opt-in) and sends nothing, so it does not keep the connection alive.
- `ctx.notifications().comment(msg)` — token-free SSE comment (`: msg`); `comment()` = bare `:` heartbeat. Use when no progress token, since a dropped `progress(null, ...)` sends nothing.

`progress(...)` needs the token from `ToolRequest`; `comment(...)` is available from every
handler's `InteractionContext` and needs no token.

**Long task ⇒ emit progress or comment first**. Keep `heartbeatInterval < readerIdleTimeout`; size `readerIdleTimeout` for dead-peer detection, not runtime.

### Session `session(cfg -> ...)`

| Method | Default |
|---|---|
| `.enabled(b)` | false (stateless) |
| `.sessionTtl(d)` | 30s |
| `.janitorInterval(d)` | 5s |
| `.sessionIdGenerator(g)` | `sess_<uuid8>` (derives id from initialize `HttpRequest`) |
| `.sessionEventStore(r)` / `.sessionStore(s)` | in-memory (experimental persistence SPIs) |

### Runtime `runtime(cfg -> ...)`

| Method | Default |
|---|---|
| `.shutdownGracePeriod(d)` | 5s (drain in-flight handlers on close; `ZERO` = interrupt now) |
| `.requestTimeout(d)` | 60s (timeout for pending requests sent to client) |
| `.clock(Clock)` | `Clock.systemUTC()` (task timestamps and TTL/expiry checks; inject a fixed or controllable clock in tests) |

### Monitoring `monitoring(cfg -> ...)`

| Method | Default |
|---|---|
| `.slowRequestLogging()` / `.slowRequestLogging(b)` | `false` (gate all slow-request diagnostics) |
| `.slowRequestThreshold(d)` | `10s` (slow-request threshold) |

## JSON Schema

`ToolDescriptor.builder().inputSchema(...)` / `.outputSchema(...)` accept a raw JSON `String`
or provider-neutral `JsonSchema` (same for `PromptDescriptor.builder().inputSchema(...)`):

```java
ToolDescriptor.builder()
    .name("get_weather")
    .inputSchema("""
        { "type": "object",
          "properties": { "city": { "type": "string", "description": "City name" } },
          "required": ["city"] }
        """)
    .build();
```

`ToolDescriptor.builder()` is no-arg only — the `builder(name)` / `builder(name, inJson, outJson)`
overloads have been removed, use `.name(...)` on the builder instead. `.tool(name, desc, inJson, outJson, fn)` also takes String schemas.

## Extensions

```java
public interface ServerExtension extends Extension<InteractionContext> {
    String extensionId(); // reverse-DNS, e.g. "com.example/audit"
    default ExtensionSettings serverSettings() { return ExtensionSettings.empty(); }
    default Set<String> methods() { return Set.of(); }
    default boolean requiresMetaEnvelope() { return true; }
    default void bootstrap(ExtensionContext context) {}
    default void onConnectionInit(InteractionContext context, ExtensionSettings clientSettings) {}
}
```

Register with `.withExtensions(myExtension)` (vararg — pass several in one call). `.extension(ext)` still works but is deprecated.

## Tests

- Unit: JUnit 6 + AssertJ, `@TempDir`, port `0` = random.
- E2E: `io.modelcontextprotocol.sdk:mcp-core:2.0.0-RC1` client.
- `mvn test` (unit+e2e) · `mvn verify` (+conformance) · `mvn spotless:apply`.

## Kotlin DSL

Kotlin DSL supports **suspend** tool/resource/prompt handlers, `buildServer { }`, and `TachyonServer { }`. Handler lambdas are `suspend` receivers: call suspending APIs directly, without `it`. Prompt lambdas expose `arguments`.

```kotlin
// buildServer {} → Kotlin TachyonServer without transport
// TachyonServer(port) {} → Kotlin TachyonServer with Netty transport

val server = TachyonServer(8080) {
    info {
        name = "demo-server"
        version = "1.0"
    }
    capabilities {
        tools { mode = Mode.ON; listChanged = true }
        resources { mode = Mode.ON; subscribe = true; listChanged = true }
    }
    tool(name = "ping", description = "Simple ping") { ToolResult.text("pong") }
    resource(name = "config", uri = "demo://config", description = "Server configuration") {
        TextResourceContents.of(uri, """{"mode":"production"}""", "application/json")
    }
    prompt(name = "greet", description = "Generates a greeting") {
        listOf(PromptMessage.user("Say hello, ${arguments ?: "friend"}"))
    }
}
```

`tool(inputSchema = ...)` accepts a `JsonSchema`, raw JSON `String`, or kotlinx `JsonObject`.

Register `ServerExtension`s with `extensions(...)` (vararg — pass one or several):

```kotlin
val server = TachyonServer(8080) {
    extensions(MyAuditExtension(), MyMetricsExtension())
}
```

(`TasksExtension` is the one built-in extension you never register by hand — `.tasks(connector)` in
`capabilities { }` registers it automatically.)

### Typed decode/result (Kotlin)

`request.arguments().decode<T>()` uses the configured serde (Jackson by default); symmetric `success(value)` returns a typed result:

```kotlin
@Serializable
data class GreetArgs(val name: String, val greeting: String = "Hello")

@Serializable
data class GreetReply(val message: String)

tool(name = "greet", description = "Typed greet", inputSchema = ..., outputSchema = ...) {
    val input = request.arguments().decode<GreetArgs>() // uses configured serde
    success(GreetReply("${input.greeting}, ${input.name}!"), "custom text")
}
```

- `request.arguments().decode<T>()` — decodes through the configured serde; select kotlinx explicitly to use custom `Json` config
- `scope.success(value)` — mirrors `decode`, defers serialization to the configured serializer
- `scope.success(value, text)` — structured + human-readable text fallback

Post-build registration — `TachyonServer` (Kotlin) mirrors every builder-time DSL function
(`tool`/`resource`/`resourceTemplate`/`prompt`/`promptCompletion`/`resourceCompletion`) as a
suspend `register*` method, callable before or after `.start()`, each with a flat-parameter
overload and a prebuilt-descriptor overload — the Kotlin equivalent of Java's
`server.tools().register(...)`/`.resources().register(...)`/`.prompts().register(...)`/
`.completions().registerForPrompt(...)`/`.registerForResource(...)`:

```kotlin
server.registerTool(
    ToolDescriptor.builder()
        .name("reverse-echo")
        .description("Echo reversed message")
        .inputSchema(schema)
        .build(),
) {
    ToolResult.text(request.arguments().stringValue("message").reversed())
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
    CompletionResult {
        values = listOf("plain", "concise", "pirate").filter { it.startsWith(argumentValue) }
    }
}

server.registerResourceCompletion("myapp://users/{userId}/profile") {
    CompletionResult { values = listOf("alice", "bob") }
}
```

⚠️ Handler scopes (`CompletionScope`, `ResourceScope`, …) and most builder receivers carry
`@TachyonDsl` — a `@DslMarker` — so inside a nested `TextResourceContents { }` you cannot reach the
outer scope's `ctx`/`request` implicitly; capture them in a local `val` first. The accessors you
actually need are re-exposed on the builder (`param`/`sequence`) or reachable because the builder is
unmarked (`CompletionResultBuilder`), so both of these compile as written:
`TextResourceContents { text = param("id") }` and
`CompletionResult { values = candidates.filter { it.startsWith(argumentValue) } }`.

Full: `resources/kotlin/PostBuildRegistrationExample.kt`

## Resource files

Load on demand (next to this skill):

- [resources/java/ServerBasic.java](resources/java/ServerBasic.java) — full server, all features
- [resources/java/ToolHandlerExample.java](resources/java/ToolHandlerExample.java) — descriptor/function registration and experimental `AbstractToolHandler`
- [resources/java/ResourceFnExample.java](resources/java/ResourceFnExample.java) — `ResourceDescriptor`, `ResourceTemplateEntry`, `ResourceFn`
- [resources/java/PromptFnExample.java](resources/java/PromptFnExample.java) — `PromptDescriptor`, `PromptArgument`, `PromptFn`
- [resources/java/ConfigReference.java](resources/java/ConfigReference.java) — `CapabilitiesConfig.Builder`, `NetworkConfig.Builder`, `SessionConfig.Builder`
- [resources/kotlin/ServerBasic.kt](resources/kotlin/ServerBasic.kt) — full server, all features (Kotlin DSL)
- [resources/kotlin/ToolHandlerExample.kt](resources/kotlin/ToolHandlerExample.kt) — suspend handler, `extends AbstractToolHandler` (`handle`/`handleAsync`), `registerTool`
- [resources/kotlin/ResourceFnExample.kt](resources/kotlin/ResourceFnExample.kt) — static resources, URI templates (Kotlin DSL)
- [resources/kotlin/PromptFnExample.kt](resources/kotlin/PromptFnExample.kt) — prompt descriptors and handlers (Kotlin DSL)
- [resources/kotlin/PostBuildRegistrationExample.kt](resources/kotlin/PostBuildRegistrationExample.kt) — `registerResource`/`registerResourceTemplate`/`registerPrompt`/`registerPromptCompletion`/`registerResourceCompletion` on an already-built `TachyonServer`
