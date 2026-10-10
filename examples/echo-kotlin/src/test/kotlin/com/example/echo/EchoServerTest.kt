// Copyright (c) 2026 Konstantin Pavlov and contributors.

package com.example.echo

import dev.tachyonmcp.core.server.TachyonServer
import dev.tachyonmcp.testkit.McpClient
import dev.tachyonmcp.testkit.McpTestClients
import io.kotest.assertions.json.shouldEqualJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EchoServerTest {
    private lateinit var server: TachyonServer

    private lateinit var client: McpClient

    @BeforeAll
    fun beforeAll() {
        server = assembleServer(0)
        server.start()
        client = McpTestClients.forVersion(server.port(), "2025-11-25")
        client.initialize()
    }

    @AfterAll
    fun afterAll() {
        client.close()
        server.close()
    }

    @Test
    fun `should list tools`() {
        val response =
            client.post(
                """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """.trimIndent(),
            )

        val json = response.body()
        json shouldEqualJson
            $$"""
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
                {
                  "name":"echo",
                  "description":"Echo message",
                  "inputSchema":{
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "$id": "EchoRequest",
                    "type":"object",
                    "properties": {
                      "message":{"type":"string","description":"Message to echo"}
                    },
                    "required":["message"],
                    "additionalProperties": false
                  } ,
                   "outputSchema": {
                      "$schema": "https://json-schema.org/draft/2020-12/schema",
                      "$id": "EchoResponse",
                      "type": "object",
                      "properties": {
                        "reply": {
                          "type": "string",
                          "description": "Response message"
                        }
                      },
                      "additionalProperties": false,
                      "required": [
                        "reply"
                      ]
                    }
                  },
                {
                  "name": "reverse-echo",
                  "description": "Echo reverse message",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "message": {
                        "type": "string",
                        "description": "Message to echo"
                      }
                    },
                    "required": [
                      "message"
                    ]
                  }
                }
                          ]}}
            """.trimIndent()
    }

    @Test
    fun `echo tool`() {
        val response =
            client.post(
                """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"echo","arguments":{"message":"Hello, MCP!"}}}
                """.trimIndent(),
            )

        val json = response.body()
        json shouldEqualJson
            """
            {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "{\"reply\":\"Hello, MCP!\"}"
                      }
                    ],
                    "structuredContent": {
                      "reply": "Hello, MCP!"
                    }
                  }
                }
            """.trimIndent()
    }

    @Test
    fun `reverse echo tool`() {
        val response =
            client.post(
                """
                {
                    "jsonrpc":"2.0",
                    "id":1,
                    "method":"tools/call",
                    "params":{"name":"reverse-echo","arguments":{"message":"stressed"}}
                }
                """.trimIndent(),
            )

        val json = response.body()
        json shouldEqualJson
            """
            {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "desserts"
                      }
                    ]
                  }
                }
            """.trimIndent()
    }

    @Test
    fun `should return error when message missing`() {
        val response =
            client.post(
                """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"tools/call",
                  "params":{"name":"echo","arguments":{}}}
                """.trimIndent(),
            )

        response.body() shouldEqualJson
            """
            {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "error": {
                    "code": -32602,
                    "message": "required property 'message' not found"
                  }
                }
            """.trimIndent()
    }

    @Test
    fun `should respond to initialize`() {
        val response =
            client.post(
                null,
                """
                {"jsonrpc":"2.0",
                      "id":1,
                      "method":"initialize",
                      "params":{"protocolVersion":"2025-11-25","capabilities":{},
                               "clientInfo":{"name":"test","version":"1.0"}}}
                """.trimIndent(),
            )

        response.body() shouldEqualJson
            """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
            "protocolVersion": "2025-11-25",
                    "capabilities": {
                      "tools": {}
                    },
                    "serverInfo": {
                      "version": "1.0.1",
                      "description": "Echo MCP server built with Tachyon Kotlin DSL",
                      "name": "echo-server",
                      "title": "Echo Server"
                    }
                  }
                }
            """.trimIndent()
    }
}
