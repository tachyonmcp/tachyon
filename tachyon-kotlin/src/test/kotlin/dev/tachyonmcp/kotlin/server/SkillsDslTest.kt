// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.server.extensions.ExtensionNegotiation
import dev.tachyonmcp.extensions.skills.FilesystemSkillsRegistry
import dev.tachyonmcp.kotlin.server.config.skills
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

internal class SkillsDslTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `skills adapter combines registries and maps cache settings over the wire`() {
        val first = registry("first")
        val second = registry("second")
        TachyonServer(port = 0) {
            session { enable() }
            skills(first) {
                registry(second)
                cacheTtl = 5.minutes
                cacheScope = "private"
                negotiation = ExtensionNegotiation.OPTIONAL
            }
        }.use { server ->
            McpProbe(server.port()).use { client ->
                client.initialize()
                val response = client.request(2, "skills/list")
                response.statusCode() shouldBe 200
                response.body() shouldEqualJson
                    """
                    {"jsonrpc":"2.0","id":2,"result":{
                      "skills":[${entry("first")},${entry("second")}],
                      "resultType":"complete","ttlMs":300000,"cacheScope":"private"
                    }}
                    """.trimIndent()
            }
        }
    }

    @Test
    fun `repeated skills calls append registries and replace settings with defaults`() {
        TachyonServer(port = 0) {
            session { enable() }
            skills(registry("first")) {
                cacheTtl = 5.minutes
                cacheScope = "private"
                negotiation = ExtensionNegotiation.REQUIRED
            }
            skills(registry("second"))
        }.use { server ->
            McpProbe(server.port()).use { client ->
                client.initialize()
                val response = client.request(2, "skills/list")
                response.statusCode() shouldBe 200
                response.body() shouldEqualJson
                    """
                    {"jsonrpc":"2.0","id":2,"result":{
                      "skills":[${entry("first")},${entry("second")}],
                      "resultType":"complete","ttlMs":0,"cacheScope":"public"
                    }}
                    """.trimIndent()
            }
        }
    }

    @Test
    fun `skills adapter enforces required negotiation`() {
        TachyonServer(port = 0) {
            session { enable() }
            skills(registry("first")) { negotiation = ExtensionNegotiation.REQUIRED }
        }.use { server ->
            McpProbe(server.port()).use { client ->
                client.initialize()
                client.request(2, "skills/list").body() shouldEqualJson
                    """
                    {"jsonrpc":"2.0","id":2,"error":{
                      "code":-32003,
                      "message":"Requires the 'io.modelcontextprotocol/skills' extension",
                      "data":{"requiredCapabilities":{"extensions":{"io.modelcontextprotocol/skills":{}}}}
                    }}
                    """.trimIndent()
            }
        }
    }

    @Test
    fun `skills adapter preserves Java cache validation`() {
        shouldThrow<IllegalArgumentException> {
            buildServer { skills(registry("first")) { cacheTtl = (-1).milliseconds } }
        }.message shouldBe "cacheTtl must be non-negative, was -1ms"
        shouldThrow<IllegalArgumentException> {
            buildServer { skills(registry("fractional")) { cacheTtl = (-1).nanoseconds } }
        }.message shouldBe "cacheTtl must be non-negative, was -1ns"
        shouldThrow<IllegalArgumentException> {
            buildServer { skills(registry("second")) { cacheScope = "invalid" } }
        }.message shouldBe "cacheScope must be 'public' or 'private', was 'invalid'"
    }

    private fun registry(name: String): FilesystemSkillsRegistry {
        val root = directory.resolve(name)
        root
            .resolve(name)
            .createDirectories()
            .resolve("SKILL.md")
            .writeText(manifest(name))
        return FilesystemSkillsRegistry(root)
    }

    private fun manifest(name: String): String =
        "---\nname: $name\ndescription: $name guide\n---\n# $name\n"

    private fun entry(name: String): String {
        val bytes = manifest(name).toByteArray()
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return """
            {"uri":"skill://$name/SKILL.md","frontmatter":{"name":"$name","description":"$name guide"},
             "resources":[{"uri":"skill://$name/SKILL.md","digest":"sha256:$digest","size":${bytes.size}}]}
            """.trimIndent()
    }
}
