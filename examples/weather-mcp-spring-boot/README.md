# Weather MCP — Spring Boot

Java 21+, Spring Boot 4.1.1, and **Tachyon only**.
Uses native Tachyon annotations, not Spring AI annotations. Boot discovers the
feature bean and owns the embedded Netty MCP server lifecycle; a separate WebFlux server serves Actuator only.

## Run

Install the snapshot dependencies from the repository root:

```bash
mvn -q -f examples/weather-mcp-spring-boot/pom.xml verify && \
java -jar examples/weather-mcp-spring-boot/target/weather-mcp-spring-boot.jar
```

Connect to `http://127.0.0.1:8080/mcp`. Override with `--tachyon.port=9090`
or `TACHYON_PORT=9090`. Bind address defaults to loopback; this example has no
authentication. Keep it local unless a trusted gateway supplies access control.
Actuator runs on `http://127.0.0.1:8081/actuator` (`--server.port=...`).

The example is included in `make examples-snapshot`, not the published-artifact
`make examples` build. Its BOM import is pinned to the snapshot even when built
separately.

## Features

| Annotation | Feature |
|---|---|
| `@McpTool` | `get-weather(city, units?)`; structured output, `Unit` enum: CELSIUS default or FAHRENHEIT |
| `@McpResource` | Prediction article, featured Tallinn weather, `weather://current/{city}` |
| `@McpPrompt` | `rewrite-forecast(forecast, style)`; `NarrationStyle` enum: PLAIN, CONCISE, PIRATE |
| `@McpCompletion` | City suggestions, selected by reflected first parameter name; `NarrationStyle` completes from enum constants automatically |

Compiler `-parameters` is enabled. Optional tool input uses JSpecify `@Nullable`.
Annotations derive schemas and map return values; no manual registry setup.

Weather and city lookup use [Open-Meteo](https://open-meteo.com/), with a five-second
connect timeout and ten-second timeout per request. Two requests are needed for
weather (geocoding, then current conditions). No API key is needed for eligible
non-commercial use; review Open-Meteo's terms before deploying.

This is a focused counterpart to [weather-mcp](../weather-mcp): the same core
weather operations with Boot-managed observability, without icons, progress-token reporting,
or city elicitation. Unknown cities and invalid units are rejected.

## Observability

- **Actuator** (`spring-boot-starter-actuator`): the starter contributes the `tachyon` health
  indicator (`UP` + host/port while the transport runs) and Micrometer meters
  `mcp.server.operation.duration` (timer, tags `mcp.method.name`, `outcome`), `mcp.server.tools`,
  `mcp.server.prompts`, `mcp.server.resources`. Exposed over HTTP by WebFlux on
  `http://127.0.0.1:8081/actuator/health` and `/actuator/metrics` (`server.port`; MCP stays on
  `tachyon.port`).
- **OpenTelemetry** (`spring-boot-starter-opentelemetry` + `tachyon-opentelemetry`): a
  `TachyonServerCustomizer` in `WeatherApplication` adds `McpOpenTelemetryListener` on Boot's
  `OpenTelemetry` bean, producing MCP semantic-convention spans. Sampling is 100%; traces, logs, and
  OTLP metrics export every 5 seconds to OTLP/HTTP `localhost:4318` by default. Without a collector, export failures are
  logged; disable with `management.tracing.export.otlp.enabled=false` and
  `management.otlp.metrics.export.enabled=false`.

## Tests

`mvn -q -f examples/weather-mcp-spring-boot/pom.xml verify` starts the actual
Spring-managed Tachyon transport on port 0 and exercises MCP requests with a fake
weather provider, and checks health, Micrometer meters, and exported MCP spans (captured in memory,
OTLP export disabled). `OpenMeteoProviderTest` serves canned Open-Meteo payloads from a loopback
HTTP server and rejects partial or invalid numeric fields. No external weather requests occur in these tests.
