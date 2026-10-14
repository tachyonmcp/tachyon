// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.extensions.apps.AppMeta
import dev.tachyonmcp.extensions.apps.AppsExtension
import dev.tachyonmcp.extensions.apps.UiToolVisibility
import dev.tachyonmcp.kotlin.server.config.apps
import dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat
import dev.tachyonmcp.testkit.Mcp20251125Client
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

internal class AppsDslTest {
    @Test
    fun `apps adapter serves views and links tools over the wire`() {
        TachyonServer(port = 0) {
            session { enable() }
            apps {
                app(
                    uri = "ui://dice/roll",
                    name = "dice_roll",
                    title = "Dice",
                    meta = AppMeta.builder().prefersBorder(true).build(),
                ) { "<!DOCTYPE html><html><body>dice</body></html>" }
            }
            tool(
                name = "roll",
                meta = AppsExtension.toolMeta("ui://dice/roll", UiToolVisibility.MODEL),
            ) {
                text("4")
            }
        }.use { server ->
            Mcp20251125Client(server.port()).use { client ->
                client.initialize()
                assertThat(
                    client.sendRpc(
                        """{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"ui://dice/roll"}}""",
                    ),
                ).isSuccess().hasId(2).hasResult(
                    """
                    {"contents":[{
                      "uri":"ui://dice/roll","mimeType":"text/html;profile=mcp-app",
                      "text":"<!DOCTYPE html><html><body>dice</body></html>",
                      "_meta":{"ui":{"prefersBorder":true}}
                    }]}
                    """.trimIndent(),
                )
                assertThat(
                    client.sendRpc("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}"""),
                ).isSuccess().hasId(3).hasResult(
                    """
                    {"resources":[{
                      "uri":"ui://dice/roll","name":"dice_roll","title":"Dice",
                      "mimeType":"text/html;profile=mcp-app","_meta":{"ui":{"prefersBorder":true}}
                    }]}
                    """.trimIndent(),
                )
                assertThat(
                    client.sendRpc("""{"jsonrpc":"2.0","id":4,"method":"tools/list"}"""),
                ).isSuccess().hasId(4).hasResult(
                    """
                    {"tools":[{
                      "name":"roll","inputSchema":{"type":"object"},
                      "_meta":{"ui":{"resourceUri":"ui://dice/roll","visibility":["model"]}}
                    }]}
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `apps adapter preserves Java validation`() {
        shouldThrow<IllegalArgumentException> {
            buildServer {
                apps {
                    app(
                        uri = "https://example.com",
                        name = "x",
                    ) { "<html></html>" }
                }
            }
        }.message shouldContain "ui://"
    }
}
