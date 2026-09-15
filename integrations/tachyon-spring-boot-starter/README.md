# Tachyon MCP Spring Boot Starter

Runs a Tachyon MCP server inside a Spring Boot 4 application. Beans with `@McpTool`,
`@McpResource`, `@McpPrompt`, or `@McpCompletion` methods are exposed automatically.

## Install

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-spring-boot-starter</artifactId>
</dependency>
```

Version comes from `tachyon-bom`. Compile with `-parameters` (Spring Boot's parent POM already
does) so named arguments keep their names.

## Use

```java
@Component
class WeatherService {
    record Forecast(String city, double celsius) {}

    @McpTool(description = "Forecast for a city")
    Forecast forecast(String city) {
        return new Forecast(city, 22.5);
    }
}
```

That's it. See [docs/annotations.md](../../docs/annotations.md) for binding and result rules.

## Configure

```yaml
tachyon:
  enabled: true   # default
  port: 8080      # default; 0 = ephemeral
  name: weather   # optional, reported to clients
  version: 1.0.0  # optional
  host: 0.0.0.0   # optional
```

| Bean | Effect |
|---|---|
| any singleton bean with `@McpTool`/`@McpResource`/`@McpPrompt`/`@McpCompletion` methods | registered after singleton initialization; class and JDK proxies preserve advice |
| `ServerExtension` | passed to `withExtensions(...)` |
| `TachyonServerCustomizer` | last word on the `ServerBuilder` (sessions, JSON, other annotation providers, …) |
| `TachyonServer` | built by the starter; inject it for `notifications()` or runtime registration |

`TachyonServerLifecycle` binds the transport when the context refreshes and closes it on shutdown.

Annotated beans may constructor-inject `TachyonServer`. The starter builds the server first,
then registers initialized beans using its configured payload codecs, before binding the transport.
JDK proxies use annotations and parameter names from the target class; annotated methods must be
exposed on the proxy's interfaces. Calls always pass through the proxy, including security and
transaction advice. Use class proxies for annotated methods outside those interfaces.

Unrelated lazy beans are not initialized for scanning. A lazy annotated bean must expose its
annotations on the type Spring can determine without creating it.

A user-provided `TachyonServer` disables automatic construction and bean registration; its lifecycle
is still managed unless a `TachyonServerLifecycle` bean is also provided.
