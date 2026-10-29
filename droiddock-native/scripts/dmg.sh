#!/usr/bin/env bash
# Package build/DroidDock.app into a compressed DMG.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/build/DroidDock.app"
[ -d "$APP" ] || { echo "build first: scripts/build.sh" >&2; exit 1; }
VERSION="$(grep -m1 '^version' "$ROOT/core/Cargo.toml" | sed -E 's/.*"([^"]+)".*/\1/')"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
OUT="$ROOT/build/DroidDock-$VERSION.dmg"
rm -f "$OUT"
hdiutil create -volname "DroidDock" -srcfolder "$STAGE" -ov -format UDZO "$OUT"
echo "==> $OUT"
