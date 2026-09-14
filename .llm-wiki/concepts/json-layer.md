---
title: JSON layer
tags: [concept, json, schema]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/json/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/, tachyon-core/src/main/resources/META-INF/services/, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/json/]
updated: 2026-09-14
commit: 582f9c52
---

# 🧾 JSON layer

Verdict: three independent JSON concerns. (1) **JSON-RPC envelope** — hand-rolled Jackson 3 streaming, raw-value passthrough. (2) **User payloads** — `PayloadSerializer/Deserializer` (Jackson default, kotlinx option). (3) **Schemas/documents** — library-neutral `JsonDocument`/`JsonSchema` in api, factories discovered by ServiceLoader keyed on source type.

## ✉️ JSON-RPC codec

`JsonRpcCodec` `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java`:
- `parseRequest(ByteBuf)` streaming; `params` read as Jackson tree (`JsonNode`), `result`/`error.data` kept **raw JSON string** `:46-53`, `:147-208`.
- Classification priority: error (code+message) > result > method+id ⇒ `Request` > method ⇒ `Notification` > IAE `:192-207`.
- `id`: long / double / string / null `:210-218` → `RequestId` (`tachyon-api/.../server/domain/RequestId.java`).
- Serialize to `byte[]` (GC-managed, not pooled — dropped response on shutdown ≠ leak) `:339-350`.
- `JsonRpcMessage` sealed `Request<T> | Response | Error | Notification<T>` `JsonRpcMessage.java:9-81`. `JsonRpcError(code, message, data, httpStatus=200)` `JsonRpcError.java:17-27`.
- `ValueSerializer` plain Map/List/scalar writer (unknown ⇒ `toString()`) `ValueSerializer.java:33-60`.

## 📄 Documents & schemas (api)

| Type | Proof |
|---|---|
| `JsonDocument` — `json()`, `unwrap(Class)`, `of(String)` (no validation), `parse`, `from(source, type)` | `tachyon-api/src/main/java/dev/tachyonmcp/api/json/JsonDocument.java:17-77` |
| `JsonObject` / `JsonArray` typed accessors (`stringOpt`, `intOpt` exact, …) | `JsonObject.java:25` |
| `JsonSchema` (`unchecked`, `generate(Class)`) | `JsonSchema.java` |
| `JsonSchemaValidator` + `noop()` | `JsonSchemaValidator.java:12-36` |
| SPI `JsonDocumentFactory<T>` / `JsonSchemaFactory<T>` (`sourceType`, `priority`) | `json/spi/*.java` |

ServiceLoader registrations:

| Services file | Impls |
|---|---|
| core `...JsonDocumentFactory` | `Jackson3JsonFactory`(String), `JacksonNodeJsonFactory`, `JacksonObjectJsonFactory` |
| core `...JsonSchemaFactory` | above 3 + `KtSchemaResourceFactory`(Class), `MapJsonFactory`(Map) |
| kotlin | `KotlinxJsonElementFactory`, `KotlinxJsonObjectFactory` |
| kt-schema | `KtSchemaReflectionFactory` (Class) |

Server requires **exactly one** String-source `JsonSchemaFactory` else ISE `DefaultTachyonServer.java:364-382`; used to validate tool schema roots at registration `JsonSchemaUtils.java:188-204`.

`KtSchemaResourceFactory` looks up `META-INF/kt-schema/schemas/<fqcn path>.json` on context classloader (build-time generated) `tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/KtSchemaResourceFactory.java:40-51`.

## ✅ Validation

- Default input **and** output validator `NetworkntJsonSchemaValidator`, dialect **2020-12**, compiled-schema cache keyed by schema JSON string `tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/NetworkntJsonSchemaValidator.java:17-46`.
- Override via `json { inputSchemaValidator / outputSchemaValidator / schemaValidator }` `tachyon-api/src/main/java/dev/tachyonmcp/api/server/config/JsonConfig.java:20-75`. Output validator `noop()` skips output check `ToolMethodHandlers.java:243-250`.
- Registration rules (`JsonSchemaUtils`): input root must be `type: object` `:40-56`; output must be object `:72-79`; `x-mcp-header` rules `:96-148` → [[protocol-versions]].

## 🔄 Payload serde

- Default `JacksonPayloadSerde` over shared `JsonUtils.mapper()` (Duration deserialized from **millis**) `JacksonPayloadSerde.java:13-47`, `JsonUtils.java:37-76`.
- `ToolRequest.arguments()` = `Args` (wraps JSON; `decode(Class)` via deserializer carried on request) `tachyon-api/.../server/domain/Args.java`.
- Structured results serialized to `JsonDocument` before mapping `JsonUtils.serializeStructured` `:248-262`.
- Kotlin: `KxSerializationSerde` → [[tachyon-kotlin]].

⚠️ Jackson **3** (`tools.jackson.*`): `JsonNode.isString()/stringValue()/asString()`, `properties()` — not Jackson 2 names.

Related: [[feature-registries]], [[errors]].
