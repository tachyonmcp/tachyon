// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.json

import dev.tachyonmcp.api.json.JsonDocument
import dev.tachyonmcp.api.json.JsonSchema
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

internal class KotlinxJsonFactoriesTest {
    @Test
    fun `KotlinxJsonElementFactory retains the same element instance for unwrap`() {
        val element = Json.parseToJsonElement("""{"type": "object"}""")

        val schema = KotlinxJsonElementFactory.INSTANCE.toJsonSchema(element).get()
        val document = KotlinxJsonElementFactory.INSTANCE.toJsonDocument(element)

        assertSoftly {
            schema.unwrap(JsonElement::class.java).get() shouldBeSameInstanceAs element
            document.unwrap(JsonElement::class.java).get() shouldBeSameInstanceAs element
            schema.json() shouldEqualJson element.toString()
        }
    }

    @Test
    fun `KotlinxJsonObjectFactory retains the same object instance for unwrap`() {
        val obj = Json.parseToJsonElement("""{"a": 1}""") as JsonObject

        val schema = KotlinxJsonObjectFactory.INSTANCE.toJsonSchema(obj).get()
        val document = KotlinxJsonObjectFactory.INSTANCE.toJsonDocument(obj)

        assertSoftly {
            schema.unwrap(JsonObject::class.java).get() shouldBeSameInstanceAs obj
            document.unwrap(JsonObject::class.java).get() shouldBeSameInstanceAs obj
            document.json() shouldEqualJson obj.toString()
        }
    }

    @Test
    fun `the generic from entry point dispatches on the declared source type`() {
        val obj = Json.parseToJsonElement("""{"type": "object"}""") as JsonObject
        val element: JsonElement = obj

        val objectSchema: JsonSchema = JsonSchema.from(obj, JsonObject::class.java)
        val objectDocument: JsonDocument = JsonDocument.from(obj, JsonObject::class.java)
        val elementSchema: JsonSchema = JsonSchema.from(element, JsonElement::class.java)
        val elementDocument: JsonDocument = JsonDocument.from(element, JsonElement::class.java)

        assertSoftly {
            objectSchema.unwrap(JsonObject::class.java).get() shouldBeSameInstanceAs obj
            objectDocument.unwrap(JsonObject::class.java).get() shouldBeSameInstanceAs obj
            elementSchema.unwrap(JsonElement::class.java).get() shouldBeSameInstanceAs element
            elementDocument.unwrap(JsonElement::class.java).get() shouldBeSameInstanceAs element
        }
    }
}
