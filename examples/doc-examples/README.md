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

| Page | Example | Test |
|---|---|---|
| [`quickstart.md`](../../docs/quickstart.md) | `quickstart/MyMcpServer.java` | `quickstart/MyMcpServerTest.java` |
| [`kotlin/_index.md`](../../docs/kotlin/_index.md) | `kotlin/*.kt` | `kotlin/*Test.kt` |

XML dependency blocks and shell commands in the docs stay inline: they use the `${tachyon.version}` placeholder
that the docs site fills in. A snippet name can appear once per file, so a block that mixes top-level types with a
builder call is embedded as two consecutive fences.
