[![Maven Central](https://img.shields.io/maven-central/v/dev.tachyonmcp/tachyon-core?logo=apachemaven&logoColor=C71A36)](https://central.sonatype.com/search?q=dev.tachyonmcp%2Ftachyon-*)
[![Java 21+](https://img.shields.io/badge/Java-21+-orange.svg?logo=jvm)](http://java.com)
[![Build](https://github.com/tachyonmcp/tachyon/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/tachyonmcp/tachyon/actions/workflows/build.yml)
[![MCPConformance: 2025-11-25+2026-07-28](https://img.shields.io/badge/MCP%20Conformance-2025.11.25+2026.07.28-grass?logo=modelcontextprotocol)](https://github.com/modelcontextprotocol/conformance)
[![codecov](https://codecov.io/github/tachyonmcp/tachyon/graph/badge.svg?token=WUMD9A8T2T)](https://codecov.io/github/tachyonmcp/tachyon)

[![Docs](https://img.shields.io/badge/Site-TachyonMCP.dev-magenta)](https://tachyonmcp.dev/docs/)
[![Api Docs](https://img.shields.io/badge/API-Docs-blue)](https://apidocs.tachyonmcp.dev/)
[![LLM Wiki](https://img.shields.io/badge/LLM_Wiki-blue?logo=github)](https://github.com/tachyonmcp/tachyon/wiki)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/tachyonmcp/tachyon)

<div style="align-content: center">
  <img
    src="docs/assets/social-banner.jpg"
    alt="Tachyon MCP banner"
    style="width: 100%; height: auto;border-radius: 10px"
  />
</div>

**[Tachyon MCP](https://tachyonmcp.dev)** is a Java 21+ server runtime for the
[Model Context Protocol](https://modelcontextprotocol.io). It implements MCP 2025-11-25 and
2026-07-28 over Streamable HTTP and passes the official conformance suites for both versions.

Build servers with synchronous Java handlers or a coroutine-first Kotlin DSL. Tachyon runs
blocking handlers on virtual threads, keeps them off the Netty event loop, and requires no
application framework.

## Why Tachyon?

- **Simple handlers** -- write blocking Java code or suspending Kotlin code without reactive
  pipelines or manual thread pools.
- **Stable application API** -- protocol-version mappers isolate handlers from wire-format changes.
- **Java annotations support** -- adapt existing mcp-java, LangChain4j, or Spring AI annotated services into Tachyon MCP servers.
- **Stateless by default** -- opt into sessions only when you need resumable SSE, event replay, and
  TTL cleanup.
- **Production transport** -- Netty backpressure, graceful shutdown, DNS-rebinding protection,
  CORS, and native transport auto-detection (`io_uring` → `epoll` → `kqueue` → NIO).
- **Built-in OpenTelemetry** -- register `tachyon-opentelemetry`'s listener for MCP
  semantic-convention spans and metrics, with an opt-in policy for payload capture.

## Quickstart

Import the [BOM](tachyon-bom) once to pin the version, then add the core dependency:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.tachyonmcp</groupId>
            <artifactId>tachyon-bom</artifactId>
            <version>${tachyon.version}</version> <!-- get latest version from Maven Central -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>dev.tachyonmcp</groupId>
        <artifactId>tachyon-core</artifactId>
    </dependency>
</dependencies>
```

Create and start a server:

```java
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;

public class WeatherMcpServer {
    public static void main(String... args) {
        var server = TachyonServer.builder()
            .name("weather-mcp")
            .withTools(tools -> tools.register(
                tool -> tool.name("get_forecast")
                    .description("Get weather forecast")
                    .inputSchema("""
                        {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
                        """),
                (context, request) -> ToolResult.text("☀️ 22°C")))
            .port(8080)
            .build();
        server.start();
    }
}
```

See the [quickstart](docs/quickstart.md) for Java and Kotlin examples plus a `curl` test.

## Implemented features

| Area | Support |
|---|---|
| **MCP surface** | Tools, resources and templates, prompts, completions, logging, sampling, form/URL elicitation, cancellation, subscriptions, pagination, and progress notifications |
| **Annotations** | Optional adapters for [mcp-java, LangChain4j, and Spring AI](docs/annotations.md), mapped onto Tachyon's standard registries |
| **Tasks** | Full `tasks/*` lifecycle, state validation, status notifications, retention, and `TasksExtension` for [SEP-1686](docs/features/tasks.md) |
| **Agent Skills** | `SkillsExtension` implements the [SEP-2640](docs/extensions/mcp-skills.md) draft: filesystem/classpath registries, `skill://` resources, `skills/list`, `skills/get`, and directory reads |
| **Extensions** | [SEP-2133](docs/extensions/) negotiation, custom JSON-RPC methods, capability advertisement, and extension-gated features |
| **Runtime** | Stateless or resumable sessions, `Last-Event-ID` replay, pluggable stores, virtual-thread handlers, request timeouts, and graceful draining |
| **Observability** | OpenTelemetry spans and metrics via `tachyon-opentelemetry`, following the [MCP semantic conventions](https://github.com/open-telemetry/semantic-conventions-genai/tree/main/model/mcp), plus an opt-in payload capture policy -- see [docs](docs/running/observability.md) |
| **Java and Kotlin** | Independent sync/async Java contracts and a coroutine-first [Kotlin DSL](docs/kotlin/) |
| **Testing** | Official conformance suites plus a [testkit](docs/testkit.md) for dynamic servers and fluent JSON-RPC assertions |

## Documentation and examples

- [Configuration](docs/running/configuration.md) -- network, native I/O, sessions, CORS, and runtime limits
- [Observability](docs/running/observability.md) -- observation listeners, payload capture policy, and the bundled OpenTelemetry integration
- [Tools](docs/features/tools.md), [resources](docs/features/resources.md), and [tasks](docs/features/tasks.md) -- feature APIs and examples
- [Annotations](docs/annotations.md) -- mcp-java, LangChain4j, and Spring AI providers
- [Extensions](docs/extensions/) and [MCP Skills](docs/extensions/mcp-skills.md) -- custom protocol methods and SEP support
- [Kotlin DSL](docs/kotlin/) -- builders, scopes, and suspending handlers
- [Examples](examples/README.md) -- runnable Java and Kotlin servers
- [FAQ](docs/faq.md) -- frameworks, concurrency, deployment, and compatibility

## Coding-agent skill

This repository also includes a skill that teaches coding agents how to build Tachyon servers. It
is separate from the server-side `SkillsExtension` described above.

```shell
npx skills add tachyonmcp/tachyon --skill tachyon-mcp
```

Its Java and Kotlin examples are compiled during the project build to keep them current.

## License

**Tachyon MCP** is available under the terms of the [Apache 2.0](LICENSE).
