package dev.tachyonmcp.docs.features.completions

import dev.tachyonmcp.api.server.features.completions.CompletionResult
import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder

internal class KotlinCityService {
    private val cities = listOf("London", "Paris", "Prague")

    fun search(prefix: String): List<String> = cities.filter { it.startsWith(prefix, ignoreCase = true) }
}

internal fun TachyonServerBuilder.completionHandlers(cityService: KotlinCityService) {
    // snips-start: completions_kotlin
    promptCompletion("review-code") {
        if (request.argumentName() != "concern") {
            CompletionResult.empty()
        } else {
            CompletionResult.of(
                listOf("clarity", "performance", "security")
                    .filter { it.startsWith(request.argumentValue(), ignoreCase = true) },
            )
        }
    }

    resourceCompletion("weather://current/{city}") {
        CompletionResult.of(cityService.search(request.argumentValue()))
    }
    // snips-end: completions_kotlin
}
