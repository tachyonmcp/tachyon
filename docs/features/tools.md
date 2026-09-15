---
title: "Tools"
weight: 10
sidebar_order: 10
toc: true
description: |-
  Declare MCP tools with annotations, bind typed arguments, return structured output, and use programmatic registration when needed.
---

Tools are the primary way clients invoke server-side logic. Tachyon validates inputs against JSON Schema 2020-12 and routes calls to your handler.

## Define a tool with annotations

Put `@McpTool` on a service method. Its parameters define the input schema, and a string return
value becomes text content. Register the service with the server:

```java
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.core.server.TachyonServer;

class GreetingService {
    @McpTool(description = "Say hello to someone")
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}

var server = TachyonServer.builder()
        .annotations(annotations -> annotations.register(new GreetingService()))
        .port(8080)
        .build();
```

The tool name defaults to `greet`; set `@McpTool(name = "hello")` to override it. Compile with
`-parameters` so `name` is available for argument binding. The [Quickstart](../quickstart.md)
includes Maven and Gradle setup. See the [annotation reference](../annotations.md) for shared rules.

### Bind typed arguments

Named parameters are required unless marked with JSpecify `@Nullable` or typed as `Optional`.
Tachyon injects `InteractionContext` wherever it appears in the signature; it is never a client
argument. Add this method to your service:

```java
@McpTool
public String welcome(String name, @org.jspecify.annotations.Nullable String title) {
    return "Hello, " + (title == null ? "" : title + " ") + name + "!";
}
```

A single record, POJO, or `Map` parameter receives the whole arguments object. Return a record or
POJO for structured output and a generated output schema:

```java
public record ForecastRequest(String city, int days) {}
public record Forecast(String city, int days, double highC) {}

@McpTool(description = "Multi-day forecast")
public Forecast forecast(ForecastRequest request) {
    return new Forecast(request.city(), request.days(), 24.0);
}
```

The input is `{"city":"Paris","days":3}`, with no `request` wrapper. Tachyon derives schemas from
the Java types and uses the configured payload codecs. The example returns fixed demonstration data;
replace its body with your forecast lookup.

### Return results

Annotated methods can return ordinary values or an explicit `ToolResult`:

| Return value | MCP result |
|---|---|
| String, number, boolean, enum | Text content |
| Record or POJO | Structured content and a JSON text fallback |
| Collection or array | JSON text content |
| `ContentBlock` | Content block |
| `void` or `null` | Empty content |
| `ToolResult` | Preserved, including errors and metadata |

Use `ToolResult` for error results, input requests, or explicit content. Its factories below work
from both annotated methods and programmatic handlers.

### Handle errors

Return `ToolResult.error(...)` for an expected failure that the caller can act on:

```java
import dev.tachyonmcp.api.server.features.tools.ToolResult;

@McpTool(description = "Say hello to someone")
public ToolResult greet(String name) {
    if (name.isBlank()) {
        return ToolResult.error("Provide a non-blank name.");
    }
    return ToolResult.text("Hello, " + name + "!");
}
```

