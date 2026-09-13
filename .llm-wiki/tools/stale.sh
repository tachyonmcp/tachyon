#!/usr/bin/env bash
# Lists wiki pages whose `sources` changed since the page's `commit`, plus dead [[links]].
# Usage: .llm-wiki/tools/stale.sh
set -euo pipefail

wiki="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(git -C "$wiki" rev-parse --show-toplevel)"
cd "$repo"

stale=0
while IFS= read -r page; do
  commit="$(sed -n 's/^commit: *//p' "$page" | head -1)"
  sources="$(sed -n 's/^sources: *\[\(.*\)\]/\1/p' "$page" | head -1 | tr ',' '\n' | sed 's/^ *//;s/ *$//')"
  [ -z "$commit" ] || [ -z "$sources" ] && continue
  if ! git cat-file -e "$commit^{commit}" 2>/dev/null; then
    echo "⚠️  ${page#"$repo"/}: unknown commit $commit"
    continue
  fi
  # shellcheck disable=SC2086
  changed="$(git diff --name-only "$commit" -- $sources 2>/dev/null)"
  if [ -n "$changed" ]; then
    stale=1
    echo "🔴 ${page#"$repo"/} (since $commit):"
    printf '%s\n' "$changed" | sort -u | sed 's/^/     /'
  fi
done < <(find "$wiki" -name '*.md' | sort)

names="$(find "$wiki" -name '*.md' -exec basename {} .md \; | sort -u)"
while IFS=: read -r file link; do
  target="${link#[[}"; target="${target%]]}"
  if ! grep -qx "$target" <<<"$names"; then
    stale=1
    echo "🔗 dead link [[${target}]] in ${file#"$repo"/}"
  fi
done < <(grep -oH '\[\[[^]]*\]\]' -r "$wiki" --include='*.md' || true)

[ "$stale" -eq 0 ] && echo "✅ wiki fresh"
exit 0
