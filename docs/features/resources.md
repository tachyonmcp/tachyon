---
title: "Resources"
weight: 15
sidebar_order: 15
toc: true
description: |-
  Declare fixed and templated MCP resources with annotations, return text or binary content, and notify subscribers.
---

Resources let clients read content by URI. Register a fixed resource when one URI always selects
the content. Register a URI template when part of the URI selects a record, file, or other value.

## Declare resources with annotations

Use `@McpResource` for both fixed URIs and URI templates. Register a service containing your resource
methods:

```java
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.core.server.TachyonServer;

class AppResources {
    public record UserProfile(String id, String displayName) {}

    @McpResource(uri = "app://config", description = "Server configuration",
            mimeType = "application/json")
    public String config() {
        return "{\"environment\":\"production\"}";
    }

    @McpResource(name = "user-profile", uri = "app://users/{id}",
            description = "User profile by ID")
    public UserProfile user(String id) {
        return new UserProfile(id, "Ada");
    }
}

var server = TachyonServer.builder()
        .annotations(annotations -> annotations.register(new AppResources()))
        .port(8080)
        .build();
```

The first method exposes a fixed resource. The second exposes a template: reading `app://users/42`
binds `id` to `"42"`. Replace the example profile with your data lookup. Names default to method
names; `uri` is required.

Compile with `-parameters` as shown in the [Quickstart](../quickstart.md). URI variables must exactly
match the method's non-context parameter names. Resource arguments support scalars such as strings,
numbers, booleans, and enums. A fixed resource takes no named arguments. Either kind can receive an
injected `InteractionContext`. See the [annotation reference](../annotations.md) for binding rules.

### Return content

A string becomes text at the requested URI. A record or POJO becomes JSON text with
`application/json` as its default MIME type. Set `mimeType` on the annotation to override that
default. Return `ResourceContents` directly when you need full control of its fields.

For binary content, return raw bytes and declare the MIME type. Add this method to your service:

```java
@McpResource(uri = "app://logo", mimeType = "image/png")
public byte[] logo() throws java.io.IOException {
    return java.nio.file.Files.readAllBytes(java.nio.file.Path.of("logo.png"));
}
```

Tachyon performs the wire encoding; don't Base64-encode the bytes. Resource methods must return a
non-null value. Enum template variables get [automatic completions](completions.md#complete-enum-values-automatically).

## Programmatic registration

Use descriptors for dynamic registration, explicit request access, or URI templates with exploded
list variables. For dependencies returning `CompletionStage`, use the async registration methods.

### Register a fixed resource

Configure resources while building the server. The descriptor's `uri` identifies the resource;
`name` is a display label and doesn't need to be unique.

```java
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.core.server.TachyonServer;

var server = TachyonServer.builder()
        .withResources(resources -> resources.register(
                descriptor -> descriptor
                        .name("config")
                        .uri("app://config")
                        .description("Server configuration")
                        .mimeType("application/json"),
                (context, request) -> TextResourceContents.of(
                        request.uri(), """{"environment":"production"}""", "application/json")))
        .port(8080)
        .build();
```

You can also call `server.resources().register(...)` after `build()`. Registering the same URI and
name replaces its descriptor and handler. Registering the same URI under a different name throws
`IllegalArgumentException`.

### Register a URI template

Templates match parameterized URIs such as `app://users/{id}`. The request exposes each matched
value through `params()`.

```java
server.resources().registerTemplate(
        template -> template
                .name("user-profile")
                .uriTemplate("app://users/{id}")
                .description("User profile by ID")
                .mimeType("application/json"),
        (context, request) -> {
            var id = request.params().get("id").scalarValue();
            return TextResourceContents.of(
                    request.uri(), loadUser(id), "application/json");
        });
```

`ResourceRequest.uriTemplate()` is `null` and `params()` is empty for a fixed resource. Template
values are `UriTemplateValue.Scalar` or `UriTemplateValue.Sequence`; exploded lists such as
`app://files{/segments*}` produce a sequence.

### Use an asynchronous handler

Synchronous handlers run on virtual threads and can block. Use `registerAsync` or
`registerTemplateAsync` when an existing API already returns `CompletionStage`.

```java
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;

server.resources().registerAsync(
        descriptor,
        (context, request) -> httpClient.sendAsync(
                        HttpRequest.newBuilder(URI.create(request.uri())).GET().build(),
                        BodyHandlers.ofString())
                .thenApply(response -> TextResourceContents.of(
                        request.uri(), response.body(), "application/json")));
```

Kotlin resource handlers are suspending functions, so they don't need a separate async registration
method.

### Return binary content

Pass raw bytes to `BlobResourceContents.of`. Tachyon performs the wire encoding.

```java
import dev.tachyonmcp.api.server.domain.BlobResourceContents;

(context, request) -> BlobResourceContents.of(request.uri(), imageBytes, "image/png")
```

Don't Base64-encode the byte array before passing it to Tachyon.

## Notify subscribers

Resource subscriptions require stateful sessions and the resource subscription capability. Enable
both before calling `notifyResourceUpdated`:

```java
var server = TachyonServer.builder()
        .session(session -> session.enabled(true))
        .capabilities(capabilities -> capabilities.resources(true, true))
        .annotations(annotations -> annotations.register(new AppResources()))
        .port(8080)
        .build();

server.resources().notifyResourceUpdated("app://config");
```

The notification reaches sessions subscribed to that URI. The call does nothing when no active
session has subscribed.

## Use the Kotlin DSL

```kotlin
import dev.tachyonmcp.api.server.domain.TextResourceContents
import dev.tachyonmcp.kotlin.server.TachyonServer

val server = TachyonServer(port = 8080) {
    resource(name = "config", uri = "app://config", mimeType = "application/json") {
        TextResourceContents.of(uri, """{"environment":"production"}""", "application/json")
    }

    resourceTemplate(name = "user-profile", uriTemplate = "app://users/{id}") {
        val id = params.getValue("id").scalarValue()
        TextResourceContents.of(uri, loadUser(id), "application/json")
    }
}
```

See the [Kotlin DSL](../kotlin/) for descriptor options and post-build registration.

## Executable examples

[DeclarativeFeaturesTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeFeaturesTest.java) exercises fixed and templated resources.
[DeclarativeResultsTest](https://github.com/tachyonmcp/tachyon/blob/main/e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeResultsTest.java) reads a PNG from a temporary file through
an annotated method and checks the complete resource response, including its URI, MIME type, and
single Base64 encoding.
