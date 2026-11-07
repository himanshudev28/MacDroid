#!/usr/bin/env bash
# @raycast.schemaVersion 1
# @raycast.title Text from Phone
# @raycast.mode silent
# @raycast.packageName DroidDock
# @raycast.icon 📱
# @raycast.argument1 { "type": "text", "placeholder": "Number" }
# @raycast.argument2 { "type": "text", "placeholder": "Message" }

osascript - "$1" "$2" <<'EOF'
on run argv
  tell application "DroidDock" to send sms to (item 1 of argv) text (item 2 of argv)
end run
EOF
