// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.e2e

import dev.tachyonmcp.api.server.features.tools.ToolResult.structured
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.domain.arguments
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import dev.tachyonmcp.kotlin.server.json.ktschema.ktSchemaGenerator
import dev.tachyonmcp.kotlin.server.registerTool
import dev.tachyonmcp.testkit.Mcp20251125Client
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.equals.shouldEqual
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig
import org.junit.jupiter.api.Test

/**
 * Exercises the reified `typedTool<In, Out>(...)` DSL overload, backed by the
 * `tachyon-kotlin-kt-schema` integration artifact's `KtSchemaReflectionFactory` (registered via
 * `META-INF/services`; this module opts the artifact in as a test dependency). Split out from
 * [KotlinE2eTest] because this is the only test class in the module that needs it.
 */
internal class TypedToolKotlinE2eTest : AbstractStatelessMcpE2eTest<Mcp20251125Client>() {
    override fun createTestClient(): Mcp20251125Client = createTestClient(port)

    override fun createTestClient(port: Int): Mcp20251125Client = Mcp20251125Client(port)

    @Serializable
    private data class GreetArgs(
        val name: String,
        val greeting: String = "Hello",
    )

    @Serializable
    private data class GreetReply(
        val message: String,
    )

    @Test
    fun `typedTool round-trips with schemas`() {
        TachyonServer(port = 0) {
            typedTool<GreetArgs, GreetReply>(
                name = "greet",
                description = "Typed greet tool",
            ) {
                val input = request.arguments<GreetArgs>()

                structured(
                    GreetReply("${input.greeting}, ${input.name}!"),
                    "greeting response",
                )
            }
        }.use { server ->

            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    // `greeting` is optional in the generated schema (it has a Kotlin default), but the
                    // default Jackson serde has no jackson-module-kotlin to fill it, so it is sent here.
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World","greeting":"Hi"}}}
                    """.trimIndent(),
                )

            response.statusCode() shouldEqual 200
            val body = response.body()
            body shouldEqualJson
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "greeting response"
                      }
                    ],
                    "structuredContent": {
                      "message": "Hi, World!"
                    }
                  }
                }
                """.trimIndent()
        }
    }

    @Test
    fun `typed registerTool decodes input and wraps output post-build`() {
        TachyonServer(port = 0).use { server ->
            server.registerTool<GreetArgs, GreetReply>(
                name = "greet",
                description = "Typed greet tool",
            ) { input ->
                GreetReply("${input.greeting}, ${input.name}!")
            }

            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World","greeting":"Hi"}}}
                    """.trimIndent(),
                )

            response.statusCode() shouldEqual 200
            response.body() shouldEqualJson
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "{\"message\":\"Hi, World!\"}"
                      }
                    ],
                    "structuredContent": {
                      "message": "Hi, World!"
                    }
                  }
                }
                """.trimIndent()
        }
    }

    @Test
    fun `typed registerTool passes a returned ToolResult through untouched`() {
        TachyonServer(port = 0).use { server ->
            server.registerTool<GreetArgs, GreetReply>("greet") { input ->
                success(GreetReply("${input.greeting}, ${input.name}!"), text = "greeting response")
                    .withMeta("cached", false)
            }

            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World","greeting":"Hi"}}}
                    """.trimIndent(),
                )

            response.statusCode() shouldEqual 200
            response.body() shouldEqualJson
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "greeting response"
                      }
                    ],
                    "structuredContent": {
                      "message": "Hi, World!"
                    },
                    "_meta": {
                      "cached": false
                    }
                  }
                }
                """.trimIndent()
        }
    }

    @Test
    fun `typed registerTool rejects a handler value that is neither Out nor ToolResult`() {
        TachyonServer(port = 0).use { server ->
            @Suppress("USELESS_CAST")
            server.registerTool<GreetArgs, GreetReply>("greet") { "not a GreetReply" as Any }

            createTestClient(server.port()).use { client ->
                client.initialize()
                val response =
                    client.sendRpc(
                        // language=json
                        """
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World","greeting":"Hi"}}}
                        """.trimIndent(),
                    )

                response.statusCode() shouldEqual 200
                response.body() shouldContain "error"
            }
        }
    }

