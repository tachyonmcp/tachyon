# Echo Kotlin MCP Example

The smallest viable MCP server built with the Tachyon Kotlin DSL — start here.

## Features

- **Tool**: `echo` — declared with `typedTool<EchoRequest, EchoResponse>`, schemas generated from
  the data classes
- **Tool**: `reverse-echo` — registered with `server.registerTool(…)` and a hand-written
  `JsonSchema.unchecked` schema

Two tools, two registration styles: schema-from-type, and a schema you supply yourself.

## Quickstart

```shell
./mvnw package && \
java -jar target/echo-kotlin-example.jar
```

Listens on `http://localhost:8080/mcp`. `HOST`, `PORT` and `ALLOWED_HOST` override the bind address
and the DNS-rebinding guard — see [../README.md](../README.md#binding-and-access-from-docker),
which also covers reaching the server from Docker.
