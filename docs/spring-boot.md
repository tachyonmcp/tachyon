---
title: "Spring Boot starter"
weight: 25
sidebar_order: 25
toc: true
description: |-
  Expose Spring beans as MCP tools with Tachyon's Spring Boot starter. Run your first tool, configure the server, and add Actuator health and metrics.
---

Expose a Spring bean as an MCP tool with `@McpTool`. The starter discovers annotated beans,
builds the MCP server, and starts and stops it with your application context.

This guide creates a greeting tool at `http://127.0.0.1:8080/mcp` using Java 21+ and
Spring Boot 4.1.1.

> [!NOTE]
> This guide targets **Tachyon 1.0.0-SNAPSHOT**, matching the Spring Boot example in this
> repository. Install that snapshot locally before building the application below. The starter
> and native annotations are experimental APIs.

## 1. Add the starter

From a Tachyon repository checkout, install the starter and its dependencies:

```bash
./mvnw -q install -pl integrations/tachyon-spring-boot-starter -am -DskipTests
```

Create a separate `greeting-server` directory with this `pom.xml`:

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
                <version>1.0.0-SNAPSHOT</version>
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

Create `src/main/resources/application.yaml`:

```yaml
tachyon:
  name: greeting-server
  version: "1.0.0"
  host: 127.0.0.1
  port: 8080
```

## 3. Run and call the tool

From `greeting-server`:

```bash
mvn -q spring-boot:run
```

