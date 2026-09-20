---
title: "Quickstart"
weight: 5
sidebar_order: 5
toc: true
description: |-
  Build a complete Java MCP server and call its greeting tool with curl.
---

Run a server with one `greet` tool at `http://127.0.0.1:8080/mcp`. Send a name and get a personal
greeting back. Choose Maven or Gradle below. For Kotlin, start with the
[Kotlin tools examples](kotlin/#tool-handlers).

## Prerequisites

- JDK 21; set `JAVA_HOME` to its installation directory.
- Maven 3.9+ or Gradle 8.14.3.
- `curl` to call the server.

> [!NOTE]
> The project files pin **Tachyon ${tachyon.version}**, available from Maven Central.
> No repository checkout or locally installed Tachyon artifacts are needed.

## 1. Add the dependency

Create an empty `greeting-server` directory. Expand **one** combination below and save its build files
into it, then [create the server](#2-create-a-server). Each configuration is complete.

The `tachyon-bom` pins the version of `tachyon-core`.

### Java + Maven

<details>
<summary>Show pom.xml</summary>

Create `pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>example</groupId>
    <artifactId>greeting-server</artifactId>
    <version>1.0-SNAPSHOT</version>
    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <maven.compiler.parameters>true</maven.compiler.parameters>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.tachyonmcp</groupId>
                <artifactId>tachyon-bom</artifactId>
                <version>${tachyon.version}</version>
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
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.14.1</version>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.6.3</version>
                <configuration>
                    <executable>java</executable>
                    <arguments>
                        <argument>-classpath</argument>
                        <classpath/>
                        <argument>MyMcpServer</argument>
                    </arguments>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

</details>

### Java + Gradle

<details>
<summary>Show settings.gradle.kts and build.gradle.kts</summary>

Create `settings.gradle.kts`:

```kotlin
rootProject.name = "greeting-server"
```

Create `build.gradle.kts`:

```kotlin
plugins {
    java
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("dev.tachyonmcp:tachyon-bom:1.0.0-beta.30"))
    implementation("dev.tachyonmcp:tachyon-core")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

application { mainClass = "MyMcpServer" }
```

</details>

## 2. Create a server

The server registers an annotated service, requires a string `name`, and closes on JVM shutdown.
Tachyon derives the tool's input schema from the method signature. The build configurations above
enable `-parameters` to preserve argument names.

Create `src/main/java/MyMcpServer.java`:

```java
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.core.server.TachyonServer;

public final class MyMcpServer {
    public static final class GreetingService {
        @McpTool(description = "Say hello to someone")
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    public static void main(String[] args) {
        final var server = TachyonServer.builder()
                .name("my-server")
                .version("1.0")
                .annotations(annotations -> annotations.register(new GreetingService()))
                .host("127.0.0.1")
                .port(8080)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }
}
```

### Run

From `greeting-server`, run the command for your build tool:

| Build tool | Build and run |
|---|---|
| Maven | `mvn -q compile exec:exec` |
| Gradle | `gradle --console=plain run` |

Leave this terminal running. The server listens at `http://127.0.0.1:8080/mcp`. Stop it with
**Ctrl+C** when you finish.

## 3. Test with curl

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

> [!IMPORTANT]
> HTTP success alone does not mean a tool call succeeded. Inspect the JSON-RPC `error` field and,
> for tool results, `isError`.

## Executable coverage

[DeclarativeFeaturesTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeFeaturesTest.java) verifies the same annotation registration,
named-string binding, greeting response, and missing-argument rejection over HTTP.
[DeclarativeResultsTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeResultsTest.java) also checks mistyped arguments and
explicit tool errors. These tests cover the handler behavior; the Markdown build files are not
extracted into the test suite.

## Next steps

- [Spring Boot starter](spring-boot.md) — expose Spring beans as MCP tools.

- [Tools](features/tools.md) — bind typed arguments, return structured output, and handle errors.
- [Annotations](annotations.md) — share registration and binding rules across feature services.
- [Testkit](testkit.md) — automate calls against a running server.
- [Resources](features/resources.md) and [prompts](features/prompts.md) — add data and reusable messages.
- [Deployment](running/deployment.md) — make the server reachable beyond your machine.
