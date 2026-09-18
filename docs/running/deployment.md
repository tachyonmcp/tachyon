---
title: "Deployment"
weight: 60
sidebar_order: 60
toc: true
description: |-
  Deploy a Tachyon server to a platform: bind address, port, public hostname and DNS-rebinding protection, browser clients, and container signal handling.
---

A Tachyon server is a plain Java process with an embedded Netty listener, so it
runs anywhere that runs a JVM or a container. Three settings usually change when
it moves off a developer machine.

Full option reference: [configuration](configuration.md). Stateless mode,
long-running tools and shutdown behaviour: [FAQ](../faq.md#deployment-and-operations).

## 1. Bind address

`host` defaults to `127.0.0.1`. A platform routes to the process from outside,
so the server has to listen on every interface.

```java
.network(n -> n.host("0.0.0.0"))
```

## 2. Port

Most platforms assign the port and pass it in the environment. Read it there
instead of hard-coding one.

```java
.network(n -> n.port(Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"))))
```

## 3. Public hostname

This is the one that is easy to miss.

[DNS-rebinding protection](configuration.md#dns-rebinding-protection) is on by
default, and it accepts only `localhost` and loopback authorities. A public
hostname is neither. Until `allowedHosts` names it, the server answers
`403 Forbidden` to every request that arrives through that hostname.

`host` and `allowedHosts` are unrelated. `host` decides which interface the
server binds. `allowedHosts` decides which `Host` headers it answers. Binding
more widely never affects the `403`.

```java
.network(n -> {
    var allowedHost = System.getenv("ALLOWED_HOST");
    if (allowedHost != null && !allowedHost.isBlank()) {
        n.allowedHosts(allowedHost);
    }
})
```

Entries are bare authorities, not URLs. `example.com` matches that host on any
port, `example.com:8096` only that port. An entry holding a scheme or a path is
rejected when the server is built, so a variable containing a full URL cannot be
passed straight through.

How the value reaches the app depends on the platform.

- Some platforms hand the app its own hostname in the environment. Read it at
  startup and the deployment is one pass. Take the bare-hostname variable if the
  platform offers both, since the full-URL one is rejected here.
- Otherwise the hostname is not known until the app exists, so it is two passes:
  deploy, read the assigned hostname, set it, deploy again.

### Verify the guard is on

A successful request does not prove the allowlist works, because an unset
allowlist and a correct one both let a good request through on localhost. Send a
request the server has to refuse. A trailing dot is the same host to DNS but a
different string to the guard:

```shell
probe() {
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Host: $1" https://YOUR-HOST/mcp)
    case "$code" in
        000) echo "$1: no HTTP response (DNS, TLS or connection failure)" ;;
        *)   echo "$1: $code" ;;
    esac
}
probe 'YOUR-HOST'
probe 'YOUR-HOST.'
```

The second must return `403`. If both return the same code, the `Host` check is
not filtering anything and the first result proved nothing. Treat `000` as a
failed probe rather than a result: two failed requests also match each other,
and they say nothing about the guard.

## Browser clients

`allowedHosts` widens the `Host` check only. A request carrying an `Origin`
header that is not loopback is still rejected, so a browser page cannot reach a
remote Tachyon server. Clients that send no `Origin` are unaffected, which is
most MCP clients.

## More than one instance

Sessions are off by default, and a stateless server scales horizontally with no
sticky routing. A stateful server keeps live sessions
in-process, so more than one instance needs sticky routing while a session is
active.

Experimental `SessionStore` and `SessionEventStore` implementations can persist session snapshots and replay events across restarts. They do not
coordinate live session or transport ownership between nodes. See [session configuration](configuration.md#session).
If session-store lookup fails during a POST, the server returns HTTP 500 with `Session lookup failed`. An unknown session returns HTTP 404.

## Containers

- The JVM has to receive the platform's stop signal, or `shutdownGracePeriod`
  never runs. That means the JVM is PID 1, or its parent forwards signals to it
  (an init process, or an entrypoint that `exec`s the JVM). The usual failure is
  a shell wrapper that stays PID 1 and swallows the signal. The exec form of
  `CMD` is the safe default.
- The filesystem is ephemeral on most platforms. Anything written at runtime is
  gone after the next deploy.

## Worked example

[`examples/weather-mcp`](https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp)
reads `HOST`, `PORT` and `ALLOWED_HOST` from the environment, so it needs no code
change to run remotely. A container entrypoint supplies the last one from
whatever variable the platform assigns:

```sh
[ -z "$ALLOWED_HOST" ] && [ -n "$PLATFORM_APP_HOSTNAME" ] \
    && export ALLOWED_HOST="$PLATFORM_APP_HOSTNAME"
```

Pick the variable holding the **bare authority**. Platforms that also expose a
full URL (`https://…`) offer the wrong one for `allowedHosts` — an entry carrying
a scheme or a path is rejected when the server is built.

Deploy, then run the two `probe` calls above against the assigned hostname.
