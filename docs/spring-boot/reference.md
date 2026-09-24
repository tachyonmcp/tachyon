---
title: "Spring Boot reference"
sidebar_title: "Reference"
weight: 20
sidebar_order: 20
toc: true
description: |-
  Configure Spring Boot integration: properties, bean discovery, customizers, Actuator, and native images.
---

Use this reference to configure an existing Tachyon Spring Boot application. For your first
server and greeting call, follow the [Spring Boot starter guide](./).

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

### Network transport

| Property | Default | Purpose |
|---|---|---|
| `tachyon.network.endpoint-path` | `/mcp` | HTTP path serving the MCP endpoints. Must start with `/`, no query, fragment or whitespace. Requests: a trailing `/` and the query string are ignored; any other path gets `404`. |
| `tachyon.network.reader-idle-timeout` | `60s` | Close connections with no inbound traffic for this long. |
| `tachyon.network.writer-idle-timeout` | `5m` | Close connections with no outbound traffic for this long. |
| `tachyon.network.heartbeat-interval` | `15s` | SSE heartbeat that keeps an upgraded stream alive. Keep it below proxy idle timeouts and, with sessions, below the session TTL; `0` disables it. |
| `tachyon.network.max-content-length` | `1MB` | Maximum HTTP request body size. Takes a `DataSize`, such as `512KB`. |
| `tachyon.network.max-pipelined-requests` | `16` | Pipelined HTTP/1.1 requests that may wait behind the one in flight on a connection; the next gets `429` and the connection closes. `0` disables pipelining. |
| `tachyon.network.max-pending-sse-bytes` | `64KB` | Encoded, unsent output one POST-SSE stream may buffer. `0` disables buffering: a tool waits until each event reaches the socket. Past it, a tool sending progress, logs or comments waits for the client. The final response is always accepted. Takes a `DataSize`. |
| `tachyon.network.allowed-origins` | none | Origins the DNS-rebinding guard admits and the CORS handler grants, each `http(s)://host[:port]` with no path. Unset grants any loopback origin, on any port. Set, loopback origins outside the list get no CORS grant. A malformed entry fails startup naming this property. |
| `tachyon.network.allowed-headers` | none | Request headers accepted by the CORS handler, beyond the built-in ones. |
| `tachyon.network.allowed-hosts` | none | `Host` authorities the DNS-rebinding guard accepts beyond loopback, each a host or `host:port`. |
| `tachyon.network.allow-private-networks` | `false` | Accept CORS preflights from the private network address space. |
| `tachyon.network.io-engine` | `auto` | Netty I/O engine: `auto`, `nio`, `epoll`, `kqueue`, `io_uring`. |

The three list properties bind from a YAML list or from a comma-separated value:

```yaml
tachyon:
  network:
    endpoint-path: /mcp
    max-content-length: 2MB
    allowed-origins:
      - https://app.example.com
      - https://admin.example.com
    allowed-hosts:
      - mcp.example.com:8096
```

### Sessions

| Property | Default | Purpose |
|---|---|---|
| `tachyon.session.enabled` | `false` | Keep server-side sessions. |
| `tachyon.session.session-ttl` | `30s` | Evict a session idle for this long. |
| `tachyon.session.janitor-interval` | `5s` | Interval between sweeps that evict expired sessions. |

Setting `session-ttl` or `janitor-interval` enables sessions on its own, matching the core builder;
`tachyon.session.enabled: true` turns them on with the defaults, and `false` states the stateless
choice explicitly.

A stateless server has no session, so `enabled: false` together with `session-ttl` or
`janitor-interval` is rejected at startup rather than silently ignored. The failure names the keys
you set and the file they came from.

### Handler execution

| Property | Default | Purpose |
|---|---|---|
| `tachyon.runtime.request-timeout` | `60s` | Timeout for requests the server sends to the client. |
| `tachyon.runtime.shutdown-grace-period` | `5s` | Time in-flight handlers get to finish on shutdown, before their threads are interrupted. `0` interrupts them immediately. |

### Running alongside Spring MVC or WebFlux

Tachyon owns a separate Netty HTTP server. `tachyon.port` controls MCP; `server.port` controls
Spring's web server. The [onboarding application](./) needs no MVC or WebFlux dependency.

If your application also serves REST endpoints or Actuator over HTTP, assign different ports:

```yaml
tachyon:
  port: 8080
server:
  port: 8081
```

MCP remains at `http://127.0.0.1:8080/mcp`. Spring HTTP endpoints use port `8081`.

### Customize the builder

`tachyon.*` covers values. Everything else — stores, id generators, clocks, JSON codecs,
observability listeners, programmatic feature registration — is wiring, and goes through a
`TachyonServerCustomizer` bean. For example, this configuration plugs in a custom session store —
sessions themselves are already on, because `tachyon.session.session-ttl` is set:

```java
package example;

import dev.tachyonmcp.spring.boot.TachyonServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpConfiguration {
    @Bean
    TachyonServerCustomizer mcpSessions(RedisSessionStore store) {
        return builder -> builder.session(session -> session.sessionStore(store));
    }
}
```

Customizers run after properties and `ServerExtension` beans have been applied, so they can
override property values. Multiple customizers follow Spring ordering, such as `@Order`.
Use them for stores and generators, JSON codecs, observability, or programmatic feature
registration.

See [configuration](../running/configuration.md) for builder options and
[deployment](../running/deployment.md) for bind addresses and accepted public hostnames.

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
[annotations](../annotations.md) for parameter binding, structured results, and third-party adapters.
Automatic bean discovery uses the native Tachyon annotations, including ones declared on an interface
the bean implements.

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
curl http://127.0.0.1:8081/actuator/metrics/mcp.server.operation.duration
```

| Signal | Meaning |
|---|---|
| `tachyon` health component | `UP` with bound `host` and `port` while the starter lifecycle runs; `DOWN` otherwise. |
| `mcp.server.operation.duration` | Operation timer tagged with `mcp.method.name` and `outcome`; created when operations complete. |
| `mcp.server.tools` | Registered tool count. |
| `mcp.server.prompts` | Registered prompt count. |
| `mcp.server.resources` | Registered resource and resource-template count. |

Disable the health indicator with `management.health.tachyon.enabled=false`.
Metrics require Micrometer and a `MeterRegistry` bean. The timer attaches through a customizer
and applies to starter-built servers. Feature gauges also work with a user-provided server.

For traces, add Tachyon's OpenTelemetry listener through a customizer; see
[observability](../running/observability.md) and the
[Spring Boot weather example](https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp-spring-boot).

## GraalVM native image

The starter discovers your tools by reflecting over Spring singletons, so a native image needs
reflection metadata, or it starts a server with nothing registered — and reports no error. The
starter's AOT processor (`TachyonAotProcessor`, registered in `META-INF/spring/aot.factories`)
handles this for you: at AOT processing time it finds every bean type declaring Tachyon
annotations and registers method-invocation hints for it and for each of its interfaces that declares them.

What it does **not** cover are the types you bind to, because nothing declares them as beans —
tool input records, structured output types and their nested types. Register those yourself:

```java
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({GreetingRequest.class, GreetingResponse.class})
class NativeHints {}
```

Native image support is experimental; verify with `./mvnw -Pnative native:compile` and call each
tool once before shipping.

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
- [Bean discovery](https://github.com/tachyonmcp/tachyon/blob/main/integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonFeatureRegistrar.java#L31)
- [Transport lifecycle](https://github.com/tachyonmcp/tachyon/blob/main/integrations/tachyon-spring-boot-starter/src/main/java/dev/tachyonmcp/spring/boot/TachyonServerLifecycle.java#L41)
