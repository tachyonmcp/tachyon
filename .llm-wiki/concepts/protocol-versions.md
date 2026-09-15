---
title: Protocol versions
tags: [concept, protocol, mcp]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/, tachyon-core/src/main/resources/META-INF/services/dev.tachyonmcp.core.protocol.Protocol, tachyon-core/ts2java.py, tachyon-core/protocol/, tachyon-core/pom.xml]
updated: 2026-09-15
commit: 9eec1092
---

# 🔀 Protocol versions

Verdict: `Protocol` SPI via ServiceLoader, two impls registered. Negotiation = highest `versionString` among protocols whose `matches(request)` is true. Mappers isolate wire shape; handlers see protocol-neutral domain types.

## 🔌 SPI

`Protocol` `Protocol`: `endpoint`, `familyName`, `versionString` (ISO date ⇒ lexical = chronological), `priority`, `supportsSessions` (`Protocol#supportsSessions`), `matches(HttpRequest)`, `responseMapper`, `requestMapper`, `createInteractionContext`, `requestHandlers(server)` (`Protocol#requestHandlers`, must be `@Sharable`, must no-op for other versions).

Registry `Protocols` static ServiceLoader, fails if empty `Protocols#PROTOCOLS`; `resolve` = filter `matches` → max by version then priority `Protocols#resolve`. Services file `tachyon-core/src/main/resources/META-INF/services/dev.tachyonmcp.core.protocol.Protocol` (2025 first, 2026 second — `getFirst()` callers depend on this order, see [[findings]]).

## ⚖️ Compare

