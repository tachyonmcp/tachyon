#!/usr/bin/env bash
# Audit tracked wiki sources and links. Add --check for a nonzero exit on findings.
set -euo pipefail
exec python3 "$(dirname "$0")/audit.py" "$@"
