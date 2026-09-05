# Changelog

Release notes are pulled from this file by
[`.github/workflows/release.yml`](.github/workflows/release.yml): the section
whose heading matches the pushed tag becomes the GitHub Release body. **Add a
`## vX.Y.Z` section before tagging** — a tag with no matching section falls back
to a generic body.

---

## v2.3.0

### Added

- **Answer, decline and hang up calls from the Mac.** The incoming-call alert
  used to be a caller-ID card you could do nothing with — in-call control was
  ADB-only, so over Wi-Fi you got told who was ringing and had to reach for the
  phone anyway. Answer, decline, hang up, mute and speaker now work over the
  plain app link, and an answered call keeps a Mac overlay with a call timer
  instead of the alert simply vanishing.

  Three things are worth knowing. The buttons appear only once the phone
  actually holds the Calls permission — a dead Answer button on a ringing phone
  is worse than none, so it is hidden rather than greyed. Mute and speaker show
  the state the phone **read back** after the change, not the state asked for,
  because the dialer that owns the call can put the audio route back and
  sometimes does. And the keypad stays ADB-only: playing DTMF into a live call
  is reachable only from the device's default dialer, which a bridge app has no
  business claiming.

- **A setup check that says what is missing before you find out the hard way.**
  Nearly everything DroidDock needs and hasn't got fails *silently*: the mirror
  streams video while every tap is discarded, the Notifications tab looks
  exactly like a quiet phone, remote control no-ops. **Settings → System →
  Setup check** lists every grant on both devices, what breaks without each, and
  a Fix button that opens the exact settings screen. Missing permissions also
  raise a strip under the title bar when a phone connects.

  Two honest notes. macOS gives no readable answer for whether it will let
  DroidDock post banners — the API available to a desktop app returns "granted"
  unconditionally — so notifications are an *informational* row that says so
  rather than a green tick that means nothing. And because Android restricts
  starting activities from the background, a Fix on a phone without "Display
  over other apps" leaves you a tappable notification instead; the Mac says
  which of the two happened rather than claiming success.

- **Ring my phone**, from the phone card. It plays on the **alarm** stream,
  which is the only route that survives silent mode, raises the alarm volume for
  the duration and puts it back afterwards. It stops from the Mac, from a Stop
  button on its own notification, or on its own after a minute — a remote
  control that can start an unstoppable noise on a device in someone else's bag
  is a worse thing to own than the feature is a good one.

- **Save a still or a recording of the mirror.** Two buttons in the mirror
  window; stills land in `~/Pictures/DroidDock`, recordings in
  `~/Movies/DroidDock`, named the way macOS names screenshots. Recordings are
  video-only and the button says so: phone audio is played by the main window so
  it outlives the pop-out, which means the mirror's canvas has no audio track to
  record.

- **Images on the clipboard.** Copy a picture on the Mac and it is on the
  phone's clipboard, ready to paste. The phone can send one back through the
  Quick Settings tile, the widget or "Send to Mac".

  That asymmetry is Android's, not a shortcut: it refuses background clipboard
  reads outright, and the accessibility trick that rescues *text* works because
  the accessibility event carries the copied string — there is no equivalent
  carrying pixels. On the Mac, images that are only on the pasteboard as TIFF
  (Preview and Safari copies) are re-encoded to PNG first, because Android
  cannot decode TIFF at all. Text still wins whenever both are present: a Finder
  file copy carries a filename *and* an icon, so an image being present is not
  evidence you copied a picture.

- **The Finder mount can now be writable.** **Settings → Mac files → Allow
  writing to the phone** turns the phone volume read-write, so ⌘S in any Mac app
  saves straight onto a file on the phone — the one thing a file browser inside
  our own window can never do.

  It is **off by default and stays that way**, for the reason it was read-only
  to begin with: a bug on the write path damages files on your phone, where a
  bug on the read path shows a wrong listing.

  Two behaviours worth knowing. Finder's own droppings — `.DS_Store` in every
  folder it merely *looks at*, `._name` sidecars beside files it saves — are
  accepted and thrown away rather than written to the phone, so browsing a
  writable volume no longer dirties it. And moving a file *between* folders is
  refused rather than emulated: the emulation is copy-then-delete, which on a
  failure halfway leaves you with two copies or none. Renaming in place works.

### Changed

