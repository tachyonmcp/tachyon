---
title: "Observability"
weight: 65
sidebar_order: 65
toc: true
description: |-
  Trace and measure Tachyon servers with OpenTelemetry: set up the listener, read the resulting span and metric, then opt in to payload capture.
---

The [`tachyon-opentelemetry`](https://github.com/tachyonmcp/tachyon/tree/main/integrations/tachyon-opentelemetry)
module records a `SERVER` span and an `mcp.server.operation.duration` histogram for every inbound MCP
request and notification, following the
[MCP semantic conventions](https://github.com/open-telemetry/semantic-conventions-genai/tree/main/model/mcp).

This guide adds it to the [quickstart](../quickstart.md) greeting server and prints telemetry to the console.
Tool arguments and results stay out of telemetry until you [opt in](#capture-payloads).

## 1. Add dependencies

`tachyon-opentelemetry` depends on `opentelemetry-api` and `opentelemetry-semconv-incubating`. It does not provide an OpenTelemetry SDK or exporter, so you must supply both.
The logging exporter needs no collector, so use it to check your setup first.

<details open>
<summary>Maven</summary>

In the quickstart `pom.xml`, import the OpenTelemetry BOM next to `tachyon-bom`:

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-bom</artifactId>
    <version>1.65.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Then add these dependencies next to `tachyon-core`:

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-logging</artifactId>
</dependency>
```

</details>

<details>
<summary>Gradle</summary>

In the quickstart `build.gradle.kts`, extend `dependencies`:

```kotlin
dependencies {
    implementation(platform("dev.tachyonmcp:tachyon-bom:1.0.0-beta.30"))
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.65.0"))
    implementation("dev.tachyonmcp:tachyon-core")
    implementation("dev.tachyonmcp:tachyon-opentelemetry")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-logging")
}
```

</details>

## 2. Register the listener

Replace `src/main/java/MyMcpServer.java`:

```java
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.opentelemetry.McpOpenTelemetryListener;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;

public final class MyMcpServer {
    public static final class GreetingService {
        @McpTool(description = "Say hello to someone")
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    public static void main(String[] args) {
        final var openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                        .build())
                .setMeterProvider(SdkMeterProvider.builder()
                        .registerMetricReader(PeriodicMetricReader.builder(LoggingMetricExporter.create())
                                .setInterval(Duration.ofSeconds(10))
                                .build())
                        .build())
                .build();

        final var server = TachyonServer.builder()
                .name("my-server")
                .version("1.0")
                .annotations(annotations -> annotations.register(new GreetingService()))
                .observability(o -> o.listener(McpOpenTelemetryListener.create(openTelemetry)))
                .host("127.0.0.1")
                .port(8080)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            openTelemetry.close();
        }));
        server.start();
    }
}
```

The span processor exports each span as soon as it ends. The metric reader exports every 10 seconds;
its default interval is one minute. The shutdown hook closes the SDK after the server, so pending
telemetry is flushed.

With the [Kotlin DSL](../kotlin/), pass the same listener to `observability { }`:

```kotlin
TachyonServer(port = 8080) {
    observability {
        listener(McpOpenTelemetryListener.create(openTelemetry))
    }
}
```

## 3. Call a tool

Start the server as in the quickstart, for example with `mvn -q compile exec:exec`. In a second terminal,
call the greeting tool with the MCP Inspector CLI:

```bash
npx -y @modelcontextprotocol/inspector@2.7.0 --cli \
  --server-url http://127.0.0.1:8080/mcp \
  --transport http --protocol-era modern \
  --connect-timeout 10000 \
  --method tools/call --tool-name greet \
  --tool-args-json '{"name":"Ada"}' --format json
```

The [quickstart's curl request](../quickstart.md#3-call-the-greeting-tool) works too.

## 4. Find the span and metric

The server terminal prints one span per MCP request. The MCP Inspector CLI sends `server/discover` and `tools/list` before
the call, so look for the span named `tools/call greet` (IDs vary; line wrapped):

```text
INFO: 'tools/call greet' : d8d835147756c8ec2da8caeb97816c22 792d57177a7a7d01 SERVER
  [tracer: dev.tachyonmcp.opentelemetry:] AttributesMap{data={server.port=8080,
  network.protocol.name=http, jsonrpc.request.id=2, mcp.method.name=tools/call,
  mcp.protocol.version=2026-07-28, jsonrpc.protocol.version=2.0,
  gen_ai.operation.name=execute_tool, gen_ai.tool.name=greet, server.address=127.0.0.1}, ...}
