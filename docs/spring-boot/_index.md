---
title: "Spring Boot"
overview_title: "Get started"
weight: 12
sidebar_order: 12
toc: true
description: |-
  Expose Spring beans as MCP tools with Tachyon's Spring Boot starter. Build a greeting server and call it with MCP Inspector or curl.
---

Expose a Spring bean as an MCP tool with `@McpTool`. The starter discovers annotated beans,
builds the MCP server, and starts and stops it with your application context.

This guide creates a greeting tool at `http://127.0.0.1:8080/mcp` using Java 21+ and
Spring Boot 4.1.1. You need:

- JDK 21+ and Maven 3.9+.
- Node.js 22.19+ and npm for MCP Inspector, or `curl` for the terminal alternative.

Maven resolves Tachyon from Maven Central; you don't need a Tachyon checkout.

## 1. Add the starter

Create an empty `greeting-server` directory with this `pom.xml`:

<details>
<summary>Show pom.xml</summary>

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>
    <groupId>example</groupId>
    <artifactId>greeting-server</artifactId>
    <version>1.0-SNAPSHOT</version>
    <properties>
        <java.version>21</java.version>
    </properties>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.tachyonmcp</groupId>
                <artifactId>tachyon-bom</artifactId>
                <version>1.0.0-beta.30</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.tachyonmcp</groupId>
            <artifactId>tachyon-spring-boot-starter</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

</details>

For an existing Boot application, import `tachyon-bom` and add `tachyon-spring-boot-starter`.
Compile with `-parameters` so tool arguments retain their Java parameter names. Boot's parent
POM enables this; without that parent, set `maven.compiler.parameters` to `true`.

## 2. Create a tool

Create `src/main/java/example/GreetingApplication.java`:

```java
package example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GreetingApplication {
    public static void main(String[] args) {
        SpringApplication.run(GreetingApplication.class, args);
    }
}
```

Create `src/main/java/example/GreetingService.java`:

```java
package example;

import dev.tachyonmcp.api.annotations.McpTool;
import org.springframework.stereotype.Component;

@Component
public class GreetingService {
    @McpTool(description = "Say hello to someone")
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
```

Keep the service in the application package or a subpackage so Spring discovers it. Tachyon
uses the method name as the tool name and derives its input schema from the parameters.
The returned string becomes text content.

## 3. Configure the MCP port

Tachyon starts its own Netty HTTP server. **`tachyon.port` controls MCP; `server.port` controls
Spring's web server.** This example has no Spring web starter, so only the MCP listener starts.

Create `src/main/resources/application.yaml`:

```yaml
tachyon:
  name: greeting-server
  version: "1.0.0"
  host: 127.0.0.1
  port: 8080
```

If you add Tachyon to an existing Spring MVC or WebFlux application, choose different ports.
For example, keep `tachyon.port: 8080` and set `server.port: 8081`. MCP calls then go to
`http://127.0.0.1:8080/mcp`; Spring HTTP endpoints use port `8081`. Setting `server.port`
alone does not change the MCP port.

## 4. Run and call the tool

From `greeting-server`, run:

```bash
mvn -q spring-boot:run
```

Leave this terminal running. Stop it with **Ctrl+C** when you finish; Spring closes the MCP server.

Use [MCP Inspector](https://github.com/modelcontextprotocol/inspector) to explore the server's tools,
enter arguments, and inspect results in your browser. This is the recommended path for manual testing.

<details open>
<summary>Test with MCP Inspector (recommended)</summary>

With your server still running, open a second terminal and launch MCP Inspector:

```bash
npx -y @modelcontextprotocol/inspector@2.7.0 \
  --server-url http://127.0.0.1:8080/mcp \
  --transport http \
  --protocol-era modern
```

This selects Streamable HTTP and the modern MCP protocol used by the curl example below.
Open the browser URL printed by MCP Inspector, including any authentication token in that URL.

1. Connect to the server at `http://127.0.0.1:8080/mcp` using its connection control.
2. Open **Tools** and select **greet**.
3. Enter `Ada` in the **name** field and click **Execute Tool**.
4. Check that the result contains the text **Hello, Ada!**.

Change `Ada` to your name and execute the tool again. The greeting should change with the argument.
See the [MCP Inspector guide](https://modelcontextprotocol.io/docs/2026-07-28/tools/inspector)
for more ways to inspect tools, resources, and prompts.

</details>

<details>
<summary>Call the tool from the MCP Inspector CLI</summary>

For a terminal call without composing JSON-RPC headers, use MCP Inspector's CLI:

```bash
npx -y @modelcontextprotocol/inspector@2.7.0 --cli \
  --server-url http://127.0.0.1:8080/mcp \
  --transport http --protocol-era modern \
  --connect-timeout 10000 \
  --method tools/call --tool-name greet \
  --tool-args-json '{"name":"Ada"}' --format json
```

The output's `result.content` contains `{"type":"text","text":"Hello, Ada!"}`.
`--tool-args-json` preserves argument types, and `--format json` makes the output suitable for scripts.
For automated checks, follow MCP Inspector's
[CLI smoke-testing guide](https://github.com/modelcontextprotocol/inspector/blob/main/docs/cli-smoke-testing.md).

</details>

<details>
<summary>Test with curl</summary>

Open a second terminal. This request uses MCP **2026-07-28**: each request supplies its protocol
and client metadata, so there is no initialization handshake or session ID to copy. The method
and tool-name headers match the JSON body.

```bash
curl --fail-with-body --silent --show-error http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: greet' \
  --data-binary '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "greet",
      "arguments": {"name": "Ada"},
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientInfo": {"name": "curl", "version": "1.0"},
        "io.modelcontextprotocol/clientCapabilities": {}
      }
    }
  }'
```

Expected response (formatted):

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [{"type": "text", "text": "Hello, Ada!"}],
    "resultType": "complete"
  }
}
```

Change `"Ada"` to your name and call again. The greeting changes with the argument. Send
`"arguments": {}` or `"arguments": {"name": 42}` to see a JSON-RPC `-32602` input-validation error.
MCP 2026-07-28 returns this error with HTTP `400`, so `--fail-with-body` prints the error body and curl exits with code `22`.

> [!IMPORTANT]
> HTTP `200` alone does not mean a tool call succeeded. A tool that returns `ToolResult.error(...)`
> yields `isError: true` in the result, and a handler that throws yields a JSON-RPC `-32603` error,
> both with HTTP `200`. Inspect the JSON-RPC `error` field and, for tool results, `isError`.

</details>

## Next steps

- [Configuration](reference.md#configure-the-server) — properties, sessions, and builder customizers.
- [Actuator](reference.md#add-actuator-health-and-metrics) — serve health and metrics on Spring's HTTP port.
- [Deployment](../running/deployment.md) — configure bind addresses and public hostnames.
