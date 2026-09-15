---
title: JSON layer
tags: [concept, json, schema]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/json/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/, tachyon-core/src/main/resources/META-INF/services/, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/json/]
updated: 2026-09-14
commit: 70e205dc
---

# 🧾 JSON layer

Verdict: three independent JSON concerns. (1) **JSON-RPC envelope** — hand-rolled Jackson 3 streaming, raw-value passthrough. (2) **User payloads** — `PayloadSerializer/Deserializer` (Jackson default, kotlinx option). (3) **Schemas/documents** — library-neutral `JsonDocument`/`JsonSchema` in api, factories discovered by ServiceLoader keyed on source type.

## ✉️ JSON-RPC codec

`JsonRpcCodec` `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java`:
- `parseRequest(ByteBuf)` streaming; `params` read as Jackson tree (`JsonNode`), `result`/`error.data` kept **raw JSON string** `JsonRpcCodec#parseRequest`, `JsonRpcCodec`.
- Classification priority: error (code+message) > result > method+id ⇒ `Request` > method ⇒ `Notification` > IAE `Notification`.
- `id`: long / double / string / null `JsonRpcCodec#parseId` → `RequestId` (`tachyon-api/.../server/domain/RequestId.java`).
- Serialize to `byte[]` (GC-managed, not pooled — dropped response on shutdown ≠ leak) `RequestId`.
- `JsonRpcMessage` sealed `Request<T> | Response | Error | Notification<T>` `JsonRpcMessage`. `JsonRpcError(code, message, data, httpStatus=200)` `JsonRpcError`.
- `ValueSerializer` plain Map/List/scalar writer (unknown ⇒ `toString()`) `ValueSerializer#writeJsonValue`.

## 📄 Documents & schemas (api)

| Type | Proof |
|---|---|
| `JsonDocument` — `json()`, `unwrap(Class)`, `of(String)` (no validation), `parse`, `from(source, type)` | `JsonDocument` |
| `JsonObject` / `JsonArray` typed accessors (`stringOpt`, `intOpt` exact, …) | `JsonObject` |
| `JsonSchema` (`unchecked`, `generate(Class)`) | `JsonSchema.java` |
| `JsonSchemaValidator` + `noop()` | `JsonSchemaValidator` |
| SPI `JsonDocumentFactory<T>` / `JsonSchemaFactory<T>` (`sourceType`, `priority`) | `json/spi/*.java` |

ServiceLoader registrations:

| Services file | Impls |
|---|---|
| core `...JsonDocumentFactory` | `Jackson3JsonFactory`(String), `JacksonNodeJsonFactory`, `JacksonObjectJsonFactory` |
| core `...JsonSchemaFactory` | above 3 + `KtSchemaResourceFactory`(Class, prio 0), `JavaTypeSchemaFactory`(Class, prio `1000`), `MapJsonFactory`(Map) |
| kotlin | `KotlinxJsonElementFactory`, `KotlinxJsonObjectFactory` |
| kt-schema | `KtSchemaReflectionFactory` (Class, prio 10; declines on generator failure, e.g. Java record with `int`) |

`JsonSchema.generate(Class)` chain = codegen resource → kt-schema reflection → Java reflection. `JavaTypeSchemas` covers records (required unless `Optional`/JSpecify `@Nullable`), POJOs (public getters/fields, only primitives required), enums, arrays/collections, maps (`additionalProperties`), `java.time`/`UUID`/`URI` formats; cycles ⇒ bare `object`. Factory declines non-object types so chain still fails for them `JavaTypeSchemaFactory#priority`.

Server requires **exactly one** String-source `JsonSchemaFactory` else ISE `DefaultTachyonServer#discoverSchemaFactory`; used to validate tool schema roots at registration `JsonSchemaUtils#parseSchemaRoot`.

`KtSchemaResourceFactory` looks up `META-INF/kt-schema/schemas/<fqcn path>.json` on context classloader (build-time generated) `KtSchemaResourceFactory#sourceType`.

## ✅ Validation

- Default input **and** output validator `NetworkntJsonSchemaValidator`, dialect **2020-12**, compiled-schema cache keyed by schema JSON string `NetworkntJsonSchemaValidator`.
- Override via `json { inputSchemaValidator / outputSchemaValidator / schemaValidator }` `JsonConfig`. Output validator `noop()` skips output check `ToolsCallHandler#validateOutput`.
- Registration rules (`JsonSchemaUtils`): input root must be `type: object` `JsonSchemaUtils#validateInputSchemaRoot`; output must be object `JsonSchemaUtils#validateOutputSchemaRoot`; `x-mcp-header` rules `JsonSchemaUtils` → [[protocol-versions]].

## 🔄 Payload serde

- Default `JacksonPayloadSerde` over shared `JsonUtils.mapper()` (Duration deserialized from **millis**) `JacksonPayloadSerde`, `JsonUtils#MAPPER`.
- `ToolRequest.arguments()` = `Args` (wraps JSON; `decode(Class)` via deserializer carried on request) `tachyon-api/.../server/domain/Args.java`.
- Structured results serialized to `JsonDocument` before mapping `JsonUtils.serializeStructured` `JsonDocument`.
- Kotlin: `KxSerializationSerde` → [[tachyon-kotlin]].

⚠️ Jackson **3** (`tools.jackson.*`): `JsonNode.isString()/stringValue()/asString()`, `properties()` — not Jackson 2 names.

Related: [[feature-registries]], [[errors]].
