#!/usr/bin/env bash
# Run the built app binary directly (never `open`: same bundle id as the installed app).
# Default: throwaway data dir on port 48600. --real: the real data dir (and the real port!).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$ROOT/build/DroidDock.app/Contents/MacOS/DroidDock"
[ -x "$BIN" ] || { echo "build first: scripts/build.sh" >&2; exit 1; }
if [ "${1:-}" = "--real" ]; then
  echo "WARNING: using the real data dir (port 48484). Quit the installed DroidDock first." >&2
  exec "$BIN"
fi
TMP="$(mktemp -d "${TMPDIR:-/tmp}/droiddock-dev.XXXXXX")"
printf '{"token":"devtoken1234","port":48600}\n' > "$TMP/droiddock.json"
echo "data dir: $TMP (port 48600)" >&2
DD_DATA_DIR="$TMP" exec "$BIN"
