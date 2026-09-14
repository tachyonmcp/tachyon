---
title: Extensions
tags: [concept, extensions, spi]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/, tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/ExtensionNegotiator.java, tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/transport/ExtensionNegotiationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java]
updated: 2026-09-14
commit: 8c7738c0
---

# 🧩 Extensions

Verdict: `ServerExtension` = bootstrap hook (register features + custom JSON-RPC methods through `ExtensionContext`) + negotiation hook. Methods an extension registers are **owned** by it. `negotiation()` policy: `REQUIRED` (default) ⇒ client must declare ext on current context, else -32021 (2026) / -32003 (2025); `OPTIONAL` ⇒ dispatched regardless, nothing synthesized.

## 🧱 Contract

| Member | Default | Proof |
|---|---|---|
| `extensionId()` | — | `tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java:12` |
| `advertiseMode()` | — (`ALWAYS` / `NEVER` / `NEGOTIATED`) | `.../server/extensions/AdvertiseMode.java:9-29` |
| `serverSettings()` | empty | `.../server/extensions/ServerExtension.java:13` |
| `methods()` | empty set (pre-declared owned methods) | `:24` |
| `negotiation()` | **`REQUIRED`** (`REQUIRED` / `OPTIONAL`) | `:34`, `.../server/extensions/ExtensionNegotiation.java` |
| `requiresMetaEnvelope()` | **true** | `:39` |
| `bootstrap(ExtensionContext)` | no-op | `:44` |
| `onConnectionInit(ctx, clientSettings)` | no-op | `:47` |
| `onConnectionClose(ctx)`, `shutdown()` | no-op | `Extension.java:15-21` |

`ExtensionContext` = `tools/resources/prompts/completions/tasks`, `executor`, `runtime`, `registerHandler(method, ExtensionMethodHandler)` `tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionContext.java:17-48`. `DefaultTachyonServer` implements it `DefaultTachyonServer.java:94`.

`ExtensionMethodHandler.handle(InteractionContext, JsonObject params)` → `Object` (null ⇒ protocol empty result) `ExtensionMethodHandler.java:19-31`, adapter `DefaultTachyonServer.java:571-611`.

## 🔁 Lifecycle

Spring extensions still bootstrap during server construction. Discovered annotated beans register later, after singleton initialization, through the existing registries (`integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java:52`, `TachyonBeanRegistrar.java:20`); see [[integrations]].

1. `withExtensions(...)` — duplicate id ⇒ IAE `DefaultServerBuilder.java` `addExtension`.
2. Ctor `bootstrapExtensions`: record `methods()` owners, set `bootstrappingExtensionId`, call `bootstrap(this)`; any `registerHandler` during bootstrap also owned `DefaultTachyonServer.java:571-576`, `:613-626`.
3. Features registered with `descriptor.extensionId(...)` hidden from lists/calls unless enabled on ctx (`ToolMethodHandlers`, `PromptMethodHandlers`, `ResourceMethodHandlers` filters).
4. Negotiation → `ExtensionNegotiator.negotiate(extensions, ctx, declared)`: enable matching ids + `onConnectionInit` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/ExtensionNegotiator.java:35-44`.
   - 2025-11-25: once in `InitializeHandler` from `initialize` params, enabled ids stored on **session** (persisted) `InitializeHandler.java:40-56`, `DefaultDispatchContext.java:105-118`.
   - 2026-07-28: every POST, `ExtensionNegotiationHandler` in pipeline, on fresh channel ctx `tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/transport/ExtensionNegotiationHandler.java:47-77`. Non-object params ⇒ `RequestMappingException` swallowed, **no extension enabled**, request passes on so dispatcher answers `invalid_params` `:66-75`.
5. Advertise in `initialize`/`server/discover`: `ALWAYS` always, `NEGOTIATED` if enabled, `NEVER` never `ExtensionNegotiator.java:52-62`.
6. `close()` → `shutdownExtensions(deadline)` each on own VT, joined with remaining grace; slow one logged + abandoned `DefaultTachyonServer.java:635-663`.

## 🚪 Routing gate

Order: resolve handler first (`server.getHandler`, none ⇒ `methodNotFound`), then `McpDispatcher.extensionNegotiationRejection` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java:286-294`, `:326-334`, `:345-360`:

| Owner | Policy | Declared on ctx | `_meta.<extId>` (if `requiresMetaEnvelope`) | Result |
|---|---|---|---|---|
| none | — | — | — | dispatch (core) |
| ext | `OPTIONAL` | any | skipped | dispatch |
| ext | `REQUIRED` | no | — | `ServerErrors.missingRequiredExtension(id)` ⇒ -32021 / HTTP 400 (2026), -32003 (2025), `data.requiredCapabilities.extensions.<id>:{}` |
| ext | `REQUIRED` | yes | missing | `invalidParams("Missing required client capability: <id>")` |
| ext | `REQUIRED` | yes | present / not required | dispatch |

Policy snapshot at bootstrap into `optionalNegotiationExtensionIds` `DefaultTachyonServer.java:126`, `:623`, `:688-689`. "Declared on ctx" = session (2025) or per-request channel ctx (2026) — no leak across 2026 requests. `OPTIONAL` never enables the ext: handler sees `isExtensionEnabled=false`, no `onConnectionInit`. Fix `582f9c52`: Skills sets `requiresMetaEnvelope=false`.

Capability requirement inside a handler: throw `MissingRequiredClientCapabilityException(msg, requiredCaps)` ⇒ -32021 (2026) `tachyon-core/src/main/java/dev/tachyonmcp/core/server/domain/MissingRequiredClientCapabilityException.java:13`. Tasks gate helper `TasksExtension.requireDeclared` (shares `ServerErrors.missingRequiredExtension` `ServerErrors.java:75`) returns error only for session-less protocols `TasksExtension.java:26-31`.

## 📚 Known extensions

| Id | Impl | Mode | Page |
|---|---|---|---|
| `io.modelcontextprotocol/tasks` | `TasksExtension` (core) | ALWAYS | [[tasks]] |
| `io.modelcontextprotocol/skills` | `SkillsExtension` | ALWAYS, no meta, negotiation via builder (default **REQUIRED**, same as SPI default; `OPTIONAL` opt-in) | [[tachyon-extensions]] |
| `dev.tachyonmcp/kotlin-coroutines` | `CoroutineRuntime` (internal, lifecycle only) | NEVER | [[tachyon-kotlin]] |

Related: [[feature-registries]], [[protocol-versions]].
