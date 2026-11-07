#!/usr/bin/env bash
# @raycast.schemaVersion 1
# @raycast.title Send Clipboard to Phone
# @raycast.mode silent
# @raycast.packageName DroidDock
# @raycast.icon 📱

osascript -e 'tell application "DroidDock" to send clipboard'
