#!/usr/bin/env bash
# Build DroidDock.app: Rust core (staticlib) -> SwiftPM executable -> bundle -> codesign.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/build/DroidDock.app"
ID="${CODESIGN_ID:--}"

echo "==> cargo build (core)"
( cd "$ROOT/core" && MACOSX_DEPLOYMENT_TARGET=14.0 cargo build --release --target aarch64-apple-darwin )

echo "==> swift build"
( cd "$ROOT/mac" && swift build -c release --arch arm64 )
BIN="$( cd "$ROOT/mac" && swift build -c release --arch arm64 --show-bin-path )/DroidDock"

VERSION="$(grep -m1 '^version' "$ROOT/core/Cargo.toml" | sed -E 's/.*"([^"]+)".*/\1/')"
echo "==> assemble $APP (v$VERSION)"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/DroidDock"
# Keep symbols for crash symbolication, then strip the shipped executable.
rm -rf "$ROOT/build/DroidDock.dSYM"
dsymutil "$APP/Contents/MacOS/DroidDock" -o "$ROOT/build/DroidDock.dSYM" 2>/dev/null || echo "warning: dsymutil failed"
strip -x "$APP/Contents/MacOS/DroidDock"
sed "s/__VERSION__/$VERSION/g" "$ROOT/mac/Info.plist" > "$APP/Contents/Info.plist"
cp "$ROOT/mac/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
for d in "$ROOT"/mac/Sources/DroidDock/Resources/*.lproj; do cp -R "$d" "$APP/Contents/Resources/"; done
printf 'APPL????' > "$APP/Contents/PkgInfo"

echo "==> codesign (identity: $ID)"
codesign --force --sign "$ID" --identifier com.droiddock.mac --options runtime "$APP"
codesign --verify --strict --verbose=1 "$APP"
echo "==> built $APP"
