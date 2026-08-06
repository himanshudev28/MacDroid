# Changelog

Release notes are pulled from this file by
[`.github/workflows/release.yml`](.github/workflows/release.yml): the section
whose heading matches the pushed tag becomes the GitHub Release body. **Add a
`## vX.Y.Z` section before tagging** — a tag with no matching section falls back
to a generic body.

---

## v2.0.1

### Fixed

- **The Mac window no longer appears on every desktop.** The cause was never a
  window property: macOS keeps per-app Space assignments in your Dock
  preferences (Dock icon → Options → Assign To), and the Dock stamps that answer
  onto every window the app opens, after the app has positioned them. Once
  DroidDock picked up an `All Desktops` assignment, nothing the app did to its
  own windows could override it, and it survived reinstalls because it isn't
  stored in the app. **Settings → System** now detects the assignment and offers
  **Keep on one desktop**, which clears it.
- **"Open DroidDock" no longer drags the window to whichever desktop you're
  on.** `show()` on a window that is already open somewhere else does not mean
  "make visible" — AppKit reads it as "put this window in front *here*" and
  relocates it. Click the tray icon from a few different desktops and the app
  has, from where you're sitting, appeared on all of them. It now only shows a
  window that isn't already up; if one is open elsewhere, activating takes you
  to it, the way every other Mac app behaves.
- **The glass slider now reaches the cards.** Cards were the one surface it
  never touched: the rail and the content surface thinned out around them while
  every card stayed a flat opaque slab, so at 100% the app read as translucent
  chrome wrapped around solid blocks — white ones in light mode, espresso ones
  in dark — and the two halves looked like different products. Cards now thin
  and pick up the desktop with everything else, down to a higher floor than the
  surface (they carry the text you actually read). A card nested in another card
  steps back rather than compounding the two alphas, and modals stay solid:
  what's behind a dialog is app UI, not a blurred desktop, and showing it
  through is noise rather than depth. At slider 0 every card is fully opaque, as
  before.
- **Light-mode cards are cream, not white.** `--dd-panel` was brighter than the
  page *and* less saturated than it, which is the recipe for a card that reads
  as a white patch dropped onto a warm surface rather than as the same material
  lifted one step. It keeps its elevation and now carries the page's own chroma
  up with it.
- **The phone-card volume slider dismisses itself.** It opens *inside* the
  phone card, over the battery and lock buttons, and previously stayed there
  until you happened to click elsewhere — a transient control you have to
  dismiss by hand isn't transient. It now closes 2.6s after you stop using it
  (the timer restarts on every drag and while the pointer is over it), and on
  Escape as well as click-away.
- **Popovers stopped rendering in the wrong place.** A `position: relative`
  added for the new glass rim was overriding Tailwind's `absolute` on every
  popover that uses `.glass-heavy`, dropping them out of their float and into
  the document flow on top of their neighbours. Plain rules in `index.css` are
  unlayered and unlayered beats layered, so it silently outranked the utility;
  it now lives in `@layer components`, below the utilities.
- **A Spaces probe, for the next time this class of bug shows up.** Run the
  binary with `DROIDDOCK_DEBUG_SPACES=1` and it logs every change in the main
  window's `collectionBehavior` and `isOnActiveSpace`, with a heartbeat so a
  dead sampler can't be mistaken for a quiet one. "The window follows me" has
  several causes that look identical in a screen recording; this tells them
  apart in one pass. Off unless the variable is set.
- **A dark app on a light Mac is dark all the way down.** The window's vibrancy
  material is an `NSVisualEffectView` behind the webview, and it rendered
  according to *macOS's* appearance rather than the app's. Run the Mac in light
  mode, set DroidDock to dark, push the glass slider up, and the two disagreed
  visibly: translucent surfaces washed out to grey-white against a light
  material while opaque cards stayed espresso, so one window showed two
  unrelated palettes. The window now takes the app's theme (`system` still
  follows macOS).
- **Glass at full strength no longer washes the UI out white, and light mode
  has a visible material at last.** Two bugs with one root: the material had no
  luminance anchoring, so a bright desktop behind a thinned surface came
  straight through and turned espresso into grey haze, and the highlight was a
  full-height white gradient — a film, not an edge. The backdrop now carries a
  theme-aware `brightness()` that scales with the slider, and the specular is a
  layered inset rim (tight edge, inner glow, three soft depth shadows) confined
  to where light actually lands. Light mode's glass tint went from 5% ink —
  invisible on cream — to a real tint with its own rim and hairline.
- **"Let the phone control this Mac" stopped working after updating.** DroidDock
  is ad-hoc signed, so macOS records the Accessibility permission against a hash
  of the exact app binary — which changes with every release. After an update
  the app still appears ticked in Privacy & Security → Accessibility while the
  system silently discards its input. The warning now says so, and **Reset
  permission** clears the dead entry and re-asks for the copy you're running.

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

- The macOS app is **ad-hoc signed and not notarized** — notarization needs a paid
  Apple Developer account. First launch is right-click → **Open**, or clear the
  quarantine flag with
  `xattr -dr com.apple.quarantine /Applications/DroidDock.app`.
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
