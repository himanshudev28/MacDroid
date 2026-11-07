#!/usr/bin/env bash
# @raycast.schemaVersion 1
# @raycast.title Phone Battery
# @raycast.mode silent
# @raycast.packageName DroidDock
# @raycast.icon 📱

osascript -e 'tell application "DroidDock" to get battery' | tr ',' ' '
