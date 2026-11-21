# Open-source phone<->desktop bridge candidates (snapshot 2026-11-21)

Activity: active = pushed <3 months ago; slow = 3-12 months; stale = >12 months.
Licence "NOASSERTION" = GitHub could not classify the licence file; "none" = no licence detected.

| repo | stars | last push | license | language | archived? | what it does | platforms | activity |
|---|---|---|---|---|---|---|---|---|
| Genymobile/scrcpy | 155296 | 2026-11-19 | Apache-2.0 | C | no | Display and control an Android device over USB/Wi-Fi (mirroring, input, no root) | Desktop: Win/macOS/Linux; Android server | active |
| localsend/localsend | 100011 | 2026-11-21 | Apache-2.0 | Dart | no | LAN file/text transfer, AirDrop-style (no notifications/SMS) | Android, iOS, macOS, Windows, Linux, web | active |
| barry-ran/QtScrcpy | 32963 | 2026-10-15 | Apache-2.0 | C++ | no | Qt GUI around scrcpy: mirror, control, multi-device | Win/macOS/Linux | active |
| viarotel-org/escrcpy | 12429 | 2026-10-26 | Apache-2.0 | JavaScript (Electron) | no | Electron GUI around scrcpy: mirror, control, file drop | Win/macOS/Linux | active |
| grishka/NearDrop | 6360 | 2026-09-19 | Unlicense | Swift | no | Native macOS receiver for Google Quick Share / Nearby Share | macOS only | active |
| SimonAKing/scrcpy-gui | 4200 | 2026-11-11 | MIT | TypeScript | no | GUI for scrcpy (mirror/control) | Desktop; OS list not verified | active |
| KDE/kdeconnect-kde | 4178 | 2026-11-21 | none (API) | C++ | no | Reference KDE Connect desktop: notifications, SMS, clipboard, files, remote input, ping | Linux primary; Windows; macOS (Qt build) | active |
| GSConnect/gnome-shell-extension-gsconnect | 3761 | 2026-10-18 | GPL-2.0 | JavaScript | no | KDE Connect implementation as a GNOME Shell extension | Linux (GNOME) | active |
| Martichou/rquickshare | 3668 | 2026-05-01 | GPL-3.0 | Rust | no | Quick Share (Nearby Share) client for Linux and macOS | Linux, macOS | slow |
| shrimqy/Sefirah | 3111 | 2026-11-20 | GPL-3.0 | C# | no | Phone Link / KDE Connect alternative: notifications, clipboard, files | Windows (desktop) + Android | active |
| KDE/kdeconnect-android | 1521 | 2026-11-20 | GPL-2.0 | Kotlin | no | Android side of the KDE Connect protocol (peer for kdeconnect-kde) | Android | active |
| kil0bit-kb/scrcpy-gui | 1479 | 2026-08-24 | none (API) | TypeScript | no | Modern GUI for scrcpy | Desktop; OS list not verified | active |
| dev47apps/droidcam-linux-client | 1230 | 2026-10-29 | GPL-2.0 | C | no | Linux client for DroidCam: phone as webcam (Android app is separate, closed) | Linux | active |
| andyholmes/valent | 925 | 2026-11-15 | NOASSERTION | C | no | KDE Connect-protocol device sync for GNOME (notifications, SMS, files, clipboard) | Linux (GNOME) | active |
| sameerasw/airsync-mac | 887 | 2026-11-16 | NOASSERTION | Swift | no | macOS side of AirSync: Android notifications, clipboard, file sharing, Mac-native | macOS | active |
| Shrey113/Adb-Device-Manager-2 | 666 | 2026-08-28 | none (API) | HTML | no | ADB device manager with scrcpy-based mirroring and control | Windows only | active |
| GeorgeEnglezos/Scrcpy-GUI | 511 | 2026-09-02 | none (API) | Dart | no | Beginner-friendly Flutter GUI for scrcpy | Desktop; OS list not verified | active |
| KDE/kdeconnect-ios | 429 | 2026-11-19 | NOASSERTION | Swift | no | Native iOS port of KDE Connect (iPhone peer, not Android) | iOS | active |
| subhashhhhhh/Tether | 278 | 2026-11-01 | GPL-2.0 | Swift | no | Native macOS companion for Android on the KDE Connect protocol (Mac-native, below 300 stars, included as rule allows) | macOS + Android | active |

## Checked and excluded or not found
- Below 300 stars: sameerasw/airsync-android (296, companion to airsync-mac, pushed 2026-11-19); ZorinOS/zorin-connect-android (300, exactly at threshold, GPL-2.0, Kotlin, pushed 2026-10-27); nnnlog/phone-link (31, MIT, Rust, pushed 2026-11-17); shantanunautiyal/phone-link (9); kaleidot725/ScrcpyHub (159); Stardawn0v0/PyQtScrcpy (155).
- Name not found: valent-gnome/valent returned 404 (correct repo is andyholmes/valent, used above). zorin-os/zorin-connect-android returned 404 (correct repo is ZorinOS/zorin-connect-android).
- Not in this table: pdf/kdeconnect-chrome-extension (250, browser only), shrimqy/Sefirah-Android (1010 stars, Android companion to Sefirah; pushed 2026-10-15, GPL-3.0 with Kotlin, a missing row to add if the list is extended).

## Known gaps
- The GitHub search API hit its rate limit after roughly 30 queries. The discovery queries "android mac notifications sync", "android macos clipboard sync", "phone link mac android", "android webcam macos", "android sms mac", "quick share macos", "android mirror mac swift" returned no results above the star threshold, or were not run because of the limit. Re-run them after the limit resets to fill the table to 25-35 rows.
- Platform cells marked "not verified" were not checked against the repo README.
