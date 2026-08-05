# AirSync mobile → DroidDock Android: gap analysis & plan

**Sources:** `future update /airSyncMobile.mp4` (61s screen recording, 31 frames
sampled) and `future update /airsyncmobile.pdf` (11 screenshots). Subject:
`droiddock-android/` + `droiddock-tauri/app/`, surveyed 2026-08-05.

This is the *mobile* counterpart to `AIRSYNC_GAP_ANALYSIS.md`, which covered the
Mac client. Where that file said a thing was built, it was re-checked here
rather than trusted.

---

## 1. What the reference actually shows

Reading both sources together, AirSync's Android app is four tabs plus a
persistent player:

| Screen | Contents |
|---|---|
| **Device (home)** | Disconnected banner · "Last Connected Device" card (laptop art, name, PLUS badge, "Last seen 4m ago") · **Quick Connect** · "Reconnect — configure auto reconnect" · **Available Devices** (toggle + discovered list: name, `IP:port`, `mDNS` tag) · "Manual Connection" expander |
| **Device (connected)** | Same card, now with an `IP:port` chip, **"Syncing"** status, **Disconnect** button, and an action row: **Lock · Screensaver · ⚙ · ☀** |
| **Clipboard** | "Nothing shared yet" · **History** toggle · "Clipboard will clear after this session" · "Type a message or drag text here…" + send · trash in the nav |
| **Settings** | Permissions · Help and guides · **two "Add tile" buttons** (Connection Tile, Clipboard Tile) · Connection block (My Android, Local IP, editable **Device Name**) · **Expand networking — allow connecting to device in VPN (Tailscale)** · groups: App, Notifications, Clipboard, Media and Files, Integrations, Widget |
| **Mini-player** | Docked above the nav on *every* tab: "Nothing Playing / from your Mac" + play; expands to prev/play/next + a volume slider |

Settings sub-screens, verbatim from the PDF:

- **App** — Default tab picker (4 tabs + "Dynamic") · Use Blur · Pitch Black Theme · Notify on crash
- **Notifications** — Notification Sync · Select apps
- **Clipboard** — Clipboard Sync · Continue browsing (prompt to open shared links) · Keep previous link
- **Media and Files** — Send now playing · Show Mac Media Controls · Quick Share · File Access (mount storage in Finder)

Visual language: warm cream (`#F7E7DE`-ish) page, near-white cards, saturated
brown (`#7A4A17`-ish) primary, peach accents, ~24dp card radii, pill-shaped
floating bottom nav with a detached context button on the right.

---

## 2. Gap table

`✅` already built · `◐` partly built · `❌` missing

