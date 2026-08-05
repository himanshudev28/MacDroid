# Changelog

Release notes are pulled from this file by
[`.github/workflows/release.yml`](.github/workflows/release.yml): the section
whose heading matches the pushed tag becomes the GitHub Release body. **Add a
`## vX.Y.Z` section before tagging** — a tag with no matching section falls back
to a generic body.

---

## v2.0.0

The release where the link stops being one-way. Until now the Mac watched the
phone; now the phone can see and drive the Mac, both apps have real light and
dark themes, and both can update themselves.

### The phone can now see and control your Mac

- **Mac status on the phone's Home screen** — name, battery level and charging
  state, pushed on connect and every 60s. Read-only, on by default, cleared the
  moment the link drops so a stale reading can't linger.
- **Now Playing from the Mac** — title, artist and app, with real transport
  controls. Because the play/pause/skip keys are posted as genuine macOS media
  keys, they drive *any* app that owns the now-playing session, including
  YouTube in a browser tab.
- **Mac volume, brightness, screensaver and lock**, from the phone. Volume is a
  true absolute slider; brightness steps (an absolute level needs a private
  framework).
- All of it stays behind the existing gates: off unless you enable remote
  control, capability-negotiated per connection, muted by the global pause, and
  subject to macOS Accessibility permission.

> Metadata is read from Music and Spotify, plus the active browser tab **only
> when its host is on a media allow-list** — a bank or mail tab is never read,
> and there are tests asserting exactly that.

### Both apps update themselves

- **Mac** — in-app updater. Update artifacts are signed with minisign, and the
  app checks a manifest published with each release.
- **Android** — the APK is now built with `assembleRelease` and signed with a
  **stable release key**. This is what makes in-place updates possible at all:
  Android only lets an APK replace one signed by the same certificate, and
  earlier releases shipped a debug key that changed every build.

> ⚠️ **Upgrading from v1.0.0 or earlier requires one manual uninstall + install.**
> The old APKs were debug-signed, so Android will refuse to update over them.
> This is a one-time break; every release after v2.0.0 updates in place.

### Themes

- **Light and dark on both apps**, following the system or pinned manually, with
  a warm palette and adjustable glass on the Mac.
- The Android light palette was **measured, not eyeballed** — retuned until the
  worst text/background contrast pair reached 4.58:1.

### Android

- **Device management** — remembered Macs, Quick Connect, Disconnect, and
  switching between Macs. Available Macs are discovered over mDNS.
- **Clipboard history** for the session, both directions. Deliberately
  memory-only and capped: everything you copy crosses this link, so persisting
  it would turn a convenience into a plaintext log of passwords and one-time
  codes.
- **Quick Settings tiles** for the connection and the accessibility service.
- **Lock your phone from the Mac**, via accessibility or device admin. There is
  deliberately **no unlock** — Android offers no such API at any privilege a
  sideloaded app can reach, and a Mac that could unlock your phone would defeat
  the lock screen.
- Crash fixes, and the app's first unit-test suite — now run in CI before any
  APK is published.

### Mac

- **Window-wide drag & drop** for sending files, not just the Files tab.
- **Configurable mirror quality** on both transports.
- Screen control and auto-clipboard are now **separate grants** — you can let
  the Mac see your clipboard without letting it drive your screen.
- `control-unavailable` now carries a reason, so the Mac can say *why* a tap
  did nothing instead of failing silently.
- Fixes: the tray's "Paused until" rendered UTC while claiming local time;
  photo-sync dropped `photos-changed` events that arrived mid-pass; the phone's
  player card was blank for the first three seconds after connecting.

### Known limitations

- The macOS app is **unsigned/ad-hoc** — first launch is right-click → Open.
- Optional AES-256-GCM covers **JSON control messages only**. Binary frames —
  file chunks, thumbnails, mirror video — remain in the clear. This is not
  end-to-end encryption of everything, and nothing in the UI claims it is.
- Only one copy of DroidDock can run at a time; they compete for port 48484.
- On Android 13+, sideloaded apps need *Settings → Apps → DroidDock → ⋮ → Allow
  restricted settings* before the accessibility service can be enabled — and
  **reinstalling turns it off again**. Without it, Mac-side taps and swipes do
  nothing.

**Full commit log:** https://github.com/himanshudev28/MacDroid/compare/v1.0.0...v2.0.0

---

## v1.0.0

First release of the Tauri macOS client alongside the Android app, replacing the
retired Electron client. Covers the AirSync parity work (Tiers B–D) and the
runtime fixes found while verifying it on hardware — including a startup
deadlock that made the app appear dead, and several failures that were silent
before they were made visible.
