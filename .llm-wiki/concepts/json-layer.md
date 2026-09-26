---
title: JSON layer
tags: [concept, json, schema]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/json/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/json/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/, tachyon-core/src/main/resources/META-INF/services/, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/json/]
updated: 2026-09-26
commit: 31900e6a
---

# 🧾 JSON layer

Verdict: three independent JSON concerns. (1) **JSON-RPC envelope** — hand-rolled Jackson 3 streaming, raw-value passthrough. (2) **User payloads** — `PayloadSerializer/Deserializer` (Jackson default, kotlinx option). (3) **Schemas/documents** — library-neutral `JsonDocument`/`JsonSchema` in api, factories discovered by ServiceLoader keyed on source type.

## ✉️ JSON-RPC codec

[JsonRpcCodec](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java) `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java`:
- `JsonUtils.toObjectTree(Map<String, ?>)` turns domain `_meta`/`experimental` maps into the `ObjectNode` generated records carry. JSON-shaped values (scalars, string-keyed maps, collections, trees) are built straight from `JsonNodeFactory` into a presized node; anything else goes through `valueToTree`. Output equals `valueToTree` per value (`JsonUtilsTest#toObjectTreeBuildsTheSameTreesAsValueToTree`; trees are deep-copied). JMH `JsonNodeMapBenchmark` `JsonUtils#toObjectTree`, `JsonUtils#toTree`.
- Generic value writer `ValueSerializer#writeJsonValue` (maps, lists, scalars, trees; behind `JsonRpcCodec.writeValueAsString`/`toJsonParams`) writes a ts2java-generated model, at any nesting depth, with its generated codec: `<pkg>.models.X` → `<pkg>.codecs.CodecRegistry#codecFor`, the per-class `codecFor` lookup is a `MethodHandle` cached in a `ClassValue`, and the codec is called through the `Codec` interface, so runtime overrides with non-public codec classes work (`GeneratedModelSerializationTest#runtimeOverrideWithNonPublicCodecIsUsed`). Covers core models of both versions and extension models (whose registry falls back to core's). So an `ExtensionMethodHandler` can return generated records directly. Other objects still go out as `toString()` `GeneratedCodecs#encode`.
- `JsonUtils.toParamsNode(Object)` narrows a raw `params` payload (tree, or a decoded `Map`) to one `ObjectNode`; anything else (absent, by-position array) ⇒ empty object, so a validator's field lookups just miss instead of branching on Java type `JsonUtils#toParamsNode`.
- One streaming tree reader/writer for the wire and codecs, `CodecSupport#readTree` / `#readWireTree` / `#writeTree` (token copy, no string round trip). Wire text (`JsonRpcCodec#readTreeValue`, `JsonUtils#parseJsonNode`) widens integers to `Long` so numeric request ids and `_meta` values stay `Long`; codec trees and `JsonUtils#parse` keep Jackson's native width. Integers above `Long.MAX_VALUE` become big-integer nodes.
- `parseRequest(ByteBuf)` streaming; `params` read as Jackson tree (`JsonNode`), `result`/`error.data` kept **raw JSON string** [JsonRpcCodec#parseRequest](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java), [JsonRpcCodec](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java).
- Classification priority: error (code+message) > result > method+id ⇒ `Request` > method ⇒ [JsonRpcMessage.Notification](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcMessage.java) > IAE [JsonRpcCodec#parseRequest](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java).
- `id`: long / double / string / null [JsonRpcCodec#parseId](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java) → [RequestId](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/domain/RequestId.java) (`tachyon-api/.../server/domain/RequestId.java`).
- Serialize to `byte[]` (GC-managed, not pooled — dropped response on shutdown ≠ leak) [JsonRpcCodec#serialize](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcCodec.java).
- [JsonRpcMessage](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcMessage.java) sealed `Request<T> | Response | Error | Notification<T>` [JsonRpcMessage](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcMessage.java). `JsonRpcError(code, message, data, httpStatus=200)` [JsonRpcError](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/JsonRpcError.java).
- [ValueSerializer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/ValueSerializer.java) plain Map/List/scalar/tree writer (generated model ⇒ its codec; other unknown ⇒ `toString()`) [ValueSerializer#writeJsonValue](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/jsonrpc/ValueSerializer.java).

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
