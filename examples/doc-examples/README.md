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

## Commands

From the repository root:

```bash
./mvnw -q -f examples/doc-examples/pom.xml verify   # compile and test the examples
make docs-sync                                       # copy snippets into docs/ (snips)
make docs-check                                      # fail if any doc snippet is stale
```

## Covered pages

| Page | Example | Test |
|---|---|---|
| [`quickstart.md`](../../docs/quickstart.md) | `quickstart/MyMcpServer.java` | `quickstart/MyMcpServerTest.java` |

Build files (`pom.xml`, Gradle, shell) in the docs stay inline: they use the `${tachyon.version}`
placeholder that the docs site fills in.