| # | Feature | State | Notes |
|---|---|---|---|
| **Theme** |
| T1 | Light / Dark / System theme | ❌ | `MainActivity.kt` hardcodes one dark palette as ~13 top-level `val`s + `darkColorScheme()`. No mode setting anywhere. |
| T2 | Light palette matching the reference | ❌ | Nothing to match it with yet. |
| **Device management** |
| D1 | Remember previously-connected devices | ❌ | `Prefs` stores exactly one `Pairing` (`ips`/`port`/`token`/`macName`). Re-pairing overwrites it. |
| D2 | "Last seen" timestamp | ❌ | Never recorded. |
| D3 | Quick Connect | ◐ | The connect loop auto-connects, but there is no explicit "connect now to this device" affordance. |
| D4 | Available Devices list on the phone | ◐ | `MdnsDiscovery` + `discoverViaBroadcast` find the Mac, but only *inside* the connect loop. Results are never surfaced as a list. |
| D5 | Connection status + Disconnect button | ◐ | Status exists on the Home hero card. There is **no** disconnect — only "Forget this Mac", which unpairs. |
| D6 | Manual connection | ✅ | `ManualPairDialog`. |
| **Remote Mac control** |
| R1 | Lock the Mac | ❌ | Not in `mac_remote.rs`'s allow-list. |
| R2 | Screensaver | ❌ | Same. |
| R3 | Brightness | ❌ | Same. |
| R4 | Volume | ◐ | Relative only — `volup`/`voldown`/`mute` as HID keys. No absolute level, no read-back, so no slider. |
| R5 | Mouse / keys / text | ✅ | `MacRemoteTab` + `mac_remote.rs`. |
| **Now Playing** |
| N1 | Media transport control | ✅ | `remote`→`media` with a closed allow-list, posted as `NX_KEYTYPE_*` keys. Buttons live in `MacRemoteTab`. |
| N2 | **See what's playing on the Mac** | ❌ | No metadata is sent Mac→phone at all. See §3 — this is the one genuinely hard item. |
| N3 | Persistent mini-player across tabs | ❌ | Controls are buried in the Remote tab. |
| **Clipboard** |
| C1 | Phone↔Mac clipboard | ✅ | Both directions, `clipboardSync`-gated. Works; **do not rebuild.** |
| C2 | Clipboard tab with history | ❌ | No history, no tab. Sending is a button in Files. |
| C3 | Compose-and-send box | ◐ | Share sheet / "Send to Mac" / QS tile exist; no in-app compose field. |
| C4 | "Continue browsing" / "Keep previous link" | ❌ | No link handling. |
| **Tiles** |
| Q1 | Clipboard quick-settings tile | ✅ | `ClipTileService`. |
| Q2 | Connection tile | ❌ | Not built. |
| Q3 | "Add tile" buttons in settings | ❌ | Android 13+ has `requestAddTileService()`; unused. |
| **Networking** |
| W1 | Tailscale / VPN toggle | ❌ | Nothing. Connect loop tries stored IPs → broadcast → mDNS, all LAN-only. |
| **Settings** |
| S1 | Editable device name | ❌ | Phone name is `"$MANUFACTURER $MODEL"`, not editable. |
| S2 | Local IP display | ❌ | Not shown. |
| S3 | Per-app notification muting | ✅ | Mac-side. |
| S4 | Notification sync toggle | ✅ | Mac-side setting. |
| S5 | Default-tab picker, blur, pitch-black, notify-on-crash | ❌ | Cosmetic; low priority. |

---

## 3. Two honest constraints, before the plan

**Now Playing metadata (N2) cannot be done the way AirSync did it.** Reading the
Mac's now-playing info requires the private `MediaRemote` framework, and
**macOS 15.4 gated `MRMediaRemoteGetNowPlayingInfo` behind an entitlement Apple
does not issue to third parties** — it broke a lot of apps. `AIRSYNC_GAP_ANALYSIS.md`
already recorded this as "not attempted."

What *is* publicly available: **Music.app and Spotify both expose track name,
artist, album and player state over AppleScript.** So the mini-player can show
real metadata for those two apps and fall back to "Nothing Playing / from your
Mac" — which is exactly the reference's own empty state — for anything else
(browsers, VLC, podcast apps). Control stays universal, because HID media keys
drive whatever owns the session.

I'm proposing that rather than a private-framework hack that Apple can and did
break. Say the word if you'd rather I attempt the private path anyway.

**"Tailscale integration" means interoperating with it, not bundling it.** The
reference's own wording is *"Expand networking — allow connecting to device in
VPN (Tailscale)"*. Concretely: stop assuming the Mac is on the LAN — accept and
advertise Tailscale's `100.64.0.0/10` CGNAT addresses, keep trying them when
broadcast and mDNS (neither of which crosses a tailnet) find nothing, and put it
behind a toggle. No VPN code ships in either app; the user runs Tailscale
themselves. That is a genuinely useful feature — it's what makes the link work
off your home network — and it's a few hundred lines, not a networking stack.

---

## 4. Prioritised plan

Ordered by value-per-risk, not by the reference's screen order.

