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
make docs-sync    # snips — rewrite stale snippets from source
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

snips auto-detects the language per GitHub Linguist for syntax highlighting; no need to
hand-pick the fence's language tag once a marker references real source.

## Workflow

1. Writing a doc with a code example that exists in source (or vice versa): wrap the
   source lines in `snips-start`/`snips-end`, then replace the Markdown fenced block
   with the `<!-- snips: ... -->` marker + a fence (content gets filled on sync).
2. `make docs-sync` to populate it, review the diff, commit both the marker and the
   generated content.
3. Source changes later → `make docs-check` catches drift (fails the build); `make
   docs-sync` fixes it.

## Gotchas

- `snips` with no file args processes `.md` and `.markdown` files in the **current directory
  only** (not recursive) — that's why the Makefile targets pass an explicit `find docs -name
  '*.md'` file list (scoped to `docs/`) instead of relying on the default.
- A stale snippet doesn't error at doc-write time — only `--check` catches it, so it's
  only as good as CI/`make all` actually running `docs-check`.
