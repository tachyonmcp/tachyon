---
title: Observability
tags: [concept, observability, otel]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/observability/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/ObservabilityConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/PayloadCapturePolicy.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/SubscriptionsListenHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/OutboundSseStream.java, integrations/tachyon-opentelemetry/]
updated: 2026-09-17
commit: e5c536ea
---

# 🔭 Observability

Verdict: passive listener lifecycle per operation. Zero listeners ⇒ `Observation.NONE` (no allocation, no capture). Listener gets `start(info) → ObservationScope` (context propagation across thread hops via `reattach`) and one terminal `complete(info, outcome)`.

## 🔁 Lifecycle

`Observation` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/observability/Observation.java`

| Call | Where | Meaning |
|---|---|---|
| `start(listeners, info)` | dispatcher entry | each listener `start`; faults logged, NOOP scope `Observation#start` |
| `closeStart()` | calling thread before hop to executor | close start scopes once `Observation#closeStart` |
| `reattach()` / `closeReattached` | executor thread around decode+kickoff, result mapping | re-open scopes (e.g. OTel context) `Observation#reattach` |
| `mark*` | handlers | override outcome: `TaskHandoff`, `SerializationFailed`, `PayloadFailure` `OperationOutcome` |
| `complete(default)` | once (CAS) | override wins `Observation#complete` |

Listener throwing on interrupted thread ⇒ rethrown, else warn `Observation#fault`.

`OperationInfo` (mutable): kind `REQUEST|NOTIFICATION|INITIALIZE`, method, requestId, sessionId, `traceparent` (from `_meta.traceparent`, always extracted `McpDispatcher#extractTraceParent`), protocolVersion, server address/port, request/response payload, `target` (tool/prompt name), exceptionCause, `establishmentNanos` (streaming ops only — see below) `OperationInfo`.

`OperationOutcome` sealed `OperationOutcome`: `Rejected(error?, httpStatus, wireCode)`, `Completed`, `PayloadFailure`, `SerializationFailed`, `HandlerFailed(error, wireCode, cause?)`, `Cancelled`, `TaskHandoff(taskId)`, `NotificationAccepted`, `NotificationIgnored`, `StreamFailed(causeType, cause?)` (a `subscriptions/listen` stream's genuine post-establishment transport failure — an ordinary disconnect reports `Cancelled` instead).

Transport-triggered subscription terminal continuations run through the server executor, with a virtual-thread fallback after executor rejection; Netty close callbacks remove the registry entry and schedule settlement of the pending result [SubscriptionsListenHandler#executeCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/SubscriptionsListenHandler.java). Graceful shutdown completes the pending result directly on the shutdown caller. The ack timestamp is volatile for independent shutdown completion [OperationInfo#establishmentNanos](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/observability/OperationInfo.java).

Trace context and server address/port enter through the builder; only getters remain on the built object [OperationInfo#build](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/observability/OperationInfo.java).

## 📦 Payload capture

`PayloadCapturePolicy(requestArgs, responseContent, rawMessage, exceptionDetail, maxBytes=4096)` `PayloadCapturePolicy`. Capture only when toggle on **and** observation active. `CapturedPayload.capture` truncates on UTF-8 boundary with `…(truncated)` `CapturedPayload#capture`. Tool args/result captured in `ToolsCallHandler#handleAsync`, `ToolsCallHandler#captureResponseContent`. Exception cause only via `DispatchContext.captureExceptionCause` gated by `exceptionDetail` `DispatchContext#captureExceptionCause`.

Stream failure classification retains the cause type; the throwable is retained only with `exceptionDetail` [McpDispatcher#handleHandlerError](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java). OTel exports an exception event only when that throwable is present [McpOpenTelemetryListener#recordOutcome](../../integrations/tachyon-opentelemetry/src/main/java/dev/tachyonmcp/opentelemetry/McpOpenTelemetryListener.java).

## 🐢 Slow requests

`observability { slowRequestLogging(); slowRequestThreshold(d) }` → `HandlerWatchdog` DEBUG log after threshold + WARN on slow POST response `McpDispatcher#invokeHandlerAsync`, `McpOperationHandler#completePostRequest`.

## 📈 OpenTelemetry bridge

`McpOpenTelemetryListener.create(openTelemetry)` `McpOpenTelemetryListener`: SERVER span named by method, histogram `mcp.server.operation.duration` (s), attributes `mcp.method.name`, `mcp.session.id`, `mcp.protocol.version`, `gen_ai.tool.name`, `gen_ai.prompt.name`, `gen_ai.operation.name`, optional `gen_ai.tool.call.arguments/result` `McpAttributes`; `error.type` from error kind / tool error / cause type. Span status is `ERROR` whenever `error.type` is set — no caller-fault-vs-server-fault carve-out, per the MCP semconv's unconditional rule. Follows OTel GenAI MCP semconv.

⚠️ `subscriptions/listen` completes at real stream end `SubscriptionsListenHandler#handleAsync`, so its **span** covers the whole stream lifetime — but the duration **metric** stays ack-only: the handler stamps `OperationInfo#establishmentNanos` from `OutboundSseStream#start`'s returned `CompletionStage`, once the ack write is actually flushed — not right after `start()` returns, which only schedules that write and can return before it lands (especially off the channel's event loop) `SubscriptionsListenHandler#handleAsync`. Both `McpOpenTelemetryListener` and `TachyonMetricsListener` use `establishmentNanos` (when present) instead of the completion timestamp when recording their histogram/timer. Same mechanism, same reason, in both listeners. Ack write fails → no `establishmentNanos` (metric falls back to completion time); handler settles `pending` itself without waiting on `onClose` — `ClosedChannelException` → `Cancelled`, else `StreamFailed` `SubscriptionsListenHandler#handleAsync`. A stream already closed when `start()` runs also fails `ClosedChannelException` → `Cancelled` `PostSseStream#doStart`.

⚠️ `ObservationListener` is `@InternalApi` + `@ExperimentalApi` yet meant for bridges — deliberate narrow exception, `tachyon-opentelemetry` compiles against it on purpose (`integrations/tachyon-opentelemetry/pom.xml`), stated in `docs/running/observability.md` → [[api-stability]].

Related: [[request-lifecycle]], [[integrations]].
