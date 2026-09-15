---
title: Observability
tags: [concept, observability, otel]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/observability/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/ObservabilityConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/PayloadCapturePolicy.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, integrations/tachyon-opentelemetry/]
updated: 2026-09-14
commit: 582f9c52
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

`OperationInfo` (mutable): kind `REQUEST|NOTIFICATION|INITIALIZE`, method, requestId, sessionId, `traceparent` (from `_meta.traceparent`, always extracted `McpDispatcher#extractTraceParent`), protocolVersion, server address/port, request/response payload, `target` (tool/prompt name), exceptionCause `OperationInfo`.

`OperationOutcome` sealed `OperationOutcome`: `Rejected(error?, httpStatus, wireCode)`, `Completed`, `PayloadFailure`, `SerializationFailed`, `HandlerFailed(error, wireCode, cause?)`, `Cancelled`, `TaskHandoff(taskId)`, `NotificationAccepted`, `NotificationIgnored`, `StreamEstablished`.

## 📦 Payload capture

`PayloadCapturePolicy(requestArgs, responseContent, rawMessage, exceptionDetail, maxBytes=4096)` `PayloadCapturePolicy`. Capture only when toggle on **and** observation active. `CapturedPayload.capture` truncates on UTF-8 boundary with `…(truncated)` `CapturedPayload#capture`. Tool args/result captured in `ToolsCallHandler#handleAsync`, `ToolsCallHandler#captureResponseContent`. Exception cause only via `DispatchContext.captureExceptionCause` gated by `exceptionDetail` `DispatchContext#captureExceptionCause`.

## 🐢 Slow requests

`observability { slowRequestLogging(); slowRequestThreshold(d) }` → `HandlerWatchdog` DEBUG log after threshold + WARN on slow POST response `McpDispatcher#invokeHandlerAsync`, `McpOperationHandler#completePostRequest`.

## 📈 OpenTelemetry bridge

`McpOpenTelemetryListener.create(openTelemetry)` `McpOpenTelemetryListener`: SERVER span named by method, histogram `mcp.server.operation.duration` (s), attributes `mcp.method.name`, `mcp.session.id`, `mcp.protocol.version`, `gen_ai.tool.name`, `gen_ai.prompt.name`, `gen_ai.operation.name`, optional `gen_ai.tool.call.arguments/result` `McpAttributes`; `error.type` from error kind / tool error / cause type. Follows OTel GenAI MCP semconv.

⚠️ `ObservationListener` is `@InternalApi` + `@Experimental` yet meant for bridges → [[api-stability]].

Related: [[request-lifecycle]], [[integrations]].
