# Weather MCP — Spring Boot

Java 21+, Spring Boot 4.1.1, and **Tachyon 1.0.0-SNAPSHOT only**.
Uses native Tachyon annotations, not Spring AI annotations. Boot discovers the
feature bean and owns the embedded Netty server lifecycle; no MVC/WebFlux server needed.

## Run

Install the snapshot dependencies from the repository root:

```bash
mvn -q -pl tachyon-bom,integrations/tachyon-spring-boot-starter,tachyon-testkit -am install -DskipTests && \
mvn -q -f examples/weather-mcp-spring-boot/pom.xml verify && \
java -jar examples/weather-mcp-spring-boot/target/weather-mcp-spring-boot.jar
```

Connect to `http://127.0.0.1:8080/mcp`. Override with `--tachyon.port=8081`
or `TACHYON_PORT=8081`. Bind address defaults to loopback; this example has no
authentication. Keep it local unless a trusted gateway supplies access control.
Spring's keep-alive setting keeps this non-web application running.

The example is included in `make examples-snapshot`, not the published-artifact
`make examples` build. Its BOM import is pinned to the snapshot even when built
separately.

## Features

| Annotation | Feature |
|---|---|
| `@McpTool` | `get-weather(city, units?)`; structured output, `Unit` enum: CELSIUS default or FAHRENHEIT |
| `@McpResource` | Prediction article, featured Tallinn weather, `weather://current/{city}` |
| `@McpPrompt` | `rewrite-forecast(forecast, style)`; `NarrationStyle` enum: PLAIN, CONCISE, PIRATE |
| `@McpCompletion` | Style and city suggestions, selected by reflected first parameter name |

Compiler `-parameters` is enabled. Optional tool input uses JSpecify `@Nullable`.
Annotations derive schemas and map return values; no manual registry setup.

Weather and city lookup use [Open-Meteo](https://open-meteo.com/), with a five-second
connect timeout and ten-second timeout per request. Two requests are needed for
weather (geocoding, then current conditions). No API key is needed for eligible
non-commercial use; review Open-Meteo's terms before deploying.

This is a focused counterpart to [weather-mcp](../weather-mcp): the same core
weather operations, without telemetry exporters, icons, progress-token reporting,
or city elicitation. Unknown cities and invalid units are rejected.

## Tests

`mvn -q -f examples/weather-mcp-spring-boot/pom.xml verify` starts the actual
Spring-managed Tachyon transport on port 0 and exercises MCP requests with a fake
weather provider. No external weather requests occur in these tests.