> **Status 2026-08-05: Phases 1–4 built, none verified on hardware.**
>
> **Phase 3** — `mac_media.rs` polls Music/Spotify over AppleScript and, behind
> a second toggle, the active browser tab *when its host is on a media
> allow-list* (YouTube, Spotify web, Twitch, …). A bank or mail tab is never
> read; seven unit tests cover the parsing, the suffix/prefix cleanup and the
> lookalike-host cases. Pushed as `mac-media` behind a `macmedia` cap, rendered
> as a Now Playing card on the phone's Home. **The card is shown whenever the
> Mac accepts remote control, not only when a title is known** — transport keys
> are real HID media keys and drive every Mac app, so hiding them when the label
> is unavailable would remove working controls.
>
> **Phase 4** — "Expand networking" toggle. Local discovery is link-local and
> can't cross a tailnet, so with this on the loop skips both probes (~4.5s a
> round) and keeps dialling stored addresses. It also fixed a real bug: the old
> `ips.take(2)`/`take(4)` truncation could silently evict the `100.x` address,
> which is the one nothing can rediscover — `trimAddresses` now protects it.
> The Mac already advertised its Tailscale address (`get_if_addrs` takes every
> non-loopback v4), so no Mac-side change was needed.
>
> **Accessibility** — the Settings hint claimed macOS "will ask for
> Accessibility permission the first time". It never did; nothing in the app
> called an API that prompts, so remote control silently no-opped. Added
> `AXIsProcessTrusted` + a warning row with a button that deep-links to the
> exact Settings pane, polled so it clears when you come back.
>
> **Final pass (same day)** — the remainder of Phases 3–4:
>
> - **Clipboard tab** (C2/C3) — session history of traffic in both directions,
>   a History toggle, per-entry copy-back, clear, and a compose box. Memory-only
>   and capped at 50, matching the reference's "clears after the session":
>   everything copied on either device passes through it, so persisting would
>   leave a plaintext log of passwords and OTPs in app storage.
> - **Connection tile** (Q2) — the second of the reference's pair, driving
>   `disconnect`/`quickConnect` rather than a private notion of "off".
> - **Add tile buttons** (Q3) — `requestAddTileService` on API 33+, with an
>   explanatory row below that instead of a dead button.
> - **Editable device name** (S1) — overrides `MANUFACTURER MODEL` in `hello`,
>   committed on focus-loss rather than per keystroke.
> - **Local IP** (S2) — enumerated from the interfaces rather than
>   `WifiManager`, which needs a location permission and can't see a tailnet
>   address; the tailnet one is exactly what's worth seeing when debugging
>   "expand networking".
>
> **Closing pass** — S5 and the test debt:
>
> - **Android now has a test harness.** JUnit 4 + `src/test`, nine tests over
>   the address-book rules (`isTailnetAddress`, `trimAddresses`,
>   `KnownDevice.toPairing`) — the logic that decides whether the phone can
>   still reach the Mac, previously verified only out-of-band. Mutation-checked:
>   reverting `trimAddresses` to the old `take(max)` fails two of them. Wired
>   into the release workflow, which previously ran no tests at all.
> - **Opening tab** picker, with the reference's "Dynamic" as **Auto** — Connect
>   until paired, Home after. Resolved eagerly at first composition so the first
>   frame is already right.
> - **Pitch black** — an OLED variant of the dark palette. `dim` is lightened
>   from the inherited value (3.62:1 on a chip → 4.79:1); the shipped
>   `DarkPalette` is deliberately left alone, since changing it would alter
>   every existing install.
> - **Notify on crash** — names the exception and the first `com.droiddock.app`
>   frame in a notification, then **delegates to the handler it replaced** so the
>   process still dies and the system still records it. It reports crashes; it
>   never swallows them.
>
> **Deliberately not built:** "Use Blur" (S5). Progressive blur needs
> `RenderEffect` (API 31+) and a second code path below that, for a purely
> decorative effect that fights DroidDock's flat card design rather than
> matching it. Cosmetic-only and reversible if you disagree.
>
> Phases 1 and 2, from the earlier pass:
>
> - **Theme** — `Theme.kt` (`DroidColors` + light/dark palettes +
>   `LocalDroidColors`), `ThemeMode` in `Prefs`, a segmented picker in Settings,
>   and system-bar polarity that follows the theme. The 14 palette names in
>   `MainActivity.kt` became `@Composable get()` accessors, so ~200 call sites
>   were left untouched and the compiler proved none of them read a colour
>   outside a composable. Light palette measured: worst pair 4.58:1.
> - **Device management** — `KnownDevice` list in `Prefs` (capped at 8, keyed by
>   token), `lastSeenAt` stamped on link-up with the working IP promoted, a
>   "Last Connected Device" card with Quick Connect / Disconnect / switch, and
>   an "Available Devices" list on Connect fed by a new `MdnsDiscovery.browse`.
> - **Disconnect reuses Pause** rather than adding a second teardown path, and
>   QR / manual / switch-device all funnel through one `ConnectionManager.onPaired`.
> - **Phase 2 controls** — `lock` (⌘⌃Q), `screensaver` (`ScreenSaverEngine`),
>   `brightness` (NX_KEYTYPE_BRIGHTNESS_UP/DOWN), `volume_set` (osascript), all
>   added to `mac_remote.rs`'s allow-list as parameterless-or-clamped verbs. The
>   Mac's current volume rides the existing `mac-info` push instead of getting a
>   new message type; the phone shows a `MacControlsCard` on Home, gated on the
>   Mac advertising `remote`.
>
> Deliberately **not** done: absolute brightness (needs private CoreDisplay).
> Discovery yields addresses but never tokens, so an unknown Mac in Available
> Devices still routes to QR/manual pairing — stated in the UI rather than hidden.

