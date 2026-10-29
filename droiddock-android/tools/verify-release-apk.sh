#!/usr/bin/env bash
# Verifies every class named in the release APK's manifest (and the classes the
# manifest-linked res/xml files belong to) exists in the dex under its original
# name. Catches R8 removing or renaming an entry point.
# Usage: tools/verify-release-apk.sh [path/to/app-release.apk]
set -euo pipefail
cd "$(dirname "$0")/.."
APK="${1:-app/build/outputs/apk/release/app-release.apk}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2=$(ls -d "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)
APKANALYZER=$(ls "$SDK"/cmdline-tools/*/bin/apkanalyzer | tail -1)
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

PKG=$("$AAPT2" dump packagename "$APK")
CLASSES=$("$APKANALYZER" dex packages --defined-only "$APK" | awk '$1=="C"{print $NF}')
# Manifest component names (activity/service/receiver/provider), resolved against the package.
NAMES=$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" \
  | awk '/E: (activity|activity-alias|service|receiver|provider)/{c=1} c&&/A: http:\/\/schemas.android.com\/apk\/res\/android:name\(/{
      match($0,/="[^"]*"/); print substr($0,RSTART+2,RLENGTH-3); c=0}')
fail=0; n=0
for name in $NAMES; do
  case "$name" in .*) name="$PKG$name";; *.*) ;; *) name="$PKG.$name";; esac
  n=$((n+1))
  if grep -qxF "$name" <<<"$CLASSES"; then echo "ok       $name"
  else echo "MISSING  $name"; fail=1; fi
done
# Classes that code looks up by name at runtime (Settings.Secure enabled list).
for name in "$PKG.ClipAccessibilityService" "$PKG.NotifListener"; do
  grep -qxF "$name" <<<"$CLASSES" || { echo "MISSING  $name"; fail=1; }
done
echo "checked $n manifest components"
exit $fail
