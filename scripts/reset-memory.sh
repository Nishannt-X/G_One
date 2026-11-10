#!/usr/bin/env sh
# Dev only: resets farmer memory so the next demo take starts from the seed.
# Deletes exactly backend/data/g-one.db, g-one.db-wal and g-one.db-shm, nothing else.
# Usage (repo root, server stopped):  sh scripts/reset-memory.sh [data-dir]
set -eu

dir="${1:-$(dirname "$0")/../backend/data}"
port="${PORT:-8080}"

if command -v lsof >/dev/null 2>&1 && lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Something is listening on port $port. Stop the backend first (Ctrl+C in its terminal), then run this again." >&2
  exit 1
fi
if ! command -v lsof >/dev/null 2>&1 && curl -s -o /dev/null "http://localhost:$port/" 2>/dev/null; then
  echo "Something answers on port $port. Stop the backend first (Ctrl+C in its terminal), then run this again." >&2
  exit 1
fi

if [ ! -d "$dir" ]; then
  echo "No data directory at $dir; nothing to reset."
  exit 0
fi

removed=0
for name in g-one.db g-one.db-wal g-one.db-shm; do
  if [ -f "$dir/$name" ]; then
    rm -f -- "$dir/$name"
    echo "Deleted $dir/$name"
    removed=$((removed + 1))
  fi
done
[ "$removed" -eq 0 ] && echo "No g-one.db files in $dir; nothing to reset."
echo "Start the backend again; check the log for 'Farmer memory seeded from ...'."
