// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.extensions.tasks.TasksExtension
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.InetSocketAddress

internal class TachyonServerBuilderTest {
    @ParameterizedTest
    @CsvSource("true,false", "false,true", "true,true")
    fun `address rejects explicit host or port`(
        setHost: Boolean,
        setPort: Boolean,
    ) {
        shouldThrow<IllegalArgumentException> {
            buildServer {
                network {
                    address = InetSocketAddress("127.0.0.1", 0)
                    if (setHost) host = "127.0.0.1"
                    if (setPort) port = 0
                }
            }.use { }
        }.message shouldBe "address is mutually exclusive with host and port"
    }

    @ParameterizedTest
    @CsvSource("true,false", "false,true", "true,true")
    fun `serde rejects explicit serializer or deserializer`(
        setSerializer: Boolean,
        setDeserializer: Boolean,
    ) {
        shouldThrow<IllegalArgumentException> {
            buildServer {
                json {
                    serde = KxSerializationSerde.Default
                    if (setSerializer) serializer = KxSerializationSerde.Default
                    if (setDeserializer) deserializer = KxSerializationSerde.Default
                }
            }.use { }
        }.message shouldBe "serde is mutually exclusive with serializer and deserializer"
    }

    @Test
    fun `withExtension returns the same builder instance, so it can be chained`() {
        val builder = TachyonServerBuilder()

        val result = builder.withExtension(TasksExtension::class.java) { }

        result shouldBeSameInstanceAs builder
    }

    @Test
    fun `withExtension configure defaults to a no-op`() {
        val builder = TachyonServerBuilder()

        val result = builder.withExtension(TasksExtension::class.java)

        result shouldBeSameInstanceAs builder
    }
}
