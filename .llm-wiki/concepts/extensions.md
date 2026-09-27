---
title: Extensions
tags: [concept, extensions, spi]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ProtocolVersionHandler.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/, tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/ExtensionNegotiator.java, tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/transport/ExtensionNegotiationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/transport/RequestValidationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/extensions/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/TasksExtensionSupport.java]
updated: 2026-09-27
commit: 4316204b
---

# 🧩 Extensions

Negotiation requirements: [extension-negotiation.md](../../specs/extension-negotiation.md)

Verdict: `ServerExtension` = bootstrap hook (register features + custom JSON-RPC methods through `ExtensionContext`) + negotiation hook. Methods an extension registers are **owned** by it. `negotiation()` policy: `OPTIONAL` (default, SEP-2133 graceful degradation) ⇒ dispatched regardless, nothing synthesized; `REQUIRED` (opt-in, mandatory ext) ⇒ client must declare ext on current context, else -32021 (2026) / -32003 (2025).

## 🧱 Contract

| Member | Default | Proof |
|---|---|---|
| `extensionId()` | — | [Extension](../../tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java) |
| `advertiseMode()` | — (`ALWAYS` / `NEVER` / `NEGOTIATED`) | [AdvertiseMode](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/AdvertiseMode.java) |
| `serverSettings()` | empty | [ServerExtension#serverSettings](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `methods()` | empty set (pre-declared owned methods) | [ServerExtension#methods](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `negotiation()` | **`OPTIONAL`** (`OPTIONAL` / `REQUIRED`) | [ServerExtension#negotiation](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java), `.../server/extensions/ExtensionNegotiation.java` |
| `bootstrap(ExtensionContext)` | no-op | [ServerExtension#bootstrap](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `onConnectionInit(ctx, clientSettings)` | no-op | [ServerExtension#onConnectionInit](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| `onConnectionClose(ctx)`, `shutdown()` | no-op | [Extension#onConnectionClose](../../tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java), [Extension#shutdown](../../tachyon-api/src/main/java/dev/tachyonmcp/api/runtime/Extension.java) |

[ExtensionContext](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionContext.java) = `tools/resources/prompts/completions`, `executor`, `runtime`, `registerHandler(method, ExtensionMethodHandler)` [ExtensionContext](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionContext.java). [DefaultTachyonServer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java) implements it [DefaultTachyonServer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).

### ⚙️ Typed configuration

`ServerBuilder#withExtension(Class<E>, Consumer<? super B>)`: `E extends ConfigurableExtension<B>`, `B extends ExtensionBuilder<E>`, so the configurer sees the extension's own builder. Same type twice ⇒ one builder, configurers in call order; `build()` once per server build `DefaultServerBuilder#resolveExtensions`. Lookup `TachyonServer#extension(Class)`. Stable; `ProvidedBy` + `spi.ExtensionProvider` stay `@ExperimentalApi`.
- Provider lookup per type `ExtensionProviders#providerFor` (`ClassValue` cache): `@ProvidedBy` on the class first (checked `extensionType()`), else `ServiceLoader` on the type's class loader; none/ambiguous ⇒ ISE. A provider on the classpath enables nothing.
- Provider that is also `EngineBinding` bootstraps the extension on `ServerEngine` instead of `bootstrap(ExtensionContext)` `DefaultTachyonServer#bootstrapExtensions`: keeps internal types off the extension's public API. Instance-registered internal extensions use `EngineExtension`.

`ExtensionMethodHandler.handle(InteractionContext, JsonObject params)` → `Object` (null ⇒ protocol empty result) [ExtensionMethodHandler](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionMethodHandler.java), adapter [DefaultTachyonServer#getHandler](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).
- `params` typed `@Nullable`, but adapter never passes null: absent/non-object ⇒ `JsonObject.empty()` `DefaultTachyonServer#toJsonObject`.
- Handler exception ⇒ `ServerErrors#fromUnhandledException`, same as tools/prompts/resources/completions: `InvalidArgumentException` ⇒ `-32602` with message, other `IllegalArgumentException` ⇒ redacted `-32602 Invalid params`, rest ⇒ `-32603 Internal error`. HTTP status per revision (2026-07-28 invalid params ⇒ 400) `McpDispatcher#handleHandlerError`.
- 2026-07-28 call = normal request: `Mcp-Method` header MUST mirror body `method` (else `-32020`), `_meta` protocolVersion + clientCapabilities required `RequestValidationHandler#validate`.

## 🔁 Lifecycle

Spring extensions still bootstrap during server construction. Discovered annotated beans register later, after singleton initialization, through the existing registries (`ServerConfiguration#tachyonServer`, `TachyonFeatureRegistrar#afterSingletonsInstantiated`); see [[spring-boot]].

1. `withExtensions(...)` — duplicate id ⇒ IAE; a `ConfigurableExtension` instance ⇒ IAE (create it through `withExtension(Class, …)`, so its provider bootstraps it) `DefaultServerBuilder#addExtension`.
2. Ctor `bootstrapExtensions`: record `methods()` owners, set `bootstrappingExtensionId`, call `bootstrap(this)`; any `registerHandler` during bootstrap also owned `DefaultTachyonServer#getHandler`, `DefaultTachyonServer#toJsonObject`. A throwing `bootstrap` fails the build with that exception: extensions already bootstrapped get `shutdown()` newest-first, a shutdown error is added as suppressed, the failing and later extensions never start `DefaultTachyonServer#bootstrapExtensions`, `DefaultTachyonServer#unwindBootstrapped`.
3. Features registered with `descriptor.extensionId(...)` hidden from lists/calls unless enabled on ctx (`ToolMethodHandlers`, `PromptMethodHandlers`, `ResourceMethodHandlers` filters).
4. Negotiation → `ExtensionNegotiator.negotiate(extensions, ctx, declared)`: enable matching ids + `onConnectionInit` `ExtensionNegotiator#negotiate`.
   - 2025-11-25: `InitializeHandler#handle` negotiates from `initialize` capabilities. `DefaultDispatchContext#enableExtension` stores declarations on the session when present. Stateless servers get a fresh context per POST, so declarations last only for `initialize`, even on a reused connection; later OPTIONAL calls see `false`, REQUIRED calls reject. [ProtocolVersionHandler#channelRead](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ProtocolVersionHandler.java), [StatelessExtensionNegotiationTest](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/v2025_11_25/StatelessExtensionNegotiationTest.java).
   - 2026-07-28: every POST, `ExtensionNegotiationHandler` in pipeline, on fresh channel ctx `ExtensionNegotiationHandler#channelRead`. Non-object params ⇒ `RequestMappingException` swallowed, **no extension enabled**, request passes on so dispatcher answers `invalid_params` `RequestMappingException`.
5. Advertise in `initialize`/`server/discover`: `ALWAYS` always, `NEGOTIATED` if enabled, `NEVER` never `ExtensionNegotiator#registeredExtensions`.
6. `close()` → `shutdownExtensions(deadline)` each on own VT, joined with remaining grace; slow one logged + abandoned `DefaultTachyonServer#bootstrapExtensions`.

## 🚪 Routing gate

Order: resolve handler first (`server.getHandler`, none ⇒ `methodNotFound`), then `McpDispatcher.extensionNegotiationRejection` `McpDispatcher#dispatchTrackedRequestAsync`, `McpDispatcher#dispatchTrackedRequestAsync`, `McpDispatcher#extensionNegotiationRejection`:

| Owner | Policy | Declared on ctx | Result |
|---|---|---|---|
| none | — | — | dispatch (core) |
| ext | `OPTIONAL` | any | dispatch |
| ext | `REQUIRED` | no | `ServerErrors.missingRequiredExtension(id)` ⇒ -32021 / HTTP 400 (2026), -32003 (2025, Tachyon-defined), `data.requiredCapabilities.extensions.<id>:{}` |
| ext | `REQUIRED` | yes | dispatch |

No per-call `_meta.<extId>` envelope: neither MCP spec nor SEP-2133 defines one, so the old `requiresMetaEnvelope()` gate was removed.

Policy snapshot at bootstrap into `optionalNegotiationExtensionIds` `DefaultTachyonServer#optionalNegotiationExtensionIds`, `DefaultTachyonServer#bootstrapExtensions`, `DefaultTachyonServer#extensionNegotiationOptional`. `REQUIRED` on a stateless server ⇒ startup WARN (2025-11-25 clients always rejected) `DefaultTachyonServer#bootstrapExtensions`. "Declared on ctx" = session (stateful 2025) or per-request channel ctx (stateless 2025 and 2026) — no leak across 2026 requests. Only undeclared `OPTIONAL` requests keep the extension disabled (`isExtensionEnabled=false`, no `onConnectionInit`); declared `OPTIONAL` extensions are enabled and receive `onConnectionInit`.

Capability requirement inside a handler: throw `MissingRequiredClientCapabilityException(msg, requiredCaps)` ⇒ -32021 (2026) `MissingRequiredClientCapabilityException`. Tasks gate helper `TasksExtensionSupport.requireDeclared` (shares `ServerErrors.missingRequiredExtension` `ServerErrors#missingRequiredExtension`) returns error only for session-less protocols `TasksExtensionSupport#requireDeclared`.

## 📚 Known extensions

| Id | Impl | Mode | Page |
|---|---|---|---|
| `io.modelcontextprotocol/tasks` | `TasksExtension` (`tachyon-extensions-tasks`, via `withExtension`) | ALWAYS | [[tasks]] |
| `io.modelcontextprotocol/skills` | `SkillsExtension` | ALWAYS, negotiation via builder (default **OPTIONAL**, same as SPI default; `REQUIRED` opt-in) | [[tachyon-extensions-skills]] |
| `dev.tachyonmcp/kotlin-coroutines` | `CoroutineRuntime` (internal, lifecycle only) | NEVER | [[tachyon-kotlin]] |

Related: [[feature-registries]], [[protocol-versions]].