### Phase 1 — Theme + device management ← *done, unverified*
1. **Theme system.** Extract the palette into `ui/Theme.kt`: a `DroidColors`
   holder, light + dark instances, `LocalDroidColors`, and a `ThemeMode`
   (`LIGHT`/`DARK`/`SYSTEM`, default `SYSTEM`) in `Prefs`. Migrate every
   hardcoded colour reference in `MainActivity.kt`. Light palette drawn from the
   reference screenshots, keeping DroidDock's amber identity rather than
   copying AirSync's brown.
2. **Theme picker** in Settings — three-way segmented control.
3. **Known-devices store.** `Prefs` grows a JSON list: name, ips, port, token,
   `lastSeenAt`. The single-`Pairing` API stays as a facade over "the active
   device" so nothing downstream breaks.
4. **Home rebuild** — Last Connected Device card, last-seen, Quick Connect,
   Available Devices (surfacing what discovery already finds), Disconnect.

### Phase 2 — Remote Mac controls
Add to `mac_remote.rs`'s allow-list, all public APIs:
`lock` (CGEvent ⌘⌃Q) · `screensaver` (`open -a ScreenSaverEngine`) ·
`brightness_up`/`brightness_down` (`NX_KEYTYPE_BRIGHTNESS_UP/DOWN`, same HID
path as the media keys) · `volume_set`/`volume_get` (`osascript set volume
output volume` — public, gives the absolute slider R4 needs).
Android: an action row on Home matching the reference's Lock · Screensaver · ⚙ · ☀.

### Phase 3 — Now Playing, clipboard, tiles
`mac_media.rs` polling Music/Spotify over AppleScript → `mac-media` push behind
a `macmedia` cap · persistent mini-player · Clipboard tab with session history
+ compose box · Connection tile + `requestAddTileService()` buttons.

### Phase 4 — Tailscale, settings, polish
`expandNetworking` toggle + CGNAT-aware discovery · editable device name · local
IP · remaining toggles · polish pass.

---

## 5. Scope note

Phase 1 alone rewrites the Android app's entire colour layer and its home
screen. Phases 2–4 each touch both codebases and the wire protocol. Every new
message type stays **capability-gated in both directions**, per the PRD's
compatibility mandate, so an un-updated phone or Mac degrades silently instead
of breaking — the same rule `macinfo` and `remote` already follow.
