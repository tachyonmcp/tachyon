---
title: "Observability"
weight: 65
sidebar_order: 65
toc: true
description: |-
  Observe Tachyon servers with OpenTelemetry: observation listeners, opt-in payload capture policy, trace context, and span/metric attributes.
---

Tachyon separates **observation** (spans, metrics, logs) from **payload capture** (what observers may see). Both live under `observability { }` / `ObservabilityConfig.Builder`.

The [`tachyon-opentelemetry`](https://github.com/tachyonmcp/tachyon/tree/main/integrations/tachyon-opentelemetry) module connects Tachyon to
OpenTelemetry using the [MCP semantic conventions](https://github.com/open-telemetry/semantic-conventions-genai/tree/main/model/mcp).

## Observation Listeners

`ObservationListener` is a passive, read-only hook into the dispatch lifecycle:

- `start(OperationInfo)` — fires once, before any handler runs
- `complete(OperationInfo, OperationOutcome)` — fires once, at the terminal boundary

`OperationInfo` exposes trace context and server address/port through getters. When constructing one programmatically, supply these values through `OperationInfo.builder(...)`.

Listeners cannot short-circuit, reject, or substitute results. Exceptions in listeners are fault-isolated and never affect handler execution, responses, or other listeners.

Listeners nest in registration order: the scope a listener returns from `start` is opened inside the scope of every listener registered before it, so a later-registered listener's context is the active one while dispatch work runs. Scopes close innermost-first, which means each `close()` runs while its own context is current and restores whatever it displaced.

```java
var server = TachyonServer.builder()
    .observability(o -> o.listener(myListener))
    .port(8080)
    .build();
```

```kotlin
TachyonServer(port = 8080) {
    observability {
        listener(myListener)
    }
}
```

> **API status:** `ObservationListener` is `@InternalApi`. Use the supported
> `McpOpenTelemetryListener` implementation unless you accept source-breaking changes.

## Payload Capture Policy

Content capture is **opt-in and off by default**. Every listener sees identity facts (method, session id, protocol version) regardless of policy. Request/response content and exception detail only reach listeners when explicitly enabled.

Configured via `payloadCapture { }` -- `PayloadCapturePolicy.Builder` in Java, `PayloadCaptureScope` in Kotlin:

| Option | Default | Description |
|---|---|---|
| `requestArgs` | `false` | Capture request params/arguments |
| `responseContent` | `false` | Capture encoded response content |
| `exceptionDetail` | `false` | Capture exception message + stack trace |
| `rawMessage` | `false` | Reserved for future raw JSON-RPC envelope capture |
| `maxBytes` | `4096` | Truncation limit (UTF-8 bytes) per captured value |

```java
var server = TachyonServer.builder()
    .observability(o -> o.payloadCapture(p -> p.requestArgs(true).responseContent(true)))
    .port(8080)
    .build();
```

```kotlin
TachyonServer(port = 8080) {
    observability {
        payloadCapture {
            requestArgs = true
            responseContent = true
        }
    }
}
```

Enable deliberately: tool arguments and results routinely carry credentials and PII. Each toggle gates its own content independently — enabling `exceptionDetail` does not enable `requestArgs`.

## OpenTelemetry Integration

`McpOpenTelemetryListener` (module `dev.tachyonmcp:tachyon-opentelemetry`) records a `SERVER` span and `mcp.server.operation.duration` histogram for every inbound MCP request/notification. It's a passive `ObservationListener` — it only reads what core captured; it never captures payloads on its own.

Depends on `opentelemetry-api` only — you supply the SDK (tracer/meter provider) and exporter:

```java
var openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
        .build())
    .setMeterProvider(SdkMeterProvider.builder()
        .registerMetricReader(PeriodicMetricReader.create(LoggingMetricExporter.create()))
        .build())
    .build();

var server = TachyonServer.builder()
    .observability(o -> o
        .listener(McpOpenTelemetryListener.create(openTelemetry))
        .payloadCapture(p -> p.requestArgs(true).responseContent(true)))
    .port(8080)
    .build();
```

```kotlin
val openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
            .build()
    )
    .setMeterProvider(
        SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.create(LoggingMetricExporter.create()))
            .build()
    )
    .build()

TachyonServer(port = 8080) {
    observability {
        listener(McpOpenTelemetryListener.create(openTelemetry))
        payloadCapture {
            requestArgs = true
            responseContent = true
        }
    }
}
```

`LoggingSpanExporter` and `LoggingMetricExporter` (`io.opentelemetry:opentelemetry-exporter-logging`) print telemetry to application logs. Use them to verify local configuration. Add OTLP exporters (`io.opentelemetry:opentelemetry-exporter-otlp`) as span processors or metric readers to send data to a collector such as Jaeger, Grafana Tempo, or Honeycomb.

### Trace Context

Spans parent from `Context.current()` on the dispatch thread. The listener only attaches scope around synchronous dispatch work (decode, kicking off async handler, and — after reattach — completion callback), so handler-started spans join as children without blocking `CompletionStage`s.

Registering two listeners produces two spans per operation, the second nested under the first, and a handler-started span parents from the innermost one.

### Attributes

**Always present:**

| Attribute | Example |
|---|---|
| `mcp.method.name` | `tools/call` |
| `jsonrpc.protocol.version` | `2.0` |
| `network.protocol.name` | `http` |
| `mcp.protocol.version` | `2025-11-25` |
| `server.address` / `server.port` | `127.0.0.1` / `8080` |

**When applicable (span + metric):**

| Attribute | When |
|---|---|
| `gen_ai.tool.name`, `gen_ai.operation.name` | `tools/call` |
| `gen_ai.prompt.name` | `prompts/get` |
| `error.type` | Rejection with an error classification, tool error, handler/serialization failure, `subscriptions/listen` stream transport failure |
| `rpc.response.status_code` | Any JSON-RPC error response |

**Span-only (high cardinality):**

| Attribute | Notes |
|---|---|
| `jsonrpc.request.id` | Absent for notifications |
| `mcp.session.id` | Absent until session exists |

**Opt-in (gated by payload capture):**

| Attribute | Toggle |
|---|---|
| `gen_ai.tool.call.arguments` | `requestArgs` |
| `gen_ai.tool.call.result` | `responseContent` |
| Exception span event (message + stack) | `exceptionDetail` |

`error.type` is set for any rejection that carries an error classification, tool error, handler/serialization failure, or stream transport failure, whether it's the caller's fault (unknown method, bad params, malformed session) or the server's. Per the MCP semantic conventions, span status is `StatusCode.ERROR` whenever `error.type` is present — there's no separate "caller fault" status.

### Long-lived streams

`subscriptions/listen`'s span covers the whole SSE stream's lifetime: it only ends when the stream closes, whether that's an ordinary client disconnect, a genuine transport failure (`error.type` set to the failure's exception class, span status `ERROR`), or server shutdown. The `mcp.server.operation.duration` metric, however, still measures only the time until the subscription's acknowledgement is flushed — not the stream's full duration — so a long-lived subscription doesn't skew latency metrics or get lost in fixed histogram buckets sized for ordinary request/response latencies. If the acknowledgement never lands (the write fails or the client is already gone), there is no acknowledgement time, so the metric falls back to the elapsed time until the operation completed. The acknowledgement timestamp is published across threads, including shutdown completion. Transport-triggered subscription completion and its observation callbacks run on the server executor; if it rejects work during shutdown, a virtual thread completes the observation. Graceful shutdown completes subscriptions directly on the shutdown caller. Transport failure messages and stack traces are exported only with `exceptionDetail(true)`; `error.type` remains available when capture is disabled.

## Examples

- [`examples/weather-mcp`](https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp) (Java)
- [`examples/weather-mcp-kotlin`](https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp-kotlin) (Kotlin)

Both wire `tachyon-opentelemetry` with logging + OTLP exporters and verbose payload capture. Run either and watch spans/metrics print to console, or point remote collector.
