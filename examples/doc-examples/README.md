# doc-examples

Compiled, tested Java and Kotlin behind the code blocks in [`docs/`](../../docs). `snips` copies
named snippets from here into the Markdown, so a doc cannot drift from code that builds and passes
its tests. Not a server to run: each page's example has its own test.

Builds against the published Tachyon release pinned by `tachyon.version` in `pom.xml`, like the
other examples.

## Layout

- One package per doc page: `docs/<dir>/<page>.md` is `dev.tachyonmcp.docs.<dir>.<page>` (hyphens
  dropped), `docs/<dir>/_index.md` is `dev.tachyonmcp.docs.<dir>`, `docs/<page>.md` is
  `dev.tachyonmcp.docs.<page>`.
- Java in `src/main/java`, Kotlin in `src/main/kotlin`, tests mirroring the package in
  `src/test/java` and `src/test/kotlin`.
- Snippets are wrapped in `// snips-start: <name>` and `// snips-end: <name>`. The doc references them
  with `<!-- snips: ../examples/doc-examples/src/main/java/.../Foo.java#<name> -->` above the fence;
  the path is relative to the Markdown file.
- Tests start the example on a port-0 server through `tachyon-testkit` and assert what the page claims.
- `kotlin-gradle/` is a real Gradle project for the Kotlin page: its `build.gradle.kts` is embedded in the docs,
  and its `src/main/kotlin/MyMcpServer.kt` is the page's first server, also compiled by the Maven module. `buildSrc`
  defines `tachyon.version` so the build file keeps the docs' `${tachyon.version}` placeholder; keep it in step with
  `tachyon.version` in `pom.xml`.

## Commands

From the repository root:

```bash
./mvnw -q -f examples/doc-examples/pom.xml verify   # compile and test the examples
make docs-sync                                       # copy snippets into docs/ (snips)
make docs-check                                      # fail if any doc snippet is stale
```

The Gradle project needs Gradle 8.14.3 and JDK 21, and is not part of the Maven build:

```bash
cd examples/doc-examples/kotlin-gradle && gradle run   # serves reverse-echo on http://127.0.0.1:8080/mcp
```

## Covered pages

| Page | Package under `dev.tachyonmcp.docs` |
|---|---|
| [`quickstart.md`](../../docs/quickstart.md) | `quickstart` |
| [`annotations.md`](../../docs/annotations.md) | `annotations` |
| [`json.md`](../../docs/json.md) | `json` |
| [`advanced/sse-reconnect-redelivery.md`](../../docs/advanced/sse-reconnect-redelivery.md) | `advanced.ssereconnectredelivery` |
| [`extensions/_index.md`](../../docs/extensions/_index.md) | `extensions` |
| [`extensions/skills.md`](../../docs/extensions/skills.md) | `extensions.skills` |
| [`extensions/custom-extensions.md`](../../docs/extensions/custom-extensions.md) | `extensions.customextensions` |
| [`extensions/tasks.md`](../../docs/extensions/tasks.md) | `extensions.tasks` |
| [`features/_index.md`](../../docs/features/_index.md) | `features` |
| [`features/tools.md`](../../docs/features/tools.md) | `features.tools` |
| [`features/resources.md`](../../docs/features/resources.md) | `features.resources` |
| [`features/prompts.md`](../../docs/features/prompts.md) | `features.prompts` |
| [`features/completions.md`](../../docs/features/completions.md) | `features.completions` |
| [`features/client-interactions.md`](../../docs/features/client-interactions.md) | `features.clientinteractions` |
| [`kotlin/_index.md`](../../docs/kotlin/_index.md) | `kotlin` |
| [`kotlin/kt-schema-json.md`](../../docs/kotlin/kt-schema-json.md) | `kotlin.ktschemajson` (plus `com.example.weather.*`, the models the page shows) |
| [`kotlin/migrate-from-kotlin-sdk.md`](../../docs/kotlin/migrate-from-kotlin-sdk.md) | `kotlin.migratefromkotlinsdk` |
| [`running/configuration.md`](../../docs/running/configuration.md) | `running.configuration` |
| [`running/deployment.md`](../../docs/running/deployment.md) | `running.deployment` |
| [`running/observability.md`](../../docs/running/observability.md) | `running.observability` |
| [`spring-boot/_index.md`](../../docs/spring-boot/_index.md) | `example` (the page's own `package example`) |
| [`spring-boot/reference.md`](../../docs/spring-boot/reference.md) | `springboot.reference` |
| [`testkit.md`](../../docs/testkit.md) | `testkit` (the snippets are test classes under `src/test/java`) |

Each package holds the page's examples in `src/main`; the matching tests in `src/test` run them.
Not covered: `architecture/` and `assets/` pages, and blocks that stay inline: build files, shell,
XML, YAML (except the Spring Boot guide's `application.yaml`), JSON wire samples, and quotes of
the library's own interfaces (`AnnotationProvider`, `Tasks`, `SkillsRegistry`).

## Patterns

- **Fixed-port snippets** (`.port(8080)`, `TachyonServer(port = 8080)`) live in a `main` that a test forks with
  `ForkedMain` (`src/test/java/.../ForkedMain.java`), so the page's code runs verbatim. The test skips itself
  when the port is busy. `ForkedMain.output()` returns the console output, for pages that assert on logs.
- **Mid-chain fragments** (`.network(...)`, `.withTools(...)`) sit between `.port(0)` and `.build()` in a helper
  that returns a started server, so the exact text from the page runs on a free port.
- **Builder snippets that show alternatives** (for example the three `session` options) cannot run as one chain;
  the test asserts the documented build-time failure and checks each alternative separately.
- **Collaborators the page leaves undefined** (`workflows`, `cityService`, `read(uri)`) are small stand-ins next
  to the example; the page says so in the lead-in.
- `JsonRpc` and `RawHttp` (`src/test/java/dev/tachyonmcp/docs`) are the shared test helpers.
- Fixtures the pages read from the working directory: `skills/` and `logo.png` (module root), and
  `src/main/resources` (`bundled-skills/`, `skills/`, `logo-32x32.png`, `application.yaml`).
