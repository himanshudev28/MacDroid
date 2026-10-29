#!/usr/bin/env bash
# Dev-only bundle for BLE testing: build/DroidDockDev.app with its own bundle id
# (com.droiddock.mac.dev), so macOS's Bluetooth permission (TCC) and UserDefaults
# are isolated from the real com.droiddock.mac. An unbundled binary has no
# usage string and TCC may kill it the moment Bluetooth is used.
#
# Run the inner binary directly with a throwaway data dir and port, never `open` it:
#   DD_DATA_DIR=/tmp/dd-ble build/DroidDockDev.app/Contents/MacOS/DroidDock
# (droiddock.json in that dir: {"token":"...","port":48614})
# It does NOT touch the real app, its defaults or its data. Never run scripts/build.sh from here.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/build/DroidDockDev.app"
ID="${CODESIGN_ID:--}"
BIN="$ROOT/mac/.build/debug/DroidDock"
[ -x "$BIN" ] || BIN="$( cd "$ROOT/mac" && swift build --show-bin-path )/DroidDock"
[ -x "$BIN" ] || { echo "build first: (cd mac && swift build)"; exit 1; }

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/DroidDock"
sed -e "s/__VERSION__/dev/g" \
    -e "s#<string>com.droiddock.mac</string>#<string>com.droiddock.mac.dev</string>#" \
    "$ROOT/mac/Info.plist" > "$APP/Contents/Info.plist"
cp "$ROOT/mac/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
for d in "$ROOT"/mac/Sources/DroidDock/Resources/*.lproj; do cp -R "$d" "$APP/Contents/Resources/"; done
printf 'APPL????' > "$APP/Contents/PkgInfo"
codesign --force --sign "$ID" --identifier com.droiddock.mac.dev "$APP"
echo "built $APP (bundle id com.droiddock.mac.dev)"