    @Test
    fun `typedTool returns a plain Out value, wrapped into structuredContent`() {
        TachyonServer(port = 0) {
            typedTool<GreetArgs, GreetReply>(name = "greet") { input ->
                GreetReply("${input.greeting}, ${input.name}!")
            }
        }.use { server ->
            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World","greeting":"Hi"}}}
                    """.trimIndent(),
                )

            response.statusCode() shouldEqual 200
            response.body() shouldEqualJson
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "{\"message\":\"Hi, World!\"}"
                      }
                    ],
                    "structuredContent": {
                      "message": "Hi, World!"
                    }
                  }
                }
                """.trimIndent()
        }
    }

    @Test
    fun `typedTool passes a returned ToolResult through untouched`() {
        TachyonServer(port = 0) {
            typedTool<GreetArgs, GreetReply>(name = "greet") { input ->
                success(GreetReply("${input.greeting}, ${input.name}!"), text = "greeting response")
                    .withMeta("cached", false)
            }
        }.use { server ->
            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"greet","arguments":{"name":"World","greeting":"Hi"}}}
                    """.trimIndent(),
                )

            response.statusCode() shouldEqual 200
            response.body() shouldEqualJson
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "greeting response"
                      }
                    ],
                    "structuredContent": {
                      "message": "Hi, World!"
                    },
                    "_meta": {
                      "cached": false
                    }
                  }
                }
                """.trimIndent()
        }
    }

    @Test
    fun `Schema rejects unknown property`() {
        // The strict schema sets additionalProperties=false, so an unrecognized argument is
        // rejected by JSON-Schema validation before the tool handler (and its serde) ever runs —
        // a different, earlier failure than kotlinx.serialization's own unknown-key rejection
        // (see KotlinE2eTest's `decode with strict serde rejects unknown key as error`).
        TachyonServer(port = 0) {
            typedTool<GreetArgs, GreetReply>(
                name = "strict-greet",
                description = "Strict typed greet tool",
                schemaGenerator = ktSchemaGenerator(JsonSchemaConfig.Strict),
            ) {
                val input = request.arguments<GreetArgs>()

                structured(
                    GreetReply("${input.greeting}, ${input.name}!"),
                    "greeting response",
                )
            }
        }.use { server ->

            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"strict-greet","arguments":{"name":"World","greeting":"Hi","unknownKey":"extra"}}}
                    """.trimIndent(),
                )

            response.statusCode() shouldEqual 200
            response.body() shouldEqualJson
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "error": {
                    "code": -32602,
                    "message": "property 'unknownKey' is not defined in the schema and the schema does not allow additional properties"
                  }
                }
                """.trimIndent()
        }
    }

    @Test
    fun `typedTool allows omitting a defaulted argument out of the box`() {
        TachyonServer(port = 0) {
            // KxSerializationSerde honors kotlinx.serialization defaults on decode; the default
            // Jackson serde does not (no jackson-module-kotlin registered), which is an unrelated,
            // pre-existing gap independent of schemaGenerator.
            json {
                serde = KxSerializationSerde.Default
            }
            typedTool<GreetArgs, GreetReply>(
                name = "lenient-greet",
                description = "Typed greet tool whose defaulted argument may be omitted",
            ) {
                val input = request.arguments<GreetArgs>()

                structured(
                    GreetReply("${input.greeting}, ${input.name}!"),
                    "greeting response",
                )
            }
        }.use { server ->

            val client = createTestClient(server.port())
            client.initialize()
            val response =
                client.post(
                    // language=json
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"lenient-greet","arguments":{"name":"World"}}}
                    """.trimIndent(),
                )

            response.statusCode() shouldEqual 200
            response.body() shouldEqualJson
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "greeting response"
                      }
                    ],
                    "structuredContent": {
                      "message": "Hello, World!"
                    }
                  }
                }
                """.trimIndent()
        }
    }
}