Use this method in place of the earlier `greet` method. A service cannot declare duplicate tool
names. Error and input-validation behavior is described [below](#error-behavior).

## ToolResult factories

`ToolResult` is a sealed type — pick the right factory:

| Factory                                 | Use case                               |
|-----------------------------------------|----------------------------------------|
| `ToolResult.text(t)`                    | Plain text response                    |
| `ToolResult.error(msg)`                 | Error (`isError = true`)               |
| `ToolResult.content(blocks...)`         | Multiple content blocks                |
| `ToolResult.structured(payload)`        | POJO → `structuredContent`; serialized JSON auto-added as the text block |
| `ToolResult.structured(payload, text)`  | Structured + explicit human-readable text |
| `ToolResult.raw(json, text)`            | Pre-serialized JSON — bypasses the payload serde |
| `ToolResult.empty()`                    | No content                             |
| `ToolResult.task(snapshot)`             | Hand off to a long-running [task](tasks.md) |
| `ToolResult.inputRequired(reqs, state)` | Elicitation request                    |

Under MCP 2026-07-28, `structuredContent`/`outputSchema` may be any JSON value — object, array, or
scalar. Under 2025-11-25, `structuredContent` stays object-only on the wire: a non-object result
still validates against `outputSchema`, but is delivered as the serialized-JSON text block instead
of `structuredContent`. A structured value that fails its declared `outputSchema` is rejected as an
`isError: true` tool result on every protocol version.

See [Client interactions](client-interactions.md) for form elicitation, input-required results,
and the sampling compatibility boundary.

## Error behavior

`ToolResult.error(...)` returns a tool result with `isError: true`. A request that fails input-schema validation
instead receives a JSON-RPC error with code `-32602`, before the handler runs. Unexpected handler
exceptions produce `-32603` with the message `Tool handler failed`; an `IllegalArgumentException`
produces `-32602` with `Invalid params`. Internal exception messages are not returned to the client.

## Test the tool

Start with the [Quickstart curl call](../quickstart.md#3-test-with-curl). Change the `name` argument
and check that the greeting changes. For the annotated `ToolResult` handler, also try an empty string, a missing
`name`, and a number: these exercise the tool-error and input-validation paths.

For automated coverage, use [Testkit](../testkit.md) to start a server on port `0`, call it through
an MCP client, and assert the full result. Cover valid input and failures through the same transport
that your clients use.

## Programmatic registration

Use descriptors when you need explicit schemas or metadata beyond `@McpTool`'s name and description,
or register handlers dynamically through `server.tools()`.

### Define a tool

Add the `.withTools(...)` registration snippets to `TachyonServer.builder()`. For a complete project, start
with the [Quickstart](../quickstart.md).

```java
import dev.tachyonmcp.api.server.features.tools.ToolResult;

.withTools(tools -> tools.register(
        tool -> tool.name("hello").description("Say hello"),
        (ctx, request) -> ToolResult.text("Hello!")))
```

Need an input schema? Configure the descriptor with the builder overload. `.inputSchema(...)` /
`.outputSchema(...)` take a raw JSON `String` or a provider-neutral `JsonSchema`:

```java
.withTools(tools -> tools.register(
        b -> b.name("hello")
            .description("Say hello")
            .inputSchema("""
            {"type":"object","properties":{"name":{"type":"string"}}}
            """),
        (ctx, request) -> ToolResult.text(
            "Hello, " + request.arguments().stringOr("name", "world") + "!")))
```

### Read arguments

Handlers receive a `ToolRequest`. Use `request.arguments()` to read its input.

`Args` is the `JsonObject` view of the call arguments, so it carries the same typed accessors:

| Method                    | Returns   |
|---------------------------|-----------|
| `args.stringValue("key")` | `String`  |
| `args.intValue("key")`    | `int`     |
| `args.boolValue("key")`   | `boolean` |
| `args.doubleValue("key")` | `double`  |
| `args.has("key")`         | `boolean` |

`*Or(key, fallback)` and `*Opt(key)` variants avoid throwing on missing keys — `stringOpt`,
`boolOpt`, `intOpt`, `longOpt`, `doubleOpt`, `decimalOpt`, `objectOpt`, `arrayOpt`.

To take the whole argument object at once, use `args.decode(MyArgs.class)` — it runs through the
server's configured `PayloadDeserializer`. To reach the underlying provider value, use
`args.unwrap(JsonNode.class)`.


### Async tool

Blocking handlers run on a virtual thread, so most tools need no async plumbing. When you already
hold a `CompletionStage` (a non-blocking client, another async service), return it directly with
`registerAsync`. Async handlers stay async — they are not funneled through the blocking path.

```java
.withTools(tools -> tools.registerAsync(
        tool -> tool.name("get_weather_async"),
        (ctx, request) -> fetchWeather(request.arguments().stringValue("city"))
                .thenApply(w -> ToolResult.text(w.summary()))))
```

### Typed tool (experimental)

> [!NOTE]
> Typed registration is experimental. Its API may change between releases.

Instead of reading arguments key by key, register a tool against an input and an output type.
Tachyon decodes the call arguments into `I` with the configured `PayloadDeserializer` and wraps
your return value as `structuredContent`:

```java
record ForecastRequest(String city, int days) {}
record Forecast(String summary, double highC) {}

.withTools(tools -> tools.register(
        ForecastRequest.class,
        Forecast.class,
        tool -> tool.name("get_forecast").description("Multi-day forecast"),
        (ctx, input) -> new Forecast(lookup(input.city()), highFor(input.city(), input.days()))))
```

`registerAsync(Class, Class, ..., AsyncTypedToolFn)` is the `CompletionStage` twin.

Any schema the descriptor leaves unset is filled in from the matching type via
`JsonSchema.generate(Class)`, which resolves through the registered `JsonSchemaFactory` chain:

| Source | Provided by |
|---|---|
| Build-time schema resource from the kt-schema annotation processor | `tachyon-core` |
| Runtime reflection over the class, when installed | `tachyon-kotlin-kt-schema` |
| Java records, POJOs, and other supported Java types | `tachyon-core` fallback |

The built-in Java fallback handles ordinary Java types without an extra schema dependency.
Set `inputSchema`/`outputSchema` on the descriptor when you need explicit constraints.

## Add metadata

```java
return ToolResult.text("done").withMeta("taskId", "t-123");
```

Metadata appears in the `_meta` field of the response.

## Mirror an argument into an HTTP header

MCP 2026-07-28 (SEP-2243) lets a tool argument be mirrored into an `Mcp-Param-{Name}` request
header, so load balancers, WAFs and rate limiters can route on it without parsing the JSON body.
Use an explicit input schema in programmatic registration and annotate the property with
`x-mcp-header` (a JSON Schema keyword):

```json
{
  "type": "object",
  "properties": {
    "region": {"type": "string", "x-mcp-header": "Region"},
    "query": {"type": "string"}
  }
}
```

A conforming client then sends `Mcp-Param-Region: us-west1`, and the server rejects the call with
`400` / JSON-RPC `-32020` if that header is missing, malformed, or disagrees with the body.

The annotation is validated when the tool is registered — a violation throws
`IllegalArgumentException` rather than being ignored, because an annotation the server skips is a
header an intermediary trusts but nothing ever checks against the body:

| Rule | Detail |
|---|---|
| Non-empty HTTP token | RFC 9110 `1*tchar` — no spaces, colons, control characters, or non-ASCII |
| Unique, ignoring case | Two properties mirroring to one header make it ambiguous |
| Primitive types only | `string`, `integer`, `boolean`. `number` is excluded — its string form is not canonical |
| Top-level properties only | An annotation on a nested property, inside `items`, or behind a `$ref` is rejected rather than silently ignored |

Values must be ASCII; see [Configuration](../running/configuration.md) for the character rules and the
`=?base64?…?=` wrapper for anything else.

> [!CAUTION]
> Do not annotate secrets. Mirrored values are visible to every intermediary on the path, and Base64
> is an encoding, not encryption.

> [!NOTE] 
> Tachyon uses **Jackson 3** (`tools.jackson.*`), not Jackson 2. Import `tools.jackson.databind.JsonNode`, not `com.fasterxml.jackson.databind.JsonNode`.

## Kotlin DSL

```kotlin
tool(name = "reverse", description = "Reverse a string") {
    val msg = arguments.stringValue("message")
    text(msg.reversed())
}
```

### Typed decode/result

Enable the Kotlin serialization compiler plugin and add the
[kotlinx.serialization dependency](../kotlin/#kotlinxserialization-integration).
Configure `json` in the same server builder scope as `tool`:

```kotlin
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.kotlin.server.domain.decode
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlinx.serialization.Serializable

@Serializable
data class EchoArgs(val message: String)
@Serializable
data class EchoReply(val echo: String)

json { serde = KxSerializationSerde.Default }

tool(
    "echo",
    inputSchema = JsonSchema.parse(
        """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""",
    ),
    outputSchema = JsonSchema.parse(
        """{"type":"object","properties":{"echo":{"type":"string"}},"required":["echo"]}""",
    ),
) {
    val input = arguments.decode<EchoArgs>()
    success(EchoReply(input.message))
}
```

- `arguments.decode<T>()` — honors the configured serde (Jackson by default)
- `scope.success(value)` / `scope.success(value, text)` — symmetric typed result via configured serializer

`typedTool<In, Out>` derives both schemas from the types, so the literals above disappear
entirely. See [typed tools](/docs/kotlin/#typed-tools) and the
[Kotlin DSL](/docs/kotlin/) for the full Kotlin API.

## Executable examples

[DeclarativeFeaturesTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeFeaturesTest.java) exercises named and nullable arguments,
record input/output, generated schemas, and validation over HTTP.
[DeclarativeResultsTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeResultsTest.java) uses the annotated `greet` method above
and checks successful text, blank-name errors, and missing or mistyped arguments.
