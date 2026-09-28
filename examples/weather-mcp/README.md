# Weather MCP Example

Demonstrates the Tachyon MCP Server with MCP Java SDK 2.0 client.

## Features

- **Tool**: `get-weather` — current weather for a city, with progress notifications and
  elicitation fallback when the city is not found
- **Resource**: `weather://prediction/article` — Markdown article about weather prediction
- **Resource**: `weather://featured/current` — JSON weather snapshot for Tallinn
- **Resource Template**: `weather://current/{city}` — JSON forecast for any city
- **Prompt**: `rewrite-forecast` — rewrites a forecast in a chosen style, with argument
  auto-completion
- **Completions**: city name completion for the resource template, style completion for the
  prompt

## Quickstart

```shell
./mvnw package && \
java -jar target/weather-example.jar
```

The server listens on `http://localhost:8080/mcp` by default. Set `HOST` and `PORT` to change it.

## Binding and access from Docker

`HOST` (default `localhost`), `PORT` (default `8080`), and `ALLOWED_HOST` (unset) control the bind
address and which extra `Host` authority the DNS-rebinding guard accepts. To reach the server from
a Docker container:

```shell
export HOST=0.0.0.0 && \
export ALLOWED_HOST=host.docker.internal:8080 && \
java -jar target/weather-example.jar
```

⚠️ `HOST=0.0.0.0` publishes the port on every interface, not just loopback — anything that can
reach your machine can reach the server. Use a specific reachable address instead of `0.0.0.0`
when you can, and keep `ALLOWED_HOST` set so the `Host` check still filters requests.

See [../README.md](../README.md#binding-and-access-from-docker) for the full table.

## Observability

This server wires [`tachyon-opentelemetry`](../../integrations/tachyon-opentelemetry) into a
minimal `OpenTelemetrySdk` (`WeatherServer.OTEL`) with two exporters and a fully verbose payload
capture policy (`requestArgs`, `responseContent`, `rawMessage`, `exceptionDetail` all on):

- A **logging exporter** — no collector to run, spans and metrics just print to the console.
- The standard **OTLP/HTTP exporter**. Run a local collector (Jaeger, Grafana Tempo, Honeycomb, ...) to see traces land there too. With no collector running, it logs periodic export failures — expected and harmless; only the logging
  exporter's output matters for this demo.

Call a tool and watch the log for a `tools/call get-weather` span and its
`mcp.server.operation.duration` metric.

See [observability documentation](../../docs/running/observability.md) for the full attribute reference.