- **Both apps now speak sixteen languages.** **Settings → Appearance →
  Language** switches between them or follows the system: English, العربية,
  বাংলা, Deutsch, Español, Français, हिन्दी, Bahasa Indonesia, Italiano, 日本語,
  한국어, Português, Русский, Türkçe, Tiếng Việt, 简体中文 and 繁體中文.

  Every user-facing string goes through a lookup rather than being baked into
  the code — 438 on the Mac, 120 on the phone, 543 unique across both. Strings
  are keyed on the **English text itself**, so a gap in a catalog shows correct
  English rather than a resource id, and adding a language stays one file plus
  one line with no code changes at all — see
  `droiddock-tauri/app/src/locales/README.md` and `I18n.kt`.

  `npm run i18n:check` guards the two things a coverage count cannot see: a
  translation that dropped a `{n}` placeholder (which would print the braces on
  screen), or one that rephrased `Mac → phone` into prose and lost the
  direction. It also reports orphans — translations the UI no longer asks for,
  which is what edited English copy leaves behind.

  **Arabic mirrors the layout.** The Mac's directional styling was converted to
  logical properties, so the whole interface flips from one `dir` attribute
  rather than a second stylesheet; Android provides `LocalLayoutDirection` from
  the app's own language, because DroidDock's language is its own setting and
  Compose would otherwise only follow the system's.

  These translations have not been reviewed by native speakers. They are
  checked for completeness, placeholders and terminology, not for tone.

  Strings are keyed on the English text itself, so a missing translation shows
  correct English rather than a resource id. The cost of that choice is that
  editing English copy orphans its translations — `npm run i18n:check` lists
  orphans and gaps so it shows up as a diff instead of a silent gap.

- **A Mac-placed call now resolves itself.** Dialling a contact from the Mac
  left the overlay on "Calling…" until it was dismissed by hand, because the
  phone only reported call state for calls it had announced itself. It now
  reports the ones the Mac started too, so the overlay follows the call and
  clears when it ends.

### Fixed

- **The phone's Home screen no longer has two dead buttons.** Mirror and Camera
  sat under the Mac controls and did nothing when tapped: both asked for a
  "mirror" tab that had been folded into Control when the bottom bar was
  regrouped to five destinations, so a tap matched no screen and drew an empty
  one. Rather than repair a shortcut to somewhere the nav bar already goes in
  one tap, the pair is replaced by **Pair Mac**, which opens the same Connect
  screen as Settings' "Pair or change Mac" — the one screen the nav bar
  deliberately does not carry, and the one you want when the Mac you are paired
  to is the wrong one.

- **"App isn't compatible with your phone" on an in-app update now says what it
  actually is.** Downloading the update and tapping Install could hand you that
  system dialog on a phone that was plainly running the app already, which
  sends you looking for an Android-version or CPU problem that does not exist.
  The real cause is a signing key: a copy installed over USB from a computer
  carries that machine's debug certificate, releases carry the CI keystore, and
  Android will only let an APK replace one signed by the **same** key —
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, rendered as "incompatible".

  The update row now compares the downloaded APK's certificate against the
  running app's as soon as the download lands, and where they differ it stops
  offering an Install button that cannot work: it explains that this copy came
  from a computer, that uninstalling and reinstalling by hand is the way
  through, and that pairing will have to be set up again. Certificates are
  compared rather than assumed against a hardcoded fingerprint, so rotating the
  release keystore does not silently turn this into a false alarm.

---

## v2.2.0

### Added

- **Phone audio now streams to the Mac over Wi-Fi**, not just over the ADB
  mirror. It rides the MediaProjection the screen mirror already holds, so it
  costs no extra consent tap, and it starts with the mirror when **Settings →
  Phone audio** is on. Two things are worth knowing up front: it needs the
  microphone permission, because Android routes captured playback through the
  same API even though no microphone is opened; and apps that opt out of
  playback capture — most paid music and video apps do — come through silent.
  That is a platform restriction, not a bug in the link.
- **H.265 on the Wi-Fi mirror.** Previously only the ADB path could use it.
  Roughly half the bandwidth at the same quality, and it degrades on its own
  from both ends: the phone falls back to H.264 if it has no HEVC encoder, and
  the Mac never asks for H.265 unless its own decoder says it can play it. So
  turning it on cannot produce a stream that fails to start.
