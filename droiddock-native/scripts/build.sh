#!/usr/bin/env bash
# Build DroidDock.app: Rust core (staticlib) -> SwiftPM executable -> bundle -> codesign.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/build/DroidDock.app"
ID="${CODESIGN_ID:--}"
# Optional dedicated keychain holding CODESIGN_ID (docs/PLAN-stable-codesign.md at the repo root),
# unlocked with CODESIGN_KEYCHAIN_PASSWORD when set.
KEYCHAIN="${CODESIGN_KEYCHAIN:-}"

# Universal (Apple Silicon + Intel): one DMG and one update zip serve every Mac.
# Each architecture is built on its own and joined with lipo, because SwiftPM's
# multi-arch `--arch a --arch b` needs full Xcode and CI/dev machines may only have
# the Command Line Tools.
# DD_ARCHS narrows it for a quick local build, e.g. DD_ARCHS=arm64 scripts/build.sh.
ARCHS=(${DD_ARCHS:-arm64 x86_64})
rust_target() { case "$1" in arm64) echo aarch64-apple-darwin ;; x86_64) echo x86_64-apple-darwin ;; *) echo "error: unknown arch $1" >&2; exit 1 ;; esac; }

# Package.swift links the core from here (a fat static library holding every arch).
CORE_LIB_DIR="$ROOT/core/target/universal/release"
CORE_LIB="$CORE_LIB_DIR/libdroiddock_core.a"
mkdir -p "$CORE_LIB_DIR"

SLICES=()
for a in "${ARCHS[@]}"; do
  t="$(rust_target "$a")"
  echo "==> cargo build (core, $t)"
  ( cd "$ROOT/core" && MACOSX_DEPLOYMENT_TARGET=14.0 cargo build --release --target "$t" )
  SLICES+=("$ROOT/core/target/$t/release/libdroiddock_core.a")
done
rm -f "$CORE_LIB"
lipo -create "${SLICES[@]}" -output "$CORE_LIB"

BINS=()
for a in "${ARCHS[@]}"; do
  echo "==> swift build ($a)"
  B="$( cd "$ROOT/mac" && swift build -c release --arch "$a" --show-bin-path )/DroidDock"
  # SwiftPM does not relink when only the Rust static library changed, which would ship an old core: force a relink.
  rm -f "$B"
  ( cd "$ROOT/mac" && swift build -c release --arch "$a" )
  [ -f "$B" ] || { echo "error: swift build produced no $B" >&2; exit 1; }
  # The core exposes no embedded version marker that survives LTO reliably, so check freshness instead:
  # the linked binary must be newer than the static library it was built from.
  if [ "$CORE_LIB" -nt "$B" ]; then
    echo "error: $B is older than the Rust core library; the app would ship a stale core" >&2
    exit 1
  fi
  BINS+=("$B")
done
BIN="$ROOT/build/DroidDock-universal"
mkdir -p "$ROOT/build"
rm -f "$BIN"
lipo -create "${BINS[@]}" -output "$BIN"
for a in "${ARCHS[@]}"; do
  lipo "$BIN" -verify_arch "$a" || { echo "error: $BIN is missing $a" >&2; exit 1; }
done
echo "==> architectures: $(lipo -archs "$BIN")"

VERSION="$(grep -m1 '^version' "$ROOT/core/Cargo.toml" | sed -E 's/.*"([^"]+)".*/\1/')"
echo "==> assemble $APP (v$VERSION)"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
mv "$BIN" "$APP/Contents/MacOS/DroidDock"
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
