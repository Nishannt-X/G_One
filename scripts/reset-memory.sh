#!/usr/bin/env sh
# Dev only: resets farmer memory so the next demo take starts from the seed.
# Deletes exactly backend/data/g-one.db, g-one.db-wal and g-one.db-shm, nothing else.
# Usage (repo root, server stopped):  sh scripts/reset-memory.sh [data-dir]
set -eu

dir="${1:-$(dirname "$0")/../backend/data}"
port="${PORT:-8080}"

case "$port" in
  ''|*[!0-9]*) echo "PORT must be a number (got '$port')." >&2; exit 1 ;;
esac

# Prints "yes" or "no" for a listener on $port, or nothing when no tool could check. Any listener blocks the reset,
# whatever is serving there: the backend may be on a non-HTTP state or behind another process.
listening() {
  if command -v ss >/dev/null 2>&1; then
    if out=$(ss -Hltn "sport = :$port" 2>/dev/null); then
      [ -n "$out" ] && echo yes || echo no
      return
    fi
  fi
  if command -v lsof >/dev/null 2>&1; then
    # lsof exits 1 both for "no match" and for some errors, so trust it only when it printed nothing to stderr.
    if err=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>&1 >/dev/null); then echo yes; return; fi
    if [ -z "$err" ]; then echo no; return; fi
  fi
  if command -v netstat >/dev/null 2>&1; then
    # Linux and macOS print LISTEN, Windows (Git Bash) prints LISTENING; macOS writes the port as *.8080.
    if out=$(netstat -an 2>/dev/null); then
      printf '%s
' "$out" | grep -Eiq "[:.]$port[[:space:]].*LISTEN" && echo yes || echo no
      return
    fi
  fi
}

case "$(listening)" in
  yes)
    echo "Something is listening on port $port. Stop the backend first (Ctrl+C in its terminal), then run this again." >&2
    exit 1 ;;
  no) ;;
  *)
    echo "Could not check whether port $port is in use (no working ss, lsof or netstat). Nothing was deleted." >&2
    echo "Stop the backend yourself and delete $dir/g-one.db* by hand, or install one of those tools." >&2
    exit 1 ;;
esac

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
