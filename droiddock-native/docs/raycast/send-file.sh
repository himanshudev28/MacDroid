#!/usr/bin/env bash
# @raycast.schemaVersion 1
# @raycast.title Send File to Phone
# @raycast.mode silent
# @raycast.packageName DroidDock
# @raycast.icon 📱
# @raycast.argument1 { "type": "text", "placeholder": "File path" }

osascript - "$1" <<'EOF'
on run argv
  tell application "DroidDock" to send file {item 1 of argv}
end run
EOF
