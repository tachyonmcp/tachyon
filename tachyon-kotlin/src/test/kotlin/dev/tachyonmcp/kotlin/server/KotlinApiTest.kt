// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.domain.Args
import dev.tachyonmcp.api.server.domain.AudioContent
import dev.tachyonmcp.api.server.domain.ImageContent
import dev.tachyonmcp.api.server.domain.InvalidArgumentException
import dev.tachyonmcp.api.server.domain.Role
import dev.tachyonmcp.api.server.domain.TextContent
import dev.tachyonmcp.api.server.features.prompts.PromptRequest
import dev.tachyonmcp.api.server.features.tools.ToolRequest
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.config.PromptScope
import dev.tachyonmcp.kotlin.server.config.ToolScope
import dev.tachyonmcp.kotlin.server.domain.RpcMethodRequest
import dev.tachyonmcp.kotlin.server.domain.arguments
import dev.tachyonmcp.kotlin.server.domain.arrayOrNull
import dev.tachyonmcp.kotlin.server.domain.boolean
import dev.tachyonmcp.kotlin.server.domain.booleanOrNull
import dev.tachyonmcp.kotlin.server.domain.decimalOrNull
import dev.tachyonmcp.kotlin.server.domain.decode
import dev.tachyonmcp.kotlin.server.domain.double
import dev.tachyonmcp.kotlin.server.domain.doubleOrNull
import dev.tachyonmcp.kotlin.server.domain.int
import dev.tachyonmcp.kotlin.server.domain.intOrNull
import dev.tachyonmcp.kotlin.server.domain.long
import dev.tachyonmcp.kotlin.server.domain.longOrNull
import dev.tachyonmcp.kotlin.server.domain.objectOrNull
import dev.tachyonmcp.kotlin.server.domain.string
import dev.tachyonmcp.kotlin.server.domain.stringOrNull
import dev.tachyonmcp.kotlin.server.domain.valuesAs
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.util.stream.Stream

internal class KotlinApiTest {
    // region: schema overloads

    @Test
    fun `every tool schema overload reaches the descriptor`() {
        val objectSchema = """{"type":"object"}"""

        buildServer {
            tool(
                "java-schema",
                inputSchema = JsonSchema.objectSchema(),
                outputSchema = JsonSchema.objectSchema(),
            ) { text("ok") }
            @Suppress("DEPRECATION")
            tool(
                "string-schema",
                inputSchema = objectSchema,
                outputSchema = objectSchema,
            ) { text("ok") }
            tool(
                "json-object-schema",
                inputSchema = buildJsonObject { put("type", "object") },
                outputSchema = buildJsonObject { put("type", "object") },
            ) { text("ok") }
        }.use { server ->
            listOf("java-schema", "string-schema", "json-object-schema").forEach { name ->
                withClue(name) {
                    server.tools().find(name) shouldBePresent {
                        checkNotNull(inputSchema()).json() shouldEqualJson objectSchema
                        checkNotNull(outputSchema()).json() shouldEqualJson objectSchema
                    }
                }
            }
        }
    }

    // endregion

    // region: Args accessors

