---
name: doc-snippets
description: >-
  Keep Markdown code examples under docs/**/*.md in sync with real source via
  snips. Use when adding/editing a doc that embeds source code, when `make
  docs-check` fails, or when source behind an existing snippet marker changed.
metadata:
  author: Konstantin Pavlov
  version: 1.0
---

# Doc snippets (snips)

`snips` links a Markdown code block to real source so docs can't silently drift.
Requires Rust/cargo; Install locally: `cargo install snips`.

## Commands

```bash
make docs-check   # snips --check — CI-style, non-zero if any doc is stale
make docs-sync    # snips — rewrite stale snippets from source, then restore java/kotlin/json fence tags
snips --diff <file.md>   # preview what sync would change, no write
snips --quiet <file.md>  # sync without output
```

`make docs-check` runs as part of `make all`. snips 0.0.6 options: `--check`, `--diff`, `--quiet`.

## Marker syntax

**Named snippet** — wrap the block in source with `snips-start: <name>` / `snips-end: <name>`
comments (any language's comment syntax), then reference it in Markdown with an HTML
comment right before the fenced code block:

```rust
// snips-start: main_feature
println!("hello");
// snips-end: main_feature
```

````markdown
<!-- snips: examples/example.rs#main_feature -->
```rust
println!("hello");
```
````

- **Whole-file**: `<!-- snips: path/to/File.java -->` embeds the entire file, including any
  `snips-start`/`snips-end` comment lines it contains.
- **Paths** resolve relative to the Markdown file, not the working directory.
- Named snippets and whole-file are the only marker kinds in snips 0.0.6..

snips rewrites the fence's language tag to the CodeMirror mode of the source file: `clike`
for Java and Kotlin, `javascript` for JSON. GitHub and the docs site don't highlight those.
`make docs-sync` runs `.github/scripts/fix-doc-fences.py` after snips to restore `java`,
`kotlin` and `json`; `snips --check` ignores the tag. After a bare `snips` run, rerun
`make docs-sync` instead of committing `clike` fences.

## Workflow

1. Writing a doc with a code example that exists in source (or vice versa): wrap the
   source lines in `snips-start`/`snips-end`, then replace the Markdown fenced block
   with the `<!-- snips: ... -->` marker + a fence (content gets filled on sync).
2. `make docs-sync` to populate it, review the diff, commit both the marker and the
   generated content.
3. Source changes later → `make docs-check` catches drift (fails the build); `make
   docs-sync` fixes it.

## Where the examples live

Java and Kotlin code blocks in `docs/` come from `examples/doc-examples` (see its README):
compiled against the published Tachyon release, with a test per example that runs it on a
port-0 server and asserts what the page claims.

- Package per page: `docs/<dir>/<page>.md` → `dev.tachyonmcp.docs.<dir>.<page>` (hyphens
  dropped); `_index.md` → `dev.tachyonmcp.docs.<dir>`; `docs/<page>.md` → `dev.tachyonmcp.docs.<page>`.
- Marker path from a doc: `../examples/doc-examples/src/main/java/dev/tachyonmcp/docs/<page>/Foo.java#name`
  (one more `../` per directory level under `docs/`).
- Put `package` above `snips-start`; imports and the class go inside the snippet. Fragments sit in
  a method whose parameters supply the context, so the doc text compiles unchanged.
- For a block that is a whole example (not a one-line fragment), link its source file inline in
  the lead-in sentence: ``Create `Foo.java` ([source](https://github.com/tachyonmcp/tachyon/blob/main/<repo path>)):``,
  the same `blob/main` form the docs already use.
- snips takes only the first region when a name repeats, so a block mixing top-level types with a builder
  call is two snippets in two fences. Imports can't sit inside a fragment: name their packages in the prose.
- Gradle build files come from `examples/doc-examples/kotlin-gradle/build.gradle.kts`; its `buildSrc` defines
  `tachyon.version` so the embedded text keeps the docs' `${tachyon.version}` placeholder. Verify with
  `gradle run` (Gradle 8.14.3, JDK 21); the Maven build does not run it.
- A doc claim that the code contradicts is a doc bug: fix the doc, don't bend the test.
- Build files and shell blocks (`${tachyon.version}` placeholders) stay inline; the SNAPSHOT
  switch is the commented `tachyon.version` line in the module pom.
- Reference page: `docs/quickstart.md` → `quickstart/MyMcpServer.java` + `MyMcpServerTest.java`.

## Gotchas

- Keep `snips-start` on the line directly above the first snippet line and `snips-end` directly
  below the last. A blank line inside the markers becomes a blank first or last line in the doc.
- `snips` with no file args processes `.md` and `.markdown` files in the **current directory
  only** (not recursive) — that's why the Makefile targets pass an explicit `find docs -name
  '*.md'` file list (scoped to `docs/`) instead of relying on the default.
- A stale snippet doesn't error at doc-write time — only `--check` catches it, so it's
  only as good as CI/`make all` actually running `docs-check`.
