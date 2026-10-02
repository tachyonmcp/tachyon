package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.core.server.TachyonServer
import dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse
import dev.tachyonmcp.testkit.McpTestClients
import tools.jackson.databind.JsonNode
import java.net.http.HttpResponse

internal fun post(
    port: Int,
    method: String,
    params: String = "{}",
): HttpResponse<String> =
    McpTestClients.latest(port).use {
        it.post("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
    }

internal fun rpcResult(
    port: Int,
    method: String,
    params: String = "{}",
): JsonNode = assertThatResponse(post(port, method, params)).hasStatus(200).isSuccess().result()

internal fun TachyonServer.post(
    method: String,
    params: String = "{}",
): HttpResponse<String> = post(port(), method, params)

internal fun TachyonServer.rpcResult(
    method: String,
    params: String = "{}",
): JsonNode = rpcResult(port(), method, params)

internal fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()

internal fun JsonNode.text(): String = path("content").get(0).path("text").asString()

internal fun JsonNode.promptText(): String = path("messages").get(0).path("content").path("text").asString()

internal fun JsonNode.names(key: String): List<String> = path(key).items().map { it.path("name").asString() }