    @Test
    fun `orNull and default accessors return the value when the key is present`() {
        val args =
            Args.of(
                mapOf(
                    "str" to "v",
                    "int" to 42,
                    "long" to Long.MAX_VALUE,
                    "bool" to true,
                    "double" to 3.14,
                    "decimal" to BigDecimal("1.25"),
                    "obj" to mapOf("k" to "v"),
                    "arr" to listOf("x", "y"),
                ),
                null,
            )

        assertSoftly {
            args.stringOrNull("str") shouldBe "v"
            args.intOrNull("int") shouldBe 42
            args.longOrNull("long") shouldBe Long.MAX_VALUE
            args.booleanOrNull("bool") shouldBe true
            args.doubleOrNull("double") shouldBe 3.14
            args.decimalOrNull("decimal") shouldBe BigDecimal("1.25")
            args.objectOrNull("obj")?.stringValue("k") shouldBe "v"
            args.arrayOrNull("arr")?.valuesAs<String>() shouldBe listOf("x", "y")

            args.string("str", "def") shouldBe "v"
            args.boolean("bool", false) shouldBe true
            args.int("int", 0) shouldBe 42
            args.long("long", 0L) shouldBe Long.MAX_VALUE
            args.double("double", 0.0) shouldBe 3.14
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("missingOrJsonNullArguments")
    fun `orNull and default accessors and decode fold missing and JSON-null arguments the same way`(
        scenario: String,
        argumentsJson: String,
    ) {
        TachyonServer(port = 0) {
            name("null-accessors-test")
            session { enabled = true }
            tool("null-accessors") {
                val args = request.arguments()
                val fields =
                    listOf(
                        "stringOrNull" to args.stringOrNull("str"),
                        "intOrNull" to args.intOrNull("int"),
                        "longOrNull" to args.longOrNull("long"),
                        "booleanOrNull" to args.booleanOrNull("bool"),
                        "doubleOrNull" to args.doubleOrNull("double"),
                        "decimalOrNull" to args.decimalOrNull("decimal"),
                        "objectOrNull" to args.objectOrNull("obj"),
                        "arrayOrNull" to args.arrayOrNull("arr"),
                        "stringDefault" to args.string("str", "default"),
                        "booleanDefault" to args.boolean("bool", true),
                        "intDefault" to args.int("int", 7),
                        "longDefault" to args.long("long", 42L),
                        "doubleDefault" to args.double("double", 1.5),
                        "decodeStr" to args.decode<NullableArgs>().str,
                    )
                ToolResult.raw(
                    ObjectMapper().writeValueAsString(fields.toMap()),
                    "accessors resolved",
                )
            }
        }.use { server ->
            McpProbe(server.port()).use { probe ->
                probe.initialize()
                val response = probe.callTool("null-accessors", argumentsJson)
                response.statusCode() shouldBe 200
                response.body() shouldEqualJson
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": 2,
                      "result": {
                        "content": [{"type": "text", "text": "accessors resolved"}],
                        "structuredContent": {
                          "stringOrNull": null,
                          "intOrNull": null,
                          "longOrNull": null,
                          "booleanOrNull": null,
                          "doubleOrNull": null,
                          "decimalOrNull": null,
                          "objectOrNull": null,
                          "arrayOrNull": null,
                          "stringDefault": "default",
                          "booleanDefault": true,
                          "intDefault": 7,
                          "longDefault": 42,
                          "doubleDefault": 1.5,
                          "decodeStr": null
                        }
                      }
                    }
                    """.trimIndent()
            }
        }
    }

    // endregion

    companion object {
        @JvmStatic
        fun missingOrJsonNullArguments(): Stream<Arguments> =
            Stream.of(
                Arguments.of("missing arguments", "{}"),
                Arguments.of(
                    "JSON-null arguments",
                    // language=json
                    """
                    {"str":null,"int":null,"long":null,"bool":null,"double":null,
                     "decimal":null,"obj":null,"arr":null}
                    """.trimIndent(),
                ),
            )
    }

    @Serializable
    data class NullableArgs(
        val str: String? = null,
    )

    @Serializable
    data class GreetingArgs(
        val name: String,
        val age: Int = 0,
    )

    // region: decode — typed via configured serde

    @Test
    fun `decode reads typed arguments through every entry point`() {
        val args = Args.of(mapOf("name" to "Alice", "age" to 30), KxSerializationSerde.Default)
        val request =
            ToolRequest
                .builder()
                .name("greet")
                .arguments(args)
                .build()

        assertSoftly {
            args.decode(GreetingArgs::class.java) shouldBe GreetingArgs("Alice", 30)
            args.decode<GreetingArgs>() shouldBe GreetingArgs("Alice", 30)
            request.arguments<GreetingArgs>() shouldBe GreetingArgs("Alice", 30)
        }
    }

    @Test
    fun `decode ignores unknown keys and applies declared defaults`() {
        val args =
            Args.of(
                mapOf("name" to "Eve", "unexpected" to "extra"),
                KxSerializationSerde.Default,
            )

        args.decode(GreetingArgs::class.java) shouldBe GreetingArgs("Eve", 0)
    }

    @Test
    fun `decode uses configured serde not hardcoded json`() {
        val raw =
            mapOf(
                "name" to "Eve",
                "age" to 25,
                "unknown" to "extra",
            )
        // Default serde ignores unknown keys; a strict serde rejects them — proving the
        // configured deserializer is used, mapped to InvalidArgumentException (invalid params).
        val strictSerde = KxSerializationSerde(Json { ignoreUnknownKeys = false })
        val args = Args.of(raw, strictSerde)
        shouldThrow<InvalidArgumentException> {
            args.decode(GreetingArgs::class.java)
        }.argName() shouldBe "arguments"
    }

    @Test
    fun `decode throws when no deserializer configured`() {
        val args = Args.of(mapOf("name" to "Bob"), null)
        shouldThrow<IllegalStateException> {
            args.decode(GreetingArgs::class.java)
        }.message shouldContain "PayloadDeserializer is not configured"
    }

    // endregion

    // region: ToolScope results

    @Test
    fun `success carries the structured value with an optional text block`() {
        val value = GreetingArgs("Charlie", 25)

        withToolScope {
            val plain = success(value)
            val annotated = success(value, "custom text")

            plain.shouldBeInstanceOf<ToolResult.Success>()
            annotated.shouldBeInstanceOf<ToolResult.Success>()
            assertSoftly {
                plain.structured().get() shouldBe value
                annotated.structured().get() shouldBe value
                (annotated.content().first() as TextContent).text() shouldBe "custom text"
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `content DSL collects base64 and raw binary blocks`() {
        withToolScope {
            val encoded =
                content {
                    text("Answer")
                    image("aGVsbG8=", "image/png")
                }
            val binary =
                content {
                    image(byteArrayOf(1, 2, 3), "image/png")
                    audio(byteArrayOf(4, 5, 6), "audio/wav")
                }

            encoded.shouldBeInstanceOf<ToolResult.Success>()
            binary.shouldBeInstanceOf<ToolResult.Success>()
            assertSoftly {
                encoded.content() shouldHaveSize 2
                (encoded.content()[0] as TextContent).text() shouldBe "Answer"
                (encoded.content()[1] as ImageContent).mimeType() shouldBe "image/png"
                binary.content() shouldHaveSize 2
                (binary.content()[0] as ImageContent).data().toList() shouldBe listOf<Byte>(1, 2, 3)
                (binary.content()[1] as AudioContent).data().toList() shouldBe listOf<Byte>(4, 5, 6)
            }
        }
    }

    @Test
    fun `text, raw and empty build single-purpose success results`() {
        withToolScope {
            val plain = text("hi").shouldBeInstanceOf<ToolResult.Success>()
            val preSerialized =
                raw("""{"k":"v"}""", "raw text").shouldBeInstanceOf<ToolResult.Success>()
            val nothing = empty().shouldBeInstanceOf<ToolResult.Success>()

            assertSoftly {
                (plain.content().single() as TextContent).text() shouldBe "hi"
                (preSerialized.content().single() as TextContent).text() shouldBe "raw text"
                nothing.structured().isPresent shouldBe false
                nothing.content() shouldHaveSize 0
            }
        }
    }

    @Test
    fun `fail builds an error result from a message or a content DSL`() {
        withToolScope {
            val single = fail("boom")
            val multi =
                fail {
                    text("Validation failed")
                    text("field 'email' is required")
                }

            single.shouldBeInstanceOf<ToolResult.Error>()
            multi.shouldBeInstanceOf<ToolResult.Error>()
            assertSoftly {
                (single.content().single() as TextContent).text() shouldBe "boom"
                multi.content() shouldHaveSize 2
                (multi.content()[0] as TextContent).text() shouldBe "Validation failed"
                (multi.content()[1] as TextContent).text() shouldBe "field 'email' is required"
            }
        }
    }

    @Test
    fun `inputRequired accepts a request map or vararg pairs`() {
        val ask = RpcMethodRequest("elicitation/create")

        withToolScope {
            val fromMap = inputRequired(mapOf("confirm" to ask), state = "opaque")
            val fromPairs = inputRequired("confirm" to ask)

            fromMap.shouldBeInstanceOf<ToolResult.InputRequired>()
            fromPairs.shouldBeInstanceOf<ToolResult.InputRequired>()
            assertSoftly {
                fromMap.inputRequests() shouldBe mapOf("confirm" to ask)
                fromMap.requestState() shouldBe "opaque"
                fromPairs.inputRequests() shouldBe mapOf("confirm" to ask)
                fromPairs.requestState() shouldBe null
            }
        }
    }

    @Test
    fun `ToolScope arguments delegates to request arguments`() {
        val args = Args.of(mapOf("k" to "v"), null)

        withToolScope(args) { arguments shouldBe args }
    }

    // endregion

    @Test
    @Suppress("DEPRECATION")
    fun `PromptScope content DSL builds one user message per block`() {
        withStatelessContext { ctx ->
            val request = PromptRequest(Args.empty(), null, null)
            val scope = PromptScope(ctx, request = request)
            val messages =
                scope.content {
                    text("Summarize this")
                    image("aGVsbG8=", "image/png")
                }
            assertSoftly {
                messages shouldHaveSize 2
                messages.forEach { it.role() shouldBe Role.USER }
                (messages[0].content() as TextContent).text() shouldBe "Summarize this"
                (messages[1].content() as ImageContent).mimeType() shouldBe "image/png"
            }
        }
    }

    private fun <T> withToolScope(
        arguments: Args = Args.of(null, null),
        block: ToolScope.() -> T,
    ): T =
        withStatelessContext { ctx ->
            val request =
                ToolRequest
                    .builder()
                    .name("t")
                    .arguments(arguments)
                    .build()
            ToolScope(ctx, request = request).block()
        }
}
