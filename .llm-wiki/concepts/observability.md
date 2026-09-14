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
| `start(listeners, info)` | dispatcher entry | each listener `start`; faults logged, NOOP scope `:49-64` |
| `closeStart()` | calling thread before hop to executor | close start scopes once `:105-109` |
| `reattach()` / `closeReattached` | executor thread around decode+kickoff, result mapping | re-open scopes (e.g. OTel context) `:119-139` |
| `mark*` | handlers | override outcome: `TaskHandoff`, `SerializationFailed`, `PayloadFailure` `:76-96` |
| `complete(default)` | once (CAS) | override wins `:146-161` |

Listener throwing on interrupted thread ⇒ rethrown, else warn `:177-182`.

`OperationInfo` (mutable): kind `REQUEST|NOTIFICATION|INITIALIZE`, method, requestId, sessionId, `traceparent` (from `_meta.traceparent`, always extracted `McpDispatcher.java:147-158`), protocolVersion, server address/port, request/response payload, `target` (tool/prompt name), exceptionCause `OperationInfo.java:19-205`.

`OperationOutcome` sealed `OperationOutcome.java:14-71`: `Rejected(error?, httpStatus, wireCode)`, `Completed`, `PayloadFailure`, `SerializationFailed`, `HandlerFailed(error, wireCode, cause?)`, `Cancelled`, `TaskHandoff(taskId)`, `NotificationAccepted`, `NotificationIgnored`, `StreamEstablished`.

## 📦 Payload capture

`PayloadCapturePolicy(requestArgs, responseContent, rawMessage, exceptionDetail, maxBytes=4096)` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/PayloadCapturePolicy.java:19-83`. Capture only when toggle on **and** observation active. `CapturedPayload.capture` truncates on UTF-8 boundary with `…(truncated)` `CapturedPayload.java:30-48`. Tool args/result captured in `ToolMethodHandlers.java:119-134`, `:218-225`. Exception cause only via `DispatchContext.captureExceptionCause` gated by `exceptionDetail` `DispatchContext.java:67-73`.

## 🐢 Slow requests

`observability { slowRequestLogging(); slowRequestThreshold(d) }` → `HandlerWatchdog` DEBUG log after threshold + WARN on slow POST response `McpDispatcher.java:393-398`, `McpOperationHandler.java:359-388`.

## 📈 OpenTelemetry bridge

`McpOpenTelemetryListener.create(openTelemetry)` `integrations/tachyon-opentelemetry/src/main/java/dev/tachyonmcp/opentelemetry/McpOpenTelemetryListener.java:79-119`: SERVER span named by method, histogram `mcp.server.operation.duration` (s), attributes `mcp.method.name`, `mcp.session.id`, `mcp.protocol.version`, `gen_ai.tool.name`, `gen_ai.prompt.name`, `gen_ai.operation.name`, optional `gen_ai.tool.call.arguments/result` `McpAttributes.java:28-45`; `error.type` from error kind / tool error / cause type. Follows OTel GenAI MCP semconv.

⚠️ `ObservationListener` is `@InternalApi` + `@Experimental` yet meant for bridges → [[api-stability]].

Related: [[request-lifecycle]], [[integrations]].