- **Quick Share: receive files from any nearby device.** Turn on **Settings →
  Mac files → Quick Share** and this Mac appears in the Quick Share sheet on
  any nearby Android, ChromeOS or Windows device — with nothing installed on
  the sender. Files land in Downloads. Every transfer has to be accepted here,
  and the four-digit code shown must match the sender's; that code comes out of
  the key exchange, so it only matches if the connection really is with the
  device in front of you.

  It is **off by default, deliberately**. Quick Share's "contacts only"
  visibility depends on Google account access a third-party app cannot have, so
  the only mode available makes this Mac visible to everyone on the network for
  as long as it is on. That is your call to make, not a default to inherit.

- **Clicking an app can now open it on the Mac instead of the phone.** The Apps
  grid could always do both, but opening an app in its own Mac window was hidden
  behind a double-click nothing announced. There is now a **Phone / Mac** switch
  in the Apps header — mirrored by **Settings → Mirroring → Open apps on this
  Mac** — that decides what a plain click does, and holding **⌥ Option** always
  does the other one. With no ADB device connected the Mac route is unreachable,
  so a click falls back to launching on the phone and says so, rather than
  leaving the tile inert because a setting is on.

  Sending *from* the Mac is not included, and is not an oversight: Android only
  becomes discoverable after it hears a Bluetooth advertisement whose contents
  macOS provides no way to set.

### Performance

- **An unpaired Mac is now genuinely idle.** Four background loops — clipboard,
  link quality, now-playing and Mac info — each woke on their own timer and
  immediately did nothing whenever no phone was linked, together a couple of CPU
  wake-ups a second, forever. They now sleep on the link itself and wake when a
  phone actually arrives, which also means the first clipboard poll and status
  push after connecting happen immediately instead of up to a tick later.

- **The phone stops sweeping the radio when there is nothing to reach.** The
  Android connect loop ran a full probe round — up to four TCP dials, a UDP
  broadcast and a live mDNS browse — on a fixed backoff around the clock, with
  no connectivity check at all: it did that in airplane mode and on cellular.
  It now parks until Android reports a network.

- **Reconnecting after the Mac comes back is close to immediate.** Two things
  made it take up to ~35 seconds: the backoff could not be interrupted, so Wi-Fi
  returning did nothing until it elapsed; and stored addresses were dialled one
  at a time at four seconds each, most of them stale. The backoff now wakes on
  the network becoming available, and all stored addresses are probed in
  parallel so the whole round costs about one timeout.

- **The mirror is lower-latency and much cheaper to encode.** The encoder was
  forcing a full keyframe every second — even on a completely static screen —
  which dominated both the bitrate and the encode cost and arrived as a visible
  hitch once a second. Keyframes are now three seconds apart, safe because this
  is a TCP link where frames are not lost and both cases that genuinely need a
  fresh one ask for it explicitly. Added realtime encoder priority and
  low-latency hints, and dropped several whole-frame memory copies per frame on
  both sides.

- **A weak Wi-Fi link no longer puts the mirror seconds behind.** Video was
  queued for sending unconditionally, so a dip meant frames piling up in memory
  with the picture falling further behind reality the whole time. Past a
  backlog threshold, predicted frames are now dropped and one keyframe
  requested, so the mirror catches up to the present instead of replaying the
  past.

- **Playing music with no Mac linked costs nothing again.** The phone was doing
  a package-manager lookup, two audio-system calls and a JSON build on the main
  thread every second to produce a message that was then discarded because
  nothing was connected. Same for the battery broadcast handler, and for a
  preference the accessibility service re-read on every cursor movement while
  typing in any app.

- **The Mac app's UI no longer redraws on every now-playing tick.** The phone
  pushes now-playing once a second while music plays, and each push re-rendered
  the whole window — rail, phone card and whichever list was open. It now
  reaches only the two components that display it. File-transfer progress was
  likewise emitted per 256 KiB chunk (~80 times a second, each redrawing the
  entire file list) and is now rate-limited; opening the Apps tab fired one
  simultaneous request per installed app and is now capped.

### Fixed

- **Notifications no longer lose a reply you are part-way through typing.** Each
  row was keyed by its position in the list, and new notifications arrive at the
  top — so a single arrival changed every key and made the whole list unmount
  and rebuild, taking any in-progress inline reply with it.

- **A locally built APK no longer needs an uninstall.** Installing one was
  believed to mean losing pairing and every granted permission. It doesn't —
  the debug key already matches, so `adb install -r` is an ordinary in-place
  upgrade. Only the accessibility service has to be re-enabled afterwards,
  which Android drops on any app update.

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