In another terminal, send the [quickstart's greeting request](quickstart.md#3-test-with-curl).
It uses the same URL, tool name, and arguments. The response includes
`{"type":"text","text":"Hello, Ada!"}` in `result.content`.

For an MCP client, choose **Streamable HTTP** and connect to `http://127.0.0.1:8080/mcp`.
Stop the application with **Ctrl+C**; Spring closes the MCP server.

## Configure the server

| Property | Default | Purpose |
|---|---|---|
| `tachyon.enabled` | `true` | Enable the starter's auto-configuration. Set `false` to disable it. |
| `tachyon.name` | Tachyon's server name | Name reported to MCP clients. |
| `tachyon.version` | Tachyon's server version | Application version reported to MCP clients; independent of the dependency version. |
| `tachyon.host` | `127.0.0.1` | MCP bind address. |
| `tachyon.port` | `8080` | MCP port. Use `0` for an available port in tests. |

Use Boot's usual overrides, such as `TACHYON_PORT=9090` or `--tachyon.port=9090`.
For an ephemeral port, inject `TachyonServer` and read `server.port()` after startup.

### Running alongside Spring MVC or WebFlux

Tachyon owns a separate Netty HTTP server. `tachyon.port` controls MCP; `server.port` controls
Spring's web server. The minimal application above needs no MVC or WebFlux dependency.

If your application also serves REST endpoints or Actuator over HTTP, assign different ports:

```yaml
tachyon:
  port: 8080
server:
  port: 8081
```

MCP remains at `http://127.0.0.1:8080/mcp`. Spring HTTP endpoints use port `8081`.

### Customize the builder

For settings beyond the five `tachyon.*` properties, declare a `TachyonServerCustomizer` bean.
For example, this configuration enables sessions:

```java
package example;

import dev.tachyonmcp.spring.boot.TachyonServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpConfiguration {
    @Bean
    TachyonServerCustomizer mcpSessions() {
        return builder -> builder.session(session -> session.enabled(true));
    }
}
```

Customizers run after properties and `ServerExtension` beans have been applied, so they can
override property values. Multiple customizers follow Spring ordering, such as `@Order`.
Use them for network guards, JSON codecs, observability, or programmatic feature registration.

See [configuration](running/configuration.md) for builder options and
[deployment](running/deployment.md) for bind addresses and accepted public hostnames.

## How bean discovery works

The starter builds `TachyonServer`, registers initialized singleton beans, then starts the
transport. Annotated services can constructor-inject the server to send notifications or
register features at runtime.

| Bean | Starter behavior |
|---|---|
| Bean with `@McpTool` methods | Registers tools. |
| Bean with `@McpResource` methods | Registers resources and resource templates. |
| Bean with `@McpPrompt` methods | Registers prompts. |
| Bean with `@McpCompletion` methods | Registers argument completions, including completion-only beans. |
| `ServerExtension` | Adds the extension before customizers run. |
| `TachyonServerCustomizer` | Adjusts the builder before construction. |

These are Tachyon annotations from `dev.tachyonmcp.api.annotations`. See
[annotations](annotations.md) for parameter binding, structured results, and third-party adapters.
Automatic bean discovery uses the native Tachyon annotations.

Spring AOP advice stays active when MCP calls your methods. With a JDK proxy, expose every
annotated method through a proxy interface; annotations and parameter names are read from the
target class. Use class proxies for annotated methods outside those interfaces.

Unrelated lazy beans are not instantiated for scanning. A lazy annotated bean must expose its
annotations on a type Spring can determine before creating it. For example, give its `@Bean`
factory method the concrete service return type instead of `Object`.

### Provide your own server

Defining a `TachyonServer` bean disables both automatic server construction and annotated-bean
registration. Configure and register its features yourself; starter customizers no longer apply.
The starter still starts and closes that server unless you also supply a
`TachyonServerLifecycle` bean.

## Add Actuator health and metrics

Add `org.springframework.boot:spring-boot-starter-actuator` to your dependencies. To serve
Actuator over HTTP, also include a Boot web starter, such as `spring-boot-starter-webflux`,
and give Spring its own port. For local development:

```yaml
tachyon:
  host: 127.0.0.1
  port: 8080
server:
  address: 127.0.0.1
  port: 8081
management:
  endpoints:
    web:
      exposure:
        include: health,metrics
  endpoint:
    health:
      show-details: always
```

Call the greeting tool, then inspect the endpoints:

```bash
curl http://127.0.0.1:8081/actuator/health
curl http://127.0.0.1:8081/actuator/metrics/mcp.server.operations
```

| Signal | Meaning |
|---|---|
| `tachyon` health component | `UP` with bound `host` and `port` while the starter lifecycle runs; `DOWN` otherwise. |
| `mcp.server.operations` | Operation timer tagged with `mcp.method.name` and `outcome`; created when operations complete. |
| `mcp.server.tools` | Registered tool count. |
| `mcp.server.prompts` | Registered prompt count. |
| `mcp.server.resources` | Registered resource and resource-template count. |

Disable the health indicator with `management.health.tachyon.enabled=false`.
Metrics require Micrometer and a `MeterRegistry` bean. The timer attaches through a customizer
and applies to starter-built servers. Feature gauges also work with a user-provided server.

For traces, add Tachyon's OpenTelemetry listener through a customizer; see
[observability](running/observability.md) and the
[Spring Boot weather example](https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp-spring-boot).

## Troubleshooting

| Symptom | Check |
|---|---|
| Address already in use | Assign different values to `tachyon.port` and `server.port`, or stop the process using that port. |
| Tool missing | Ensure the service is a Spring singleton with native Tachyon annotations and lies within component scanning. A custom `TachyonServer` disables discovery. |
| Argument names are wrong | Compile with `-parameters`; in Maven, set `maven.compiler.parameters=true` if you do not use Boot's parent. |
| Registration fails for a proxied method | Expose the method on the JDK proxy's interface or use a class proxy. |
| Actuator URL unavailable | Add a Boot web starter and use `server.port`. MCP's Netty listener does not serve Actuator. |
| Operation timer missing | Ensure a `MeterRegistry` exists, make an MCP request, and check that the starter builds the server. |

## Implementation reference

- [Properties and defaults](https://github.com/tachyonmcp/tachyon/blob/main/integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonProperties.java#L24)
- [Construction, ordering, backoff, and Actuator conditions](https://github.com/tachyonmcp/tachyon/blob/main/integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonAutoConfiguration.java#L43)
- [Bean discovery](https://github.com/tachyonmcp/tachyon/blob/main/integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonBeanRegistrar.java#L19)
- [Transport lifecycle](https://github.com/tachyonmcp/tachyon/blob/main/integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonServerLifecycle.java#L31)
