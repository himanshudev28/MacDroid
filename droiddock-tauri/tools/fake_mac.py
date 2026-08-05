#!/usr/bin/env python3
"""Minimal stand-in for the Mac's WebSocket server, so the phone's screen-control
path can be exercised end to end without clicking the real UI.

Accepts the phone's `hello`, answers `welcome`, then sends whatever control
messages were passed on the command line and prints everything the phone says
back (so a `control-unavailable` reply is visible)."""
import base64, hashlib, json, socket, struct, sys, threading, time

TOKEN = "acad86c1-6041-408c-bce7-2495dd338711"
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
PORT = 48484

# Control messages to fire once the phone is authenticated: JSON per argv slot.
SCRIPT = [json.loads(a) for a in sys.argv[1:]] or [{"type": "mirror-key", "key": "home"}]


def send_text(sock, payload: str):
    data = payload.encode()
    n = len(data)
    if n < 126:
        hdr = struct.pack("!BB", 0x81, n)
    elif n < 65536:
        hdr = struct.pack("!BBH", 0x81, 126, n)
    else:
        hdr = struct.pack("!BBQ", 0x81, 127, n)
    sock.sendall(hdr + data)


def recv_frames(sock):
    """Yield decoded text payloads from masked client frames."""
    buf = b""
    while True:
        try:
            chunk = sock.recv(65536)
        except OSError:
            return
        if not chunk:
            return
        buf += chunk
        while True:
            if len(buf) < 2:
                break
            b1, b2 = buf[0], buf[1]
            opcode = b1 & 0x0F
            masked = b2 & 0x80
            ln = b2 & 0x7F
            off = 2
            if ln == 126:
                if len(buf) < 4:
                    break
                ln = struct.unpack("!H", buf[2:4])[0]; off = 4
            elif ln == 127:
                if len(buf) < 10:
                    break
                ln = struct.unpack("!Q", buf[2:10])[0]; off = 10
            need = off + (4 if masked else 0) + ln
            if len(buf) < need:
                break
            if masked:
                mask = buf[off:off + 4]; off += 4
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(buf[off:off + ln]))
            else:
                payload = buf[off:off + ln]
            buf = buf[off + ln:]
            if opcode == 8:
                return
            if opcode == 1:
                yield payload.decode(errors="replace")


srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", PORT))
srv.listen(5)
print(f"[fake-mac] listening on 0.0.0.0:{PORT}", flush=True)
srv.settimeout(60)
conn, peer = srv.accept()
print(f"[fake-mac] TCP from {peer}", flush=True)

# HTTP upgrade
req = b""
while b"\r\n\r\n" not in req:
    req += conn.recv(4096)
key = ""
for line in req.decode(errors="replace").split("\r\n"):
    if line.lower().startswith("sec-websocket-key:"):
        key = line.split(":", 1)[1].strip()
accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
conn.sendall(
    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
     f"Connection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n").encode()
)
print("[fake-mac] upgraded", flush=True)

authed = False


def pump():
    global authed
    for text in recv_frames(conn):
        try:
            msg = json.loads(text)
        except Exception:
            continue
        t = msg.get("type")
        if t == "hello":
            ok = msg.get("token") == TOKEN
            print(f"[fake-mac] hello from {msg.get('name')!r} token_ok={ok}", flush=True)
            if not ok:
                return
            send_text(conn, json.dumps({"type": "welcome", "name": "fake-mac", "caps": []}))
            authed = True
        elif t in ("control-unavailable", "reply-result"):
            print(f"[fake-mac] <<< {text}", flush=True)
        elif t not in ("device-info", "media", "notification", "pong"):
            print(f"[fake-mac] <<< {t}", flush=True)


threading.Thread(target=pump, daemon=True).start()

for _ in range(200):
    if authed:
        break
    time.sleep(0.05)
if not authed:
    print("[fake-mac] phone never authenticated", flush=True)
    sys.exit(1)

time.sleep(1.0)
for msg in SCRIPT:
    print(f"[fake-mac] >>> {json.dumps(msg)}", flush=True)
    send_text(conn, json.dumps(msg))
    time.sleep(2.5)

time.sleep(1.5)
print("[fake-mac] done", flush=True)
