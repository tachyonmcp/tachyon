---
title: "JSON and JSON Schema"
weight: 41
sidebar_order: 41
toc: true
description: |-
  How Tachyon handles JSON payloads and JSON Schema validation: JsonDocument types, schema dialects, and pluggable validators.
---

Tachyon lets you define schemas, inspect arguments, and return structured content without binding
your application to a specific JSON library. Most applications need four types from
`dev.tachyonmcp.api.json`:

| Type           | Use it for                             |
|----------------|----------------------------------------|
| `JsonSchema`   | Tool input and output schemas          |
| `JsonDocument` | Encoded JSON values                    |
| `JsonObject`   | Typed access to JSON object properties |
| `JsonArray`    | Typed access to JSON array elements    |

## Define a schema

Use `JsonSchema.unchecked` for schema literals you control:

```java
var inputSchema = JsonSchema.unchecked("""
    {
      "type": "object",
      "properties": {
        "city": { "type": "string" },
        "days": { "type": "integer", "minimum": 1 }
      },
      "required": ["city"]
    }
    """);
```

Use `JsonSchema.parse` when the JSON comes from a file, database, or another external source.
It rejects malformed JSON immediately:

```java
JsonSchema inputSchema = JsonSchema.parse(userSuppliedSchema);
```

`JsonDocument` follows the same pattern:

```java
JsonDocument trusted = JsonDocument.of(jsonLiteral);
JsonDocument checked = JsonDocument.parse(externalJson);
```

Kotlin applications can generate schemas from data classes instead of maintaining JSON strings.
See the [kt-schema integration](kotlin/kt-schema-json.md) for a complete example.

## Read objects and arrays

Create a provider-neutral object from standard Java collections:

```java
var user = JsonObject.of(Map.of(
    "name", "Ada",
    "age", 32,
    "roles", List.of("admin", "author")
));

String name = user.stringValue("name");
int age = user.intOr("age", 0);
Optional<JsonObject> address = user.objectOpt("address");
List<String> roles = user.arrayValue("roles").valuesAs(String.class);
```

Choose an accessor based on how your application handles missing or JSON `null` values:

| Pattern | behaviour | Example |
|---|---|---|
| `*Value(name)` | Returns a required value; throws if missing or `null` | `stringValue("name")` |
| `*Opt(name)` | Returns an `Optional` or primitive optional | `objectOpt("address")` |
| `*Or(name, fallback)` | Returns a fallback when missing or `null` | `intOr("age", 0)` |

Accessors never coerce values. Reading a string as an integer, narrowing a fraction to an integer,
or overflowing the requested numeric type throws `IllegalArgumentException`.

`JsonArray` provides the same access patterns by index:

```java
var coordinates = JsonArray.of(List.of(59.437, 24.7536));

double latitude = coordinates.doubleValue(0);
double longitude = coordinates.doubleValue(1);
```

## Reuse an existing JSON tree

If your application already has a Jackson `JsonNode`, wrap it without converting it to a `Map`:

```java
JsonNode node = objectMapper.readTree(source);
JsonDocument document = JsonDocument.from(node, JsonNode.class);
```

You can recover a retained provider value when you need library-specific operations:

```java
JsonNode node = document.unwrap(JsonNode.class).orElseThrow();
```

Pass the type supported by the provider, such as `JsonNode.class`, rather than the source
implementation class.

## Providers and registration

Tachyon includes providers for common Java and Kotlin JSON types:

| Source type                                          | Available with   |
|------------------------------------------------------|------------------|
| `String`                                             | `tachyon-core`   |
| Jackson3 `JsonNode` and `ObjectNode`                 | `tachyon-core`   |
| kotlinx.serialization `JsonElement` and `JsonObject` | `tachyon-kotlin` |

`JsonDocument.parse` and `JsonDocument.from` use a `JsonDocumentFactory<T>`.
`JsonSchema.parse` and `JsonSchema.from` use a `JsonSchemaFactory<T>`. Tachyon discovers both
interfaces with Java's `ServiceLoader` and matches the requested `Class<T>` to the factory's
`sourceType()`.

To support another JSON representation, implement either interface—or both—and register the
implementation class, one name per line, in the matching service file:

```text
META-INF/services/dev.tachyonmcp.api.json.spi.JsonDocumentFactory
META-INF/services/dev.tachyonmcp.api.json.spi.JsonSchemaFactory
```

## Configure payload serialization

Tachyon uses Jackson by default. Supply a `PayloadSerde` when your application needs different
serialization behaviour:

```java
var server = TachyonServer.builder()
    .json(json -> json.serde(myPayloadSerde))
    .port(8080)
    .build();
```

You can also configure `PayloadSerializer` and `PayloadDeserializer` separately. A
`JsonDocument` bypasses payload serialization because it already contains encoded JSON.

## Configure schema validation

Tachyon validates tool input and output against their declared schemas. Replace either validator
when you need custom validation behaviour:

```java
var server = TachyonServer.builder()
    .json(json -> json
        .inputSchemaValidator(myInputValidator)
        .outputSchemaValidator(JsonSchemaValidator.NOOP))
    .port(8080)
    .build();
```

`JsonSchemaValidator.NOOP` disables validation for that direction.

Next, see [Tools](features/tools.md) to attach schemas to tool descriptors or
[Configuration](running/configuration.md) for all JSON settings. Kotlin developers can use the equivalent
DSL described in the [Kotlin API](kotlin/).
