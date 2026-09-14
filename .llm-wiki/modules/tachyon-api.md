---
title: tachyon-api
tags: [module, api]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/]
updated: 2026-09-14
commit: 70e205dc
---

# 📜 tachyon-api

Verdict: contract module. User-facing SAMs, descriptors, requests, results, domain content types, runtime context, JSON SPI, stability annotations. No Netty, no Jackson in public signatures.

## 🗂️ Packages (`tachyon-api/src/main/java/dev/tachyonmcp/api/`)

| Package | Key types | Page |
|---|---|---|
| `annotations` | `ExperimentalApi`, `InternalApi`, `LegacyApi`; declarative `McpTool`, `McpResource`, `McpPrompt` (RUNTIME, experimental, all attrs optional except resource `uri`; prompt `role` default `USER`) | [[api-stability]], [[integrations]] |
| `json`, `json.spi` | `JsonDocument`, `JsonObject`, `JsonArray`, `JsonSchema`, `JsonSchemaValidator`, `PayloadSerde`, `JsonDocumentFactory`, `JsonSchemaFactory` | [[json-layer]] |
| `runtime` | `InteractionContext`, `ContextNotifications`, `Notifications`, `ClientContext` (elicitation, deprecated sampling), `AttributeKey`, `Extension` | below |
| `server` | `ServerFeature<D>` (`descriptor()`), `ServerFeature.Descriptor` (`name()`), `.Request extends HasMeta` | |
| `server.config` | `ServerIdentity`, `RuntimeConfig`, `JsonConfig`, `MonitoringConfig`, `Mode` | [[configuration]] |
| `server.domain` | `ContentBlock` sealed (`TextContent`, `ImageContent`, `AudioContent`, `ResourceLink`, `EmbeddedResource`), `ResourceContents` (text/blob), `Args`, `RequestId`, `ProgressToken`, `LoggingLevel`, `ServerError`, `ServerCapabilities`, `UriTemplate`, `InputRequest` (`FormInputRequest`, `UrlInputRequest`), `InvalidArgumentException`, `Icon`, `Annotations`, `ToolAnnotations` | |
| `server.extensions` | `ServerExtension`, `ExtensionContext`, `ExtensionMethodHandler`, `ExtensionSettings`, `AdvertiseMode` | [[extensions]] |
| `server.features` | `HandlerFutures` (`@InternalApi`), `PaginatedResult` | [[feature-registries]] |
| `server.features.tools` | `Tools`, `ToolFn`, `AsyncToolFn`, `TypedToolFn`, `AsyncTypedToolFn`, `ToolHandler`, `AbstractToolHandler`, `ToolDescriptor`, `ToolRequest`, `ToolResult` | [[feature-registries]] |
| `server.features.resources` | `Resources`, `ResourceFn`, `AsyncResourceFn`, `ResourceDescriptor`, `ResourceTemplateDescriptor`, `ResourceRequest` | |
| `server.features.prompts` | `Prompts`, `PromptFn`, `AsyncPromptFn`, `PromptDescriptor`, `PromptRequest`, `PromptResult` | |
| `server.features.completions` | `Completions`, `CompletionFn`, `AsyncCompletionFn`, `CompletionRequest`, `CompletionResult` | |
| `server.features.tasks` | `Tasks`, `TaskConnector`, `TaskSnapshot`, `TaskState`, `TaskSupport`, `Task*Fn`, `Task*Request`, `TaskNotFoundException` | [[tasks]] |
| `server.features.annotations` | `AnnotationProvider`, `AnnotationRegistrationContext`, `AnnotationInvocationSupport` | [[integrations]] |
| `server.session` | `SessionIdGenerator<T>` | [[sessions]] |

## 🎭 InteractionContext (what handlers get)

`tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/InteractionContext.java:22-117`: `protocolVersion()`, `lifecycle()` (`INITIALIZATION|OPERATION|SHUTDOWN`), `sessionId()` (null stateless), `isExtensionEnabled(id)`, `notifications()` (`log`, `progress(token,…)`, `comment`), `client()` (`elicitation().create(...)`), `sendRequest(method, params)` (`@ExperimentalApi`, raw JSON string future), typed attributes `get/set(AttributeKey)`.

Runtime impl in core: `DefaultDispatchContext` (per request, wraps channel ctx) → [[tachyon-core]].

## 🎁 Results

- `ToolResult` sealed `Success | Error | InputRequired | Task` + statics `text`, `content`, `structured(payload[, text])`, `error`, `empty`, `task`, `raw(json,text)`, `inputRequired(reqs, state)` `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tools/ToolResult.java:19-458`.
- `PromptResult` sealed `Messages | InputRequired` `.../prompts/PromptResult.java:18-220`.
- `InputRequired` (MRTR: `inputRequests` map + opaque `requestState`); follow-up request carries `inputResponses` + `requestState` (`ToolRequest.java:83-92`).

## 🧪 Tests

`tachyon-api/src/test/java/dev/tachyonmcp/api/` — `JsonObjectTest`, `JsonSchemaTest`, `ArgsTest`, `UriTemplateTest`, `ToolResultTest`, `HandlerFnContractTest`, `HandlerFuturesTest`, descriptor tests.