| Aspect | 2025-11-25 | 2026-07-28 |
|---|---|---|
| Impl | `protocol/mcp/v2025_11_25/McpProtocol.java` | `protocol/mcp/v2026_07_28/McpProtocol.java` |
| `matches` POST | header absent **or** in `{2025-11-25, 2025-06-18, 2025-03-26}` [McpProtocol#matches](../../tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2025_11_25/McpProtocol.java) | header == `2026-07-28` [McpProtocol#matches](../../tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/McpProtocol.java) |
| `matches` GET/DELETE/OPTIONS | yes (endpoint only) | no |
| `supportsSessions` | true | **false** `McpProtocol#supportsSessions` |
| Handshake | `initialize` + `notifications/initialized` | none; `server/discover` |
| Extensions | once at `initialize` (`InitializeHandler`) | per request `_meta."io.modelcontextprotocol/clientCapabilities".extensions` |
| Pipeline handlers | no-op `RequestValidationHandler` `RequestValidationHandler.java` | `RequestValidationHandler(server)` + `ExtensionNegotiationHandler(extensions)` `McpProtocol.java` |
| Logging threshold | `logging/setLevel` per session | `_meta."io.modelcontextprotocol/logLevel"` per request; absent ⇒ **no logs** `NotificationsImpl#shouldEmit` |
| Resource updates | `resources/subscribe` + GET SSE | `subscriptions/listen` stream (SEP-2575) |
| Tasks | legacy `tasks/list`, `tasks/result`, task-augmented `tools/call` | `tasks/get|cancel|update` gated on tasks extension (SEP-2663) |
| Error codes | JSON-RPC std + -32001..-32004, HTTP 200 | -32020/-32021/-32022, HTTP 400/404 — [[errors]] |
| Result envelope | plain | `resultType: complete|input_required|task` |

Request mappers: shared logic in `AbstractMcpRequestMapper` `AbstractMcpRequestMapper` (all mapping methods + `_meta` helpers). Each version is `final` and only supplies `convert(ObjectNode, Class)` with its generated `CodecRegistry`:
- 2025-11-25: own codec, else `invalid_params("Unsupported params type …")` `McpRequestMapper.java`.
- 2026-07-28: own codec → fallback to 2025-11-25 codec → `invalid_params`; plus overrides `callTool`, `supportsLegacyTaskAugmentation=false`, `supportsSubscriptionsListen=true`, `subscriptionsListen` `McpRequestMapper.java`.

Params rules `AbstractMcpRequestMapper`: `null` ⇒ empty object; `ObjectNode`/`Map` accepted; any other JSON (array, scalar) ⇒ `RequestMappingException(invalid_params "Params must be an object")`; codec `JacksonException` ⇒ `invalid_params` with Jackson message. `declaredExtensions`, `permittedLogLevel`, `hasMetaKey` also throw on non-object params/`_meta` `ProtocolRequestMapper#supportsLegacyTaskAugmentation`.

Response mapper 2026 extends 2025 one `McpResponseMapper.java`.

## 🛂 2026-07-28 request validation

`v2026_07_28/transport/RequestValidationHandler.java` (peeks `content().duplicate()`, malformed JSON passes through to normal parse error):

1. Removed methods ⇒ method not found: `initialize, ping, logging/setLevel, resources/subscribe, resources/unsubscribe` `RequestValidationHandler#REMOVED_METHODS`.
2. `_meta` object required; `io.modelcontextprotocol/protocolVersion` string; `clientInfo` shape if present; `clientCapabilities` object `RequestValidationHandler#validate`.
3. Header `MCP-Protocol-Version` == `_meta` version else header mismatch `RequestValidationHandler#validate`.
4. `Mcp-Method` == body method (requests **and** notifications) `RequestValidationHandler#validateMethodHeader`.
5. `Mcp-Name` == `name` (tools/call, prompts/get) or `uri` (resources/read); Base64 sentinel `=?base64?…?=` decoded `RequestValidationHandler#validate`, `RequestValidationHandler#decodeName`.
6. `Mcp-Param-*` chars: HTAB/space/visible ASCII only `RequestValidationHandler#findInvalidCharacterParamHeader`.
7. `tools/call`: every top-level property with `x-mcp-header` in tool input schema must have matching `Mcp-Param-<name>`; numbers compared by `BigDecimal`, |n| ≤ 2^53-1; header without body value rejected; Base64 padding strict `RequestValidationHandler`, `RequestValidationHandler#decodeParamValue`.

Registration side: `x-mcp-header` only on top-level `string|integer|boolean` props, HTTP-token name, case-insensitively unique `JsonSchemaUtils`.

Header names `McpHeaderNames` `McpHeaderNames#MCP_SESSION_ID`: `MCP-Session-Id`, `MCP-Protocol-Version`, `Last-Event-ID`, `Mcp-Method`, `Mcp-Name`, `Mcp-Param-`, schema keyword `x-mcp-header`.

## 🗺️ Mapper surface

`ProtocolRequestMapper` `ProtocolRequestMapper` — `page`, `callTool`, `getPrompt`, `readResource`, `complete`, `resourceUri`, `taskGet/Cancel/AwaitResult/Update`, `loggingLevel`, `initialize`, `declaredExtensions`, `permittedLogLevel`, `hasMetaKey`, `cancellation`, `taskStatus`, `subscriptionsListen`. Records: `PageRequest`, `ToolCallRequest(request, taskAugmented)`, `CompletionReference` sealed, `SubscriptionListenRequest`.

`ProtocolResponseMapper` `ProtocolResponseMapper` — `encode`, `emptyResult`, `error`, `discoverResult`, `initializeResult`, `list*Result`, `callToolResult`, `readResourceResult`, `getPromptResult`, `inputRequiredResult`, task results, notification params (`loggingMessageParams`, `progressNotificationParams`, `taskStatusNotificationParams`, subscriptions ack/list-changed/resource-updated/graceful).

## 🏭 Codegen (ts2java)

- Wire models + streaming Jackson codecs generated from TypeScript schema: `tachyon-core/protocol/mcp-2025-11-25.ts`, `mcp-2026-07-28.ts` + `*_config.json`.
- Generator `tachyon-core/ts2java.py` (records + Jackson streaming codecs, `@Generated`).
- Bound in `pom.xml` via `exec-maven-plugin` at `generate-sources`; output `target/generated-sources/ts2java/java` added as source root (`pom.xml`). Generated packages: `dev.tachyonmcp.core.protocol.mcp.v20xx.models`, `...codecs`.
- ⚠️ Generated code not in git. Handwritten mappers live next to generated codecs in `codecs/`.
- Revapi ignores generated classes (commit `3cc96c5f`).

## ➕ Adding a protocol version (checklist)

1. TS schema + config in `tachyon-core/protocol/`, exec execution in `tachyon-core/pom.xml`.
2. `McpProtocol` impl + request mapper (`extends AbstractMcpRequestMapper`, implement `convert` with fallback to older codecs) + response mapper (extend previous).
3. Register in services file.
4. Pipeline handlers (`@Sharable`, version-check first).
5. e2e package `e2e/src/test/java/dev/tachyonmcp/e2e/mcp/v<ver>/` + testkit client. See [[testing]].

Related: [[netty-pipeline]], [[extensions]], [[errors]].