```

Within 10 seconds, the metric exporter prints `name=mcp.server.operation.duration` data points
partitioned by low-cardinality attributes. Failed operations additionally include `error.type` and,
when applicable, `rpc.response.status_code`. Spans contain no tool arguments or result.

The OpenTelemetry SDK also reports its own `otel.sdk.*` metrics on every export. They are not
Tachyon telemetry; ignore them when checking this setup.

## 5. Send telemetry to a collector

Add `io.opentelemetry:opentelemetry-exporter-otlp` and register its exporters as additional span
processors and metric readers. OTLP sends data to a collector such as Jaeger, Grafana Tempo, or
Honeycomb. Keep the logging exporters while you check the collector connection, then remove them.

For local development, [otel-tui](https://github.com/ymtdzzz/otel-tui) is a terminal viewer for
traces, metrics, and logs. It receives OTLP on ports `4317` (gRPC) and `4318` (HTTP), the default
OTLP exporter endpoints, so no collector setup is needed. Install it with
`brew install ymtdzzz/tap/otel-tui` or `go install github.com/ymtdzzz/otel-tui@latest`, run
`otel-tui`, then call a tool to see its span.

The [weather examples](#examples) register both.

## Capture payloads

Content capture is **opt-in and off by default**. Every listener sees identity facts, such as method,
session ID, and protocol version. Request and response content and exception detail reach listeners
only when you enable them.

Enable capture deliberately. Tool arguments and results often carry credentials or personal data,
and they leave the process with your telemetry. Each toggle gates its own content: enabling
`exceptionDetail` does not enable `requestArgs`.

With `McpOpenTelemetryListener`, `requestArgs` and `responseContent` add tool arguments and results to
spans as `gen_ai.tool.call.arguments` and `gen_ai.tool.call.result`:

```java
.observability(o -> o
    .listener(McpOpenTelemetryListener.create(openTelemetry))
    .payloadCapture(p -> p.requestArgs(true).responseContent(true)))
```

```kotlin
observability {
    listener(McpOpenTelemetryListener.create(openTelemetry))
    payloadCapture {
        requestArgs = true
        responseContent = true
    }
}
```

Configure capture through `payloadCapture { }`: `PayloadCapturePolicy.Builder` in Java,
`PayloadCaptureScope` in Kotlin.

| Option | Default | Description |
|---|---|---|
| `requestArgs` | `false` | Capture request params/arguments |
| `responseContent` | `false` | Capture encoded response content |
| `exceptionDetail` | `false` | Capture exception message + stack trace |
| `rawMessage` | `false` | Reserved for future raw JSON-RPC envelope capture |
| `maxBytes` | `4096` | Truncation limit (UTF-8 bytes) per captured value |

## Trace context

Spans parent from `Context.current()` on the dispatch thread. The listener only attaches scope around synchronous dispatch work (decode, kicking off async handler, and — after reattach — completion callback), so handler-started spans join as children without blocking `CompletionStage`s.

Registering two listeners produces two spans per operation, the second nested under the first, and a handler-started span parents from the innermost one.

## Attributes

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

## Long-lived streams

`subscriptions/listen`'s span covers the whole SSE stream's lifetime: it only ends when the stream closes, whether that's an ordinary client disconnect, a genuine transport failure (`error.type` set to the failure's exception class, span status `ERROR`), or server shutdown. The `mcp.server.operation.duration` metric, however, still measures only the time until the subscription's acknowledgement is flushed — not the stream's full duration — so a long-lived subscription doesn't skew latency metrics or get lost in fixed histogram buckets sized for ordinary request/response latencies. If the acknowledgement never lands (the write fails or the client is already gone), there is no acknowledgement time, so the metric falls back to the elapsed time until the operation completed. The acknowledgement timestamp is published across threads, including shutdown completion. Transport-triggered subscription completion and its observation callbacks run on the server executor; if it rejects work during shutdown, a virtual thread completes the observation. Graceful shutdown completes subscriptions directly on the shutdown caller. Transport failure messages and stack traces are exported only with `exceptionDetail(true)`; `error.type` remains available when capture is disabled.

## Custom observation listeners

> **API status:** `ObservationListener` is `@InternalApi`. Use the supported
> `McpOpenTelemetryListener` implementation unless you accept source-breaking changes.

`McpOpenTelemetryListener` is one `ObservationListener`. A listener is a passive, read-only hook into
the dispatch lifecycle:

- `start(OperationInfo)` — fires once, before any handler runs
- `complete(OperationInfo, OperationOutcome)` — fires once, at the terminal boundary

`OperationInfo` exposes trace context and server address/port through getters. When constructing one programmatically, supply these values through `OperationInfo.builder(...)`.

Register a listener the same way as the OpenTelemetry one:

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

Listeners cannot short-circuit, reject, or substitute results. Tachyon catches an `Exception` thrown by a listener, logs it at `WARN` without payloads, and carries on, so the fault does not affect handler execution, responses, or other listeners. Two cases are not isolated: an `Error` propagates, and an exception raised while the calling thread is interrupted, for example during a shutdown that interrupts handlers, is rethrown as a `RuntimeException`.

Listeners nest in registration order: the scope a listener returns from `start` is opened inside the scope of every listener registered before it, so a later-registered listener's context is the active one while dispatch work runs. Scopes close innermost-first, which means each `close()` runs while its own context is current and restores whatever it displaced.

## Examples

- [`examples/weather-mcp`](https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp) (Java)
- [`examples/weather-mcp-kotlin`](https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp-kotlin) (Kotlin)

Both wire `tachyon-opentelemetry` with logging and OTLP exporters and verbose payload capture. Run either
and watch spans and metrics print to the console, or point the OTLP exporters at a collector.
