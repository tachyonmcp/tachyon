---
title: "Kotlin Serialization + JSON Schemas"
weight: 45
sidebar_order: 45
toc: true
description: |-
  Generate JSON Schemas from kotlinx.serialization classes with kt-schema and wire them into Tachyon tool contracts.
---

[kt-schema](https://github.com/kpavlov/kt-schema) generates
[JSON Schema 2020-12](https://json-schema.org/draft/2020-12) from Kotlin classes at runtime.
Use it when Kotlin models should define your tool contract. This removes hand-written schema
strings that can drift from the handler.

The runnable
[Weather MCP Kotlin example](https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp-kotlin)
uses the reflection generator for tool input, structured output, and elicitation schemas.

## Understand the request and response path

The [MCP 2026-07-28 tool specification](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
defines two related contracts: `inputSchema` describes `tools/call` arguments, and
`outputSchema` describes the result's `structuredContent`. Schemas without `$schema` use
JSON Schema 2020-12, as defined by the
[MCP JSON Schema rules](https://modelcontextprotocol.io/specification/2026-07-28/basic#json-schema-usage).

Tachyon implements the contract in this order:

| Phase | MCP data | Tachyon code |
|---|---|---|
| Discovery | `tools/list` returns `inputSchema` and `outputSchema` | `ToolDescriptor` |
| Request | `tools/call.params.arguments` | Validated before the handler, then exposed by `request.arguments()` |
| Handler | Application logic returns a domain value | `ToolResult.structured(value)` |
| Response | `result.structuredContent` | Serialized by the configured payload serde, validated against `outputSchema`, then encoded |
| Compatibility | `result.content` contains serialized JSON text | Tachyon adds the text block when the handler doesn't provide one |

Schema generation doesn't deserialize arguments. The weather handler reads its already-validated
`Args` through `request.arguments()`. It maps the provider's `WeatherObservation` into a
`GetWeatherResponse`, which is serialized after the handler returns.

## Add the dependencies

The weather example pairs kt-schema with kotlinx.serialization JSON. Its `pom.xml` pins the
versions used here:

```xml
<properties>
    <kotlinx-serialization-json.version>1.11.0</kotlinx-serialization-json.version>
    <kt-schema.version>0.8.3</kt-schema.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.jetbrains.kotlinx</groupId>
        <artifactId>kotlinx-serialization-json</artifactId>
        <version>${kotlinx-serialization-json.version}</version>
    </dependency>
    <dependency>
        <groupId>me.kpavlov.kt.schema</groupId>
        <artifactId>kt-schema-generator-json-jvm</artifactId>
        <version>${kt-schema.version}</version>
    </dependency>
</dependencies>
```

Add these dependencies to an existing [`tachyon-kotlin`](./#dependency) application.

## Typed echo and simple reverse tools

The [echo-kotlin project](https://github.com/tachyonmcp/tachyon/tree/main/examples/echo-kotlin)
shows typed `echo` returning a response model and simple `reverse-echo` reading a string
and returning text. The typed tool generates schemas from models; the simple tool uses a
hand-written input schema. This version explicitly uses
kotlinx.serialization for typed payloads.

### Configure the build

For a Gradle application, create `settings.gradle.kts` with
`rootProject.name = "echo-server"` and use this `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("dev.tachyonmcp:tachyon-bom:1.0.0-beta.28"))
    implementation("dev.tachyonmcp:tachyon-kotlin-kt-schema")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

kotlin { jvmToolchain(21) }

application { mainClass = "EchoServerKt" }
```

For Maven, import the [Tachyon BOM](../quickstart.md#java--maven), add
`tachyon-kotlin-kt-schema` and `kotlinx-serialization-json` (version `1.11.0`),
and enable serialization in your existing Kotlin Maven plugin:

```xml
<configuration>
    <jvmTarget>21</jvmTarget>
    <compilerPlugins>
        <plugin>kotlinx-serialization</plugin>
    </compilerPlugins>
</configuration>
<dependencies>
    <dependency>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-maven-serialization</artifactId>
        <version>${kotlin.version}</version>
    </dependency>
</dependencies>
```

Use the same `kotlin.version` as the Kotlin Maven plugin (`2.2.21` here).
`tachyon-kotlin-kt-schema` supplies the Kotlin DSL and the runtime schema factory.
The serialization compiler plugin generates payload serializers; schema generation and payload
serialization are separate steps.

### Define the models and register both tools

Save this as `src/main/kotlin/EchoServer.kt`:

```kotlin
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.kpavlov.kt.schema.Description

@Serializable
@SerialName("EchoRequest")
data class EchoRequest(
    @Description("Message to echo")
    val message: String,
)

@Serializable
@SerialName("EchoResponse")
data class EchoResponse(
    @Description("Response message")
    val reply: String,
)

fun main() {
    val server = buildServer {
        capabilities { tools { mode = Mode.ON } }
        network {
            host = "127.0.0.1"
            port = 8080
        }
        info {
            name = "echo-server"
            version = "1.0"
        }
        json { serde = KxSerializationSerde.Default }
        typedTool<EchoRequest, EchoResponse>(
            name = "echo",
            description = "Echo message",
        ) { input ->
            EchoResponse(input.message)
        }
    }

    server.registerTool(
        name = "reverse-echo",
        description = "Echo reverse message",
        inputSchema = JsonSchema.unchecked(
            """
            {
              "type": "object",
              "properties": {
                "message": {"type": "string", "description": "Message to echo"}
              },
              "required": ["message"]
            }
            """,
        ),
    ) {
        text(arguments.stringValue("message").reversed())
    }

    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    server.start()
}
```

Run `gradle --console=plain run`. Connect an MCP client to `http://127.0.0.1:8080/mcp`.
For a curl request, use the [quickstart request](../quickstart.md#3-test-with-curl), setting both
the `Mcp-Name` header and `params.name` to the tool name below and replacing `params.arguments`.

| Tool | Arguments | Result |
|---|---|---|
| `echo` | `{"message":"Hello, MCP!"}` | `structuredContent: {"reply":"Hello, MCP!"}`, plus a JSON text block |
| `reverse-echo` | `{"message":"stressed"}` | Text `desserts` |

The `echo` tool publishes generated `EchoRequest` and `EchoResponse` schemas.
The `reverse-echo` tool publishes its literal input schema and has no output schema. `typedTool` decodes its input before invoking the lambda;
the simple tool reads `arguments.stringValue("message")` directly. Missing or non-string
`message` values fail input validation before either handler runs.

For a standalone simple server with no model dependency, see
[Tool handlers](./#tool-handlers).

## Complete weather tool integration

The following code comes from `examples/weather-mcp-kotlin`. The production example keeps each
model in its own file.

### Define the input and output models

`GetWeatherRequest` uses `@Description` for schema descriptions and a default value for the optional
temperature unit. `@SerialName` pins the generated `$id`/`$ref` name so it doesn't change if the
class is renamed or moved:

```kotlin
package com.example.weather.model

import kotlinx.serialization.SerialName
import me.kpavlov.kt.schema.Description

@SerialName("GetWeatherRequest")
data class GetWeatherRequest(
    @Description("City name (e.g., London, Tokyo, New York)")
    val city: String,
    @Description("Temperature unit (default: Celsius)")
    val units: TemperatureUnit = TemperatureUnit.Celsius,
)
```

The enum supplies the schema values:

```kotlin
package com.example.weather.model

import kotlinx.serialization.SerialName
import me.kpavlov.kt.schema.Description

@Description("Unit used to represent temperature")
@SerialName("TemperatureUnit")
enum class TemperatureUnit {
    Celsius,
    Fahrenheit,
}
```

The handler's domain provider returns `WeatherObservation`, an SPI type owned by weather
providers. It carries no `city` field — the caller already knows which city it asked for, so
echoing it back would be redundant:

```kotlin
package com.example.weather.spi

import com.example.weather.model.TemperatureUnit
import kotlinx.serialization.Serializable

@Serializable
data class WeatherObservation(
    val condition: String,
    val temperature: Double,
    val temperatureUnit: TemperatureUnit,
    val humidity: Int,
    val windSpeed: Double,
)
```

The tool returns a dedicated wire model instead of the SPI type. `GetWeatherResponse` lives in
`model`, next to `GetWeatherRequest`, and is what `ToolResult.structured` actually serializes.
Keeping it separate from `WeatherObservation` lets the domain type evolve independently of the
tool's public contract:

```kotlin
package com.example.weather.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.kpavlov.kt.schema.Description

@Serializable
@SerialName("GetWeatherResponse")
data class GetWeatherResponse(
    @Description("City name")
    val city: String,
    @Description("Weather condition")
    val condition: String,
    @Description("Temperature in the response unit")
    val temperature: Double,
    @Description("Temperature unit")
    val temperatureUnit: TemperatureUnit,
    @Description("Relative humidity percentage")
    val humidity: Int,
    @Description("Wind speed in km/h")
    val windSpeed: Double,
)
```

### Generate both tool schemas

Create one generator and use the same source models as the handler:

```kotlin
import com.example.weather.model.GetWeatherRequest
import com.example.weather.model.GetWeatherResponse
import dev.tachyonmcp.kotlin.server.features.tools.ToolDescriptor
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig
import me.kpavlov.kt.schema.generator.json.ReflectionClassJsonSchemaGenerator

private val schemaGenerator =
    ReflectionClassJsonSchemaGenerator(
        json = kotlinx.serialization.json.Json { encodeDefaults = false },
        config = JsonSchemaConfig.Default,
    )

val getWeatherToolDescriptor =
    ToolDescriptor {
        name = "get-weather"
        title = "Current Weather"
        description = "Get current weather for a city"
        inputSchema(schemaGenerator.generateSchemaString(GetWeatherRequest::class))
        outputSchema(schemaGenerator.generateSchemaString(GetWeatherResponse::class))
    }
```

`generateSchemaString` returns encoded JSON Schema. The `ToolDescriptor` builder accepts that
string directly and Tachyon validates the schema when the tool is registered.

The running weather server publishes both generated schemas through `tools/list`:

<details>
<summary>Show the complete <code>tools/list</code> JSON response</summary>

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "description": "Get current weather for a city",
        "inputSchema": {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$id": "GetWeatherRequest",
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "City name (e.g., London, Tokyo, New York)"
            },
            "units": {
              "$ref": "#/$defs/TemperatureUnit",
              "description": "Temperature unit (default: Celsius)"
            }
          },
          "additionalProperties": false,
          "required": [
            "city"
          ],
          "$defs": {
            "TemperatureUnit": {
              "type": "string",
              "description": "Unit used to represent temperature",
              "enum": [
                "Celsius",
                "Fahrenheit"
              ]
            }
          }
        },
        "outputSchema": {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "$id": "GetWeatherResponse",
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "City name"
            },
            "condition": {
              "type": "string",
              "description": "Weather condition"
            },
            "temperature": {
              "type": "number",
              "description": "Temperature in the response unit"
            },
            "temperatureUnit": {
              "$ref": "#/$defs/TemperatureUnit",
              "description": "Temperature unit"
            },
            "humidity": {
              "type": "integer",
              "description": "Relative humidity percentage"
            },
            "windSpeed": {
              "type": "number",
              "description": "Wind speed in km/h"
            }
          },
          "additionalProperties": false,
          "required": [
            "city",
            "condition",
            "temperature",
            "temperatureUnit",
            "humidity",
            "windSpeed"
          ],
          "$defs": {
            "TemperatureUnit": {
              "type": "string",
              "description": "Unit used to represent temperature",
              "enum": [
                "Celsius",
                "Fahrenheit"
              ]
            }
          }
        },
        "name": "get-weather",
        "title": "Current Weather"
      }
    ]
  }
}
```

</details>

### Return the output model and register the tool

The weather handler maps the provider's `WeatherObservation` to a `GetWeatherResponse` before
passing it to `ToolResult.structured`:

```kotlin
fun attempt(city: String): ToolResult =
    try {
        ToolResult.structured(
            toResponse(
                city,
                fetchWithProgress(
                    ctx,
                    progressToken,
                    weatherService,
                    city,
                    temperatureUnit,
                ),
            ),
        )
    } catch (e: Exception) {
        if (e is CityNotFoundException) throw e
        internalError(e)
    }

private fun toResponse(city: String, weather: WeatherObservation): GetWeatherResponse =
    GetWeatherResponse(
        city = city,
        condition = weather.condition,
        temperature = weather.temperature,
        temperatureUnit = weather.temperatureUnit,
        humidity = weather.humidity,
        windSpeed = weather.windSpeed,
    )
```

The configured payload serde serializes the `GetWeatherResponse` value. Tachyon validates the
serialized value against the generated `outputSchema` before writing the MCP response.

The server selects kotlinx.serialization and registers the generated descriptor with the handler:

```kotlin
return buildServer {
    network { this.port = port }
    json { serde = KxSerializationSerde.Default }

    tool(getWeatherToolDescriptor) { getWeather(weatherService) }
}
```

This gives one end-to-end contract:

| Stage | Source of truth |
|---|---|
| Tool input schema | `GetWeatherRequest` |
| Handler arguments | `request.arguments()` |
| Tool output schema | `GetWeatherResponse` |
| Structured result | `WeatherObservation` mapped to `GetWeatherResponse` and returned through `ToolResult.structured` |
| Payload encoding | `KxSerializationSerde.Default` |

The MCP 2026-07-28 specification permits any JSON value in `structuredContent`, and Tachyon accepts
array- and scalar-root `outputSchema`s under that protocol version. Under 2025-11-25, `structuredContent`
is object-only on the wire; a non-object result still validates against `outputSchema` but falls back
to the serialized-JSON text block. A data class as the top-level result stays the simplest choice when
targeting both protocol versions.

See the exact
[`GetWeatherTool.kt`](https://github.com/tachyonmcp/tachyon/blob/main/examples/weather-mcp-kotlin/src/main/kotlin/com/example/weather/GetWeatherTool.kt)
and
[`WeatherServer.kt`](https://github.com/tachyonmcp/tachyon/blob/main/examples/weather-mcp-kotlin/src/main/kotlin/com/example/weather/WeatherServer.kt)
sources for progress notifications, elicitation, resources, prompts, and error handling.

## Generate other schema types

The same generator creates the weather example's elicitation and prompt input schemas. APIs that
take `JsonSchema` instead of an encoded string use `JsonSchema.parse`:

```kotlin
private val CITY_SCHEMA =
    JsonSchema.parse(
        schemaGenerator.generateSchemaString(CityElicitationInput::class),
    )

private data class CityElicitationInput(
    val city: String,
)
```

The generator configuration determines how Kotlin types map to JSON Schema:

| Kotlin declaration | Generated schema |
|---|---|
| `@Description("...")` | `description` |
| `val city: String` | Required string property |
| `val units: TemperatureUnit = Celsius` | Optional enum property |
| `val value: T?` | Optional nullable property |
| `enum class` | `enum` values |
| Nested type | `$defs` and `$ref` |

The weather example uses `JsonSchemaConfig.Default`, the general-purpose JSON Schema preset.

## Run the source example

From the repository root:

```shell
cd examples/weather-mcp-kotlin
./mvnw package
java -jar target/weather-mcp-kotlin.jar
```

Connect an MCP client to `http://localhost:8080/mcp`, then inspect `get-weather` through
`tools/list` or call it through `tools/call`.

Next, see [JSON and JSON Schema](../json.md) for validation and provider behaviour,
[Tools](../features/tools.md) for tool contracts, the [Kotlin DSL](./) for handler APIs, or the
[MCP 2026-07-28 schema reference](https://modelcontextprotocol.io/specification/2026-07-28/schema)
for wire types.
