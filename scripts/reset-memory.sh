#!/usr/bin/env sh
# Creates a fresh data directory for a new demo take. Existing memory is never deleted.
# Usage: export G_ONE_DATA_DIR="$(sh scripts/reset-memory.sh)"
set -eu

dir="${1:-$(dirname "$0")/../backend/data}"
mkdir -p -- "$dir"
dir=$(cd -- "$dir" && pwd -P)
take=$(mktemp -d "$dir/take-XXXXXXXX")
printf '*\n' > "$take/.gitignore"
if command -v cygpath >/dev/null 2>&1; then
  cygpath -w "$take"
else
  printf '%s\n' "$take"
fi
