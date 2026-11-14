#!/usr/bin/env bash
# Build DroidDock.app: Rust core (staticlib) -> SwiftPM executable -> bundle -> codesign.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/build/DroidDock.app"
ID="${CODESIGN_ID:--}"
# Optional dedicated keychain holding CODESIGN_ID (docs/PLAN-stable-codesign.md at the repo root),
# unlocked with CODESIGN_KEYCHAIN_PASSWORD when set.
KEYCHAIN="${CODESIGN_KEYCHAIN:-}"

echo "==> cargo build (core)"
( cd "$ROOT/core" && MACOSX_DEPLOYMENT_TARGET=14.0 cargo build --release --target aarch64-apple-darwin )

echo "==> swift build"
BIN="$( cd "$ROOT/mac" && swift build -c release --arch arm64 --show-bin-path )/DroidDock"
# SwiftPM does not relink when only the Rust static library changed, which would ship an old core: force a relink.
rm -f "$BIN"
( cd "$ROOT/mac" && swift build -c release --arch arm64 )
[ -f "$BIN" ] || { echo "error: swift build produced no $BIN" >&2; exit 1; }
CORE_LIB="$ROOT/core/target/aarch64-apple-darwin/release/libdroiddock_core.a"
# The core exposes no embedded version marker that survives LTO reliably, so check freshness instead:
# the linked binary must be newer than the static library it was built from.
if [ -f "$CORE_LIB" ] && [ "$CORE_LIB" -nt "$BIN" ]; then
  echo "error: $BIN is older than the Rust core library; the app would ship a stale core" >&2
  exit 1
fi

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
cp "$ROOT/mac/Sources/DroidDock/Scripting/DroidDock.sdef" "$APP/Contents/Resources/DroidDock.sdef"
for d in "$ROOT"/mac/Sources/DroidDock/Resources/*.lproj; do cp -R "$d" "$APP/Contents/Resources/"; done
printf 'APPL????' > "$APP/Contents/PkgInfo"

echo "==> codesign (identity: $ID)"
SIGN_ARGS=()
if [ -n "$KEYCHAIN" ]; then
  # codesign only finds identities in keychains on the user search list: add ours for the
  # signing step and put the original list back afterwards, failure included.
  [ -n "${CODESIGN_KEYCHAIN_PASSWORD:-}" ] && security unlock-keychain -p "$CODESIGN_KEYCHAIN_PASSWORD" "$KEYCHAIN"
  ORIG_KEYCHAINS=()
  while IFS= read -r k; do
    k="${k#"${k%%[![:space:]]*}"}"; k="${k#\"}"; k="${k%\"}"
    [ -n "$k" ] && ORIG_KEYCHAINS+=("$k")
  done < <(security list-keychains -d user)
  trap 'security list-keychains -d user -s ${ORIG_KEYCHAINS[@]+"${ORIG_KEYCHAINS[@]}"}' EXIT
  security list-keychains -d user -s ${ORIG_KEYCHAINS[@]+"${ORIG_KEYCHAINS[@]}"} "$KEYCHAIN"
  SIGN_ARGS=(--keychain "$KEYCHAIN")
fi
codesign --force --sign "$ID" ${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"} --identifier com.droiddock.mac --options runtime "$APP"
codesign --verify --strict --verbose=1 "$APP"
echo "==> built $APP"
