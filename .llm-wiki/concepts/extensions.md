---
title: Extensions
tags: [concept, extensions, spi]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/, tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/ExtensionNegotiator.java, tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/transport/ExtensionNegotiationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java]
updated: 2026-09-15
commit: 9eec1092
---

# 🧩 Extensions

Verdict: `ServerExtension` = bootstrap hook (register features + custom JSON-RPC methods through `ExtensionContext`) + negotiation hook. Methods an extension registers are **owned** by it. `negotiation()` policy: `REQUIRED` (default) ⇒ client must declare ext on current context, else -32021 (2026) / -32003 (2025); `OPTIONAL` ⇒ dispatched regardless, nothing synthesized.

## 🧱 Contract

| Member | Default | Proof |
|---|---|---|
| `extensionId()` | — | [Extension](../../tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java) |
| `advertiseMode()` | — (`ALWAYS` / `NEVER` / `NEGOTIATED`) | [AdvertiseMode](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/AdvertiseMode.java) |
| `serverSettings()` | empty | [ServerExtension#serverSettings](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `methods()` | empty set (pre-declared owned methods) | [ServerExtension#methods](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `negotiation()` | **`REQUIRED`** (`REQUIRED` / `OPTIONAL`) | [ServerExtension#negotiation](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java), `.../server/extensions/ExtensionNegotiation.java` |
| `requiresMetaEnvelope()` | **true** | [ServerExtension#requiresMetaEnvelope](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `bootstrap(ExtensionContext)` | no-op | [ServerExtension#bootstrap](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `onConnectionInit(ctx, clientSettings)` | no-op | [ServerExtension#onConnectionInit](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `onConnectionClose(ctx)`, `shutdown()` | no-op | [Extension#onConnectionClose](../../tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java), [Extension#shutdown](../../tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java) |

[ExtensionContext](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionContext.java) = `tools/resources/prompts/completions/tasks`, `executor`, `runtime`, `registerHandler(method, ExtensionMethodHandler)` [ExtensionContext](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionContext.java). [DefaultTachyonServer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java) implements it [DefaultTachyonServer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).

`ExtensionMethodHandler.handle(InteractionContext, JsonObject params)` → `Object` (null ⇒ protocol empty result) [ExtensionMethodHandler](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionMethodHandler.java), adapter [DefaultTachyonServer#getHandler](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).

## 🔁 Lifecycle

Spring extensions still bootstrap during server construction. Discovered annotated beans register later, after singleton initialization, through the existing registries (`ServerConfiguration#tachyonServer`, `TachyonBeanRegistrar#afterSingletonsInstantiated`); see [[spring-boot]].

1. `withExtensions(...)` — duplicate id ⇒ IAE `DefaultServerBuilder.java` `addExtension`.
2. Ctor `bootstrapExtensions`: record `methods()` owners, set `bootstrappingExtensionId`, call `bootstrap(this)`; any `registerHandler` during bootstrap also owned `DefaultTachyonServer#getHandler`, `DefaultTachyonServer#toJsonObject`.
3. Features registered with `descriptor.extensionId(...)` hidden from lists/calls unless enabled on ctx (`ToolMethodHandlers`, `PromptMethodHandlers`, `ResourceMethodHandlers` filters).
4. Negotiation → `ExtensionNegotiator.negotiate(extensions, ctx, declared)`: enable matching ids + `onConnectionInit` `ExtensionNegotiator#negotiate`.
   - 2025-11-25: once in `InitializeHandler` from `initialize` params, enabled ids stored on **session** (persisted) `InitializeHandler#handle`, `DefaultDispatchContext#enableExtension`.
   - 2026-07-28: every POST, `ExtensionNegotiationHandler` in pipeline, on fresh channel ctx `ExtensionNegotiationHandler#channelRead`. Non-object params ⇒ `RequestMappingException` swallowed, **no extension enabled**, request passes on so dispatcher answers `invalid_params` `RequestMappingException`.
5. Advertise in `initialize`/`server/discover`: `ALWAYS` always, `NEGOTIATED` if enabled, `NEVER` never `ExtensionNegotiator#registeredExtensions`.
6. `close()` → `shutdownExtensions(deadline)` each on own VT, joined with remaining grace; slow one logged + abandoned `DefaultTachyonServer#bootstrapExtensions`.

## 🚪 Routing gate

Order: resolve handler first (`server.getHandler`, none ⇒ `methodNotFound`), then `McpDispatcher.extensionNegotiationRejection` `McpDispatcher#dispatchTrackedRequestAsync`, `McpDispatcher#dispatchTrackedRequestAsync`, `McpDispatcher#extensionNegotiationRejection`:

| Owner | Policy | Declared on ctx | `_meta.<extId>` (if `requiresMetaEnvelope`) | Result |
|---|---|---|---|---|
| none | — | — | — | dispatch (core) |
| ext | `OPTIONAL` | any | skipped | dispatch |
| ext | `REQUIRED` | no | — | `ServerErrors.missingRequiredExtension(id)` ⇒ -32021 / HTTP 400 (2026), -32003 (2025), `data.requiredCapabilities.extensions.<id>:{}` |
| ext | `REQUIRED` | yes | missing | `invalidParams("Missing required client capability: <id>")` |
| ext | `REQUIRED` | yes | present / not required | dispatch |

Policy snapshot at bootstrap into `optionalNegotiationExtensionIds` `DefaultTachyonServer#optionalNegotiationExtensionIds`, `DefaultTachyonServer#bootstrapExtensions`, `DefaultTachyonServer#extensionNegotiationOptional`. "Declared on ctx" = session (2025) or per-request channel ctx (2026) — no leak across 2026 requests. `OPTIONAL` never enables the ext: handler sees `isExtensionEnabled=false`, no `onConnectionInit`. Fix `582f9c52`: Skills sets `requiresMetaEnvelope=false`.

Capability requirement inside a handler: throw `MissingRequiredClientCapabilityException(msg, requiredCaps)` ⇒ -32021 (2026) `MissingRequiredClientCapabilityException`. Tasks gate helper `TasksExtension.requireDeclared` (shares `ServerErrors.missingRequiredExtension` `ServerErrors#missingRequiredExtension`) returns error only for session-less protocols `TasksExtension#requireDeclared`.

## 📚 Known extensions

| Id | Impl | Mode | Page |
|---|---|---|---|
| `io.modelcontextprotocol/tasks` | `TasksExtension` (core) | ALWAYS | [[tasks]] |
| `io.modelcontextprotocol/skills` | `SkillsExtension` | ALWAYS, no meta, negotiation via builder (default **REQUIRED**, same as SPI default; `OPTIONAL` opt-in) | [[tachyon-extensions]] |
| `dev.tachyonmcp/kotlin-coroutines` | `CoroutineRuntime` (internal, lifecycle only) | NEVER | [[tachyon-kotlin]] |

Related: [[feature-registries]], [[protocol-versions]].
