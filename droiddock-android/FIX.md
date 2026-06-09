# Fix — "paired but stuck connecting" (Wi‑Fi clipboard never links)

## Symptom

The phone scanned the QR and showed **"Paired"**, but the status stayed on
**"connecting to 192.168.0.108…"** forever and never turned green / "linked".
On the Mac side, the DroidDock server logged **zero** incoming connections.

## Root cause

The companion app connects to the Mac over a **plain WebSocket** (`ws://…`, cleartext),
but it targets `targetSdk = 35`. Since **Android 9 (API 28)**, cleartext network
traffic is **blocked by default**.

So OkHttp refused every connection attempt with *"Cleartext HTTP traffic … not permitted"*
and fired `onFailure` **before a TCP connection ever reached the Mac**. That produced the
exact symptoms:

- "Paired" — because scanning the QR only stores data locally; no network needed. ✅
- "connecting to 192.168.0.108…" forever — the connect loop set that label, instantly
  failed, backed off, and retried, never advancing to "linked".
- Mac sees nothing — the packets never left the phone's network stack.

It was **not** the IP, the token, the firewall, or the Mac app — all of those were correct.

## The fix

One line in [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml),
on the `<application>` tag:

```xml
<application
    android:allowBackup="true"
    android:icon="@drawable/ic_launcher"
    android:label="@string/app_name"
    android:usesCleartextTraffic="true"   <!-- ADDED: allow ws:// to the Mac over the LAN -->
    android:theme="@android:style/Theme.Material.NoActionBar">
```

This permits the cleartext `ws://` connection used to talk to the Mac on the local network.

### Rebuild & install

```bash
cd droiddock-android
./gradlew installDebug      # builds and pushes to the connected phone via adb
```

After reinstalling, the phone connected within ~2 seconds of opening the app.

## Verification

- The TCP connection to the Mac (`192.168.0.108:48484`) stayed **ESTABLISHED past the
  5‑second auth window** — proof the token handshake succeeded (the Mac drops any socket
  that doesn't send a valid `hello` within 5s).
- Copying text on the Mac now lands on the phone ~1s later.

## Related change on the Mac side (diagnostics)

While tracking this down, the Mac app's `src/main/wifi.js` was updated to **log every
incoming connection** and surface **wrong‑token** / **timed‑out** attempts. Previously the
server dropped bad connections silently, which is exactly why this problem was invisible.

## Notes

- Clipboard data travels as **plain WebSocket over the LAN** (no TLS). Fine for a trusted
  home network; TLS is on the Phase 5 roadmap. `usesCleartextTraffic="true"` is the
  pragmatic enabler for that design.
- If you ever want to scope it more tightly, replace the flag with a
  `network-security-config` that permits cleartext only for private IP ranges.
