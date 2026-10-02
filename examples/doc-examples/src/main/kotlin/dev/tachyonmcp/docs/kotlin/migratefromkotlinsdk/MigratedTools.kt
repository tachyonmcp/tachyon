package dev.tachyonmcp.docs.kotlin.migratefromkotlinsdk

import dev.tachyonmcp.api.server.domain.LoggingLevel
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.config.ToolScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig
import me.kpavlov.kt.schema.generator.json.ReflectionClassJsonSchemaGenerator
import kotlin.reflect.KClass

@Serializable
internal data class SearchResult(
    val hits: List<String>,
)

internal val json = Json

internal fun run(query: String): SearchResult = SearchResult(listOf("$query-1", "$query-2"))

internal val KClass<*>.jsonSchemaString: String
    get() =
        ReflectionClassJsonSchemaGenerator(
            json = Json { encodeDefaults = false },
            config = JsonSchemaConfig.Default,
        ).generateSchemaString(this)

internal fun registerSearch(server: TachyonServer) {
    // snips-start: migrate_register_tool
    server.registerTool(
        name = "search",
        description = "Search the index.",
        inputSchema = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
        outputSchema = SearchResult::class.jsonSchemaString,   // nullable
    ) { // this: ToolScope
        val query = request.arguments().stringValue("query")
        ToolResult.text(json.encodeToString(SearchResult.serializer(), run(query)))
    }
    // snips-end: migrate_register_tool
}

internal fun ToolScope.logEntry(
    loggerName: String,
    entry: Map<String, Any>,
) {
    // snips-start: migrate_logging
    // before: server.sendLoggingMessage(LoggingMessageNotification(...LoggingLevel.Info, data = McpJson...))
    ctx.notifications().log(LoggingLevel.INFO, loggerName, entry)
    // snips-end: migrate_logging
}

internal fun ToolScope.askUser(params: Map<String, Any>): ToolResult {
    // snips-start: migrate_client_request
    val response = ctx.sendRequest("elicitation/create", params).join()
    // snips-end: migrate_client_request
    return ToolResult.text(response.toString())
}
