# Examples

Standalone, runnable MCP servers built with Tachyon. Each example is its own Maven project with
the Maven wrapper build in, so you can run it without installing anything else — just a JDK 21+.

- [**echo-kotlin**](echo-kotlin) — Minimal server with `echo` and `reverse-echo` tools in Kotlin.
- [**weather-mcp**](weather-mcp) — Java. Full MCP surface: tools, resources, resource templates,
  prompts, completions and elicitation. Wired up with `tachyon-opentelemetry` and a verbose
  payload capture policy — see its README's Observability section.
- [**weather-mcp-kotlin**](weather-mcp-kotlin) — Kotlin port of `weather-mcp`, same observability setup.
- [**weather-mcp-spring-boot**](weather-mcp-spring-boot) — Java. Spring Boot starter discovers a
  bean with native `@McpTool`, `@McpResource`, `@McpPrompt`, and `@McpCompletion` methods.
  Actuator health/Micrometer meters and OpenTelemetry spans via Boot starters. Binds via
  `tachyon.host`/`tachyon.port` properties, not the variables below.
- [**langchain4j-mcp**](langchain4j-mcp) — Java. A plain LangChain4j `@Tool` method, with no
  Tachyon imports, scanned by `LangChain4jAnnotationProvider` into a running server.
- [**mcp-java**](mcp-java) — Java. A plain service using mcp-java `@Tool`, `@Resource`,
  `@ResourceTemplate`, and `@Prompt` annotations, scanned by `McpJavaAnnotationProvider`.
- [**mcp-skills**](mcp-skills) — Java. Serves a bundled Elvish-magic Agent Skill through the MCP
  skills extension.
- [**temporal**](temporal) — Java. MCP tasks backed by Temporal workflows through
  `tachyon-tasks-temporal`. Library plus tests; Docker is needed for the Testcontainers test.

Start with **echo-kotlin** to see the smallest viable server, then move to **weather-mcp** (or its
Kotlin twin) for a realistic feature-rich example backed by the Open-Meteo API.

Run a server from its own directory — each example's README has the exact build and run commands.

## Binding and access from Docker

Every example reads the same three environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `HOST` | `localhost` | Interface to bind to |
| `PORT` | `8080` | Port to listen on |
| `ALLOWED_HOST` | *(unset)* | Extra `Host` authority the DNS-rebinding guard accepts |

By default a server binds loopback and accepts only `localhost`/`127.0.0.1` in the `Host` header
(DNS-rebinding protection). A client inside a Docker container reaches your machine as
`host.docker.internal`, so it fails both checks. To let it in, bind a reachable interface **and**
whitelist the authority it sends:

```shell
export HOST=0.0.0.0
export ALLOWED_HOST=host.docker.internal:8080
```

⚠️ `HOST=0.0.0.0` publishes the port on every interface, not just loopback — anything that can
reach your machine can reach the server. Use a specific reachable address instead of `0.0.0.0`
when you can, and keep `ALLOWED_HOST` set so the `Host` check still filters requests.

Looking for the API and docs? See the main [README](../README.md).
