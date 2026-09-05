import { useEffect, useRef, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import Icon from "./Icon";
import { t, useT } from "../lib/i18n";
import {
  mirrorAttach,
  mirrorInput,
  mirrorSaveCapture,
  mirrorSetOnTop,
  onMirrorStarted,
  onMirrorStopped,
  onMirrorError,
  type MirrorStarted,
} from "../lib/bridge";

/// Pop-out, phone-shaped mirror window (the Tauri "mirror" window, `#mirror`
/// route). Decodes the H.264 stream with WebCodecs (Spike A's verdict — WKWebView
/// handles the real captured stream with 0 decode errors), paints to a canvas,
/// and forwards taps/swipes/nav back to the phone. Aspect-ratio + sizing are
/// locked natively from the Rust side on `mirror-started` (see `mirror.rs`).
/// Frames arrive as raw bytes over the `mirrorAttach` IPC channel; the attach
/// call also returns a `mirror-started` that beat this window's load.

export default function MirrorWindow() {
  // Its own window, so it needs its own subscription — a language change in the
  // main window has to repaint this one too. See App.tsx.
  useT();

  const canvasRef = useRef<HTMLCanvasElement>(null);
  // The 2D context, cached against the canvas it came from. `getContext` only
  // honours its options on the *first* call for an element, so the options
  // below have to be set once and the result kept, not re-requested per frame:
  //   · `alpha: false`    — the video is opaque. Without this every frame is
  //                         composited as a transparent surface over the
  //                         window, which in a `transparent: true` window means
  //                         blending it against the vibrancy material 30-60
  //                         times a second for no visible difference.
  //   · `desynchronized`  — lets WebKit skip a compositor round-trip for a
  //                         canvas whose contents are already a video stream.
  const ctxRef = useRef<{ el: HTMLCanvasElement; ctx: CanvasRenderingContext2D } | null>(null);
  const decoderRef = useRef<VideoDecoder | null>(null);
  const tsRef = useRef(0);
  const waitingKeyRef = useRef(true);
  const downRef = useRef<{ x: number; y: number; t: number } | null>(null);
  const [live, setLive] = useState(false);
  const [source, setSource] = useState<"screen" | "camera">("screen");
  const [facing, setFacing] = useState<"front" | "back">("back");
  const [onTop, setOnTop] = useState(false);
  const isCam = source === "camera";

  // ── Saving what's on screen ───────────────────────────────────────────
  // The canvas already holds decoded frames, so a PNG and an MP4 come off
  // work WebKit is doing anyway — see capture.rs for why none of this is
  // done in Rust.
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const [recording, setRecording] = useState(false);
  const [recSeconds, setRecSeconds] = useState(0);
  const [note, setNote] = useState<string | null>(null);

  // This window has no toast system of its own, and a save that reports
  // nothing is indistinguishable from a button that does nothing.
  const say = (text: string) => {
    setNote(text);
    setTimeout(() => setNote((n) => (n === text ? null : n)), 4000);
  };

  const shoot = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    canvas.toBlob((blob) => {
      if (!blob) {
        say(t("Nothing to capture yet"));
        return;
      }
      mirrorSaveCapture(blob, "png")
        .then((path) => say(`Saved ${path.split("/").pop()}`))
        .catch((e) => say(String(e)));
    }, "image/png");
  };

  /// MP4 first, because QuickTime and Finder previews can open it and a WebM
  /// on a Mac is a file most apps refuse. WebKit's MediaRecorder does produce
  /// MP4/H.264 — but this asks rather than assuming, and names the file after
  /// whatever it actually got.
  const pickMime = (): { mime: string; ext: string } | null => {
    if (typeof MediaRecorder === "undefined") return null;
    const candidates: [string, string][] = [
      ["video/mp4;codecs=avc1", "mp4"],
      ["video/mp4", "mp4"],
      ["video/webm;codecs=vp9", "webm"],
      ["video/webm", "webm"],
    ];
    for (const [mime, ext] of candidates) {
      if (MediaRecorder.isTypeSupported?.(mime)) return { mime, ext };
    }
    return null;
  };

  const stopRecording = () => {
    const rec = recorderRef.current;
    // `stop()` fires `onstop`, which is where the file is written — so a
    // recording that ends because the mirror died still gets saved rather
    // than discarded along with the stream.
    if (rec && rec.state !== "inactive") rec.stop();
  };

  const toggleRecording = () => {
    if (recording) {
      stopRecording();
      return;
    }
    const canvas = canvasRef.current;
    const picked = pickMime();
    if (!canvas || !picked) {
      say(t("This build of WebKit can't record video"));
      return;
    }
    try {
      const stream = canvas.captureStream(30);
      const rec = new MediaRecorder(stream, { mimeType: picked.mime });
      chunksRef.current = [];
      rec.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data);
      };
      rec.onstop = () => {
        setRecording(false);
        recorderRef.current = null;
        stream.getTracks().forEach((t) => t.stop());
        const blob = new Blob(chunksRef.current, { type: picked.mime });
        chunksRef.current = [];
        mirrorSaveCapture(blob, picked.ext)
          .then((path) => say(`Saved ${path.split("/").pop()}`))
          .catch((e) => say(String(e)));
      };
      // A timeslice, so a crash mid-recording leaves buffered chunks rather
      // than one unwritten blob held until stop.
      rec.start(1000);
      recorderRef.current = rec;
      setRecording(true);
      setRecSeconds(0);
    } catch (e) {
      say(String(e));
    }
  };

  useEffect(() => {
    if (!recording) return;
    const id = setInterval(() => setRecSeconds((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, [recording]);

  // The stream ending is not a reason to lose the recording — finalise it.
  useEffect(() => {
    if (!live && recorderRef.current) stopRecording();
  }, [live]);

  useEffect(() => {
    const setupDecoder = (codec: string) => {
      const old = decoderRef.current;
      if (old && old.state !== "closed") {
        try {
          old.close();
        } catch {
          /* closing */
        }
      }
      const paint = (frame: VideoFrame) => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        let cached = ctxRef.current;
        if (!cached || cached.el !== canvas) {
          const ctx = canvas.getContext("2d", { alpha: false, desynchronized: true });
          if (!ctx) return;
          cached = { el: canvas, ctx };
          ctxRef.current = cached;
        }
        if (canvas.width !== frame.displayWidth) canvas.width = frame.displayWidth;
        if (canvas.height !== frame.displayHeight) canvas.height = frame.displayHeight;
        cached.ctx.drawImage(frame, 0, 0);
      };

      const dec = new VideoDecoder({
        output: (frame) => {
          // Decode always, paint only when there is something to paint on.
          // Every delta frame is defined against the ones before it, so
          // *skipping decodes* while the window is covered would desync the
          // stream and smear until the next keyframe — but painting pixels
          // behind another app's window is pure waste, and this canvas is the
          // most expensive surface in the app.
          if (!document.hidden) paint(frame);
          frame.close();
        },
        error: (e) => console.warn("mirror decode:", e?.message || e),
      });
      try {
        dec.configure({ codec: codec || "avc1.42E01E", optimizeForLatency: true });
      } catch {
        /* unsupported — the window will just stay blank */
      }
      decoderRef.current = dec;
      tsRef.current = 0;
      waitingKeyRef.current = true;
    };

    let started = false;
    const handleStarted = (m: MirrorStarted) => {
      started = true;
      setSource(m.source || "screen");
      setFacing(m.facing || "back");
      setLive(true);
      setupDecoder(m.codec);
    };

    const offStarted = onMirrorStarted(handleStarted);
    // Attach the raw-frame channel; the resolved value replays a
    // `mirror-started` the phone announced before this window was listening
    // (skipped if the live event already arrived — no double decoder reset).
    mirrorAttach((key, data) => {
      const dec = decoderRef.current;
      if (!dec || dec.state !== "configured") return;
      if (waitingKeyRef.current && !key) return;
      waitingKeyRef.current = false;
      try {
        dec.decode(
          new EncodedVideoChunk({
            type: key ? "key" : "delta",
            timestamp: tsRef.current++,
            data,
          })
        );
      } catch {
        /* drop bad chunk */
      }
    }).then((m) => {
      if (m && !started) handleStarted(m);
    });
    const offStopped = onMirrorStopped(() => {
      getCurrentWindow().close();
    });
    const offError = onMirrorError(() => {
      getCurrentWindow().close();
    });
    return () => {
      offStarted();
      offStopped();
      offError();
      const d = decoderRef.current;
      if (d && d.state !== "closed") {
        try {
          d.close();
        } catch {
          /* closing */
        }
      }
    };
  }, []);

  // Type on the Mac keyboard → inject into the focused field on the phone (screen mode).
  useEffect(() => {
    if (!live || isCam) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey) return; // leave shortcuts alone
      if (e.key === "Backspace") {
        mirrorInput({ type: "mirror-text", op: "backspace" });
        e.preventDefault();
      } else if (e.key === "Enter") {
        mirrorInput({ type: "mirror-text", op: "enter" });
        e.preventDefault();
      } else if (e.key.length === 1) {
        mirrorInput({ type: "mirror-text", text: e.key });
        e.preventDefault();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [live, isCam]);

  // --- control: canvas pointer → 0..1 phone-screen fractions ---
  const frac = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const c = canvasRef.current;
    if (!c) return null;
    const r = c.getBoundingClientRect();
    return {
      x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)),
      y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)),
    };
  };
  const onDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const p = frac(e);
    if (p) downRef.current = { ...p, t: Date.now() };
  };
  const onUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const d = downRef.current;
    downRef.current = null;
    const p = frac(e);
    if (!d || !p) return;
    const dist = Math.hypot(p.x - d.x, p.y - d.y);
    if (dist < 0.02) mirrorInput({ type: "mirror-tap", x: p.x, y: p.y });
    else
      mirrorInput({
        type: "mirror-swipe",
        x1: d.x,
        y1: d.y,
        x2: p.x,
        y2: p.y,
        dur: Math.min(800, Math.max(60, Date.now() - d.t)),
      });
  };
  const onWheel = (e: React.WheelEvent<HTMLCanvasElement>) => {
    const dy = e.deltaY > 0 ? -0.3 : 0.3;
    mirrorInput({ type: "mirror-swipe", x1: 0.5, y1: 0.5, x2: 0.5, y2: 0.5 + dy, dur: 120 });
  };
  const key = (k: string) => mirrorInput({ type: "mirror-key", key: k });
  const flipCamera = () =>
    mirrorInput({ type: "camera-flip", facing: facing === "front" ? "back" : "front" });
  const toggleTop = () => {
    const next = !onTop;
    setOnTop(next);
    mirrorSetOnTop(next);
  };

  return (
    <div className="flex h-screen select-none flex-col bg-ink">
      {/* `data-tauri-drag-region`, not the `.drag` class. That class sets
          `-webkit-app-region: drag`, which despite the prefix is a Chromium
          extension WebKit never implemented — so in this WKWebView it does
          nothing at all, and a frameless window with no native title bar simply
          could not be moved. Tauri checks this attribute on the event target,
          so the label carries it too or dragging by the text wouldn't work. */}
      <div
        data-tauri-drag-region
        className="flex h-9 shrink-0 items-center justify-between border-b border-line bg-panel px-2.5"
      >
        <span
          data-tauri-drag-region
          className="flex items-center gap-1.5 text-[11px] font-medium text-fg/85"
        >
          <span className={`h-1.5 w-1.5 rounded-full ${live ? "led bg-(--color-link)" : "bg-faint"}`} />
          {isCam ? "Camera" : "Mirroring"}
        </span>
        <div className="flex items-center gap-1">
          {!isCam && live && (
            <>
              <Btn title={t("Back")} onClick={() => key("back")}>
                <Icon name="arrowLeft" size={13} />
              </Btn>
              <Btn title={t("Home")} onClick={() => key("home")}>
                <Icon name="circle" size={12} />
              </Btn>
              <Btn title={t("Recents")} onClick={() => key("recents")}>
                <Icon name="squareStack" size={12} />
              </Btn>
            </>
          )}
          {/* Labelled, unlike the nav glyphs above: this is the camera
              window's only control, and as a bare 28px icon among the pin and
              close buttons it read as "there is no way to switch cameras". The
              label doubles as the readout of which camera is live. */}
          {isCam && live && (
            <button
              onClick={flipCamera}
              title={t("Switch front/back camera")}
              className="flex h-7 items-center gap-1.5 rounded-lg px-2 text-[11px] font-medium text-fg/85 transition-colors hover:bg-panel3"
            >
              <Icon name="switchCamera" size={13} />
              {facing === "front" ? "Front" : "Back"}
            </button>
          )}
          {live && (
            <>
              <Btn title={t("Save a still to Pictures/DroidDock")} onClick={shoot}>
                <Icon name="camera" size={12} />
              </Btn>
              <Btn
                title={
                  recording
                    ? t("Stop recording and save to Movies/DroidDock")
                    : t("Record to Movies/DroidDock (video only — phone audio isn't part of this window)")
                }
                onClick={toggleRecording}
                active={recording}
              >
                <Icon name={recording ? "pause" : "circle"} size={12} />
              </Btn>
            </>
          )}
          {recording && (
            <span className="data px-1 text-[11px] text-bad">
              {Math.floor(recSeconds / 60)}:{String(recSeconds % 60).padStart(2, "0")}
            </span>
          )}
          <Btn title={onTop ? t("Unpin (on top)") : t("Keep on top")} onClick={toggleTop} active={onTop}>
            <Icon name="pin" size={12} />
          </Btn>
          <Btn title={t("Close")} onClick={() => getCurrentWindow().close()} danger>
            <Icon name="x" size={13} />
          </Btn>
        </div>
      </div>

      {note && (
        <div className="shrink-0 border-b border-line bg-panel2 px-3 py-1.5 text-[11px] text-fg/80">
          {note}
        </div>
      )}

      <div className="flex min-h-0 flex-1 items-center justify-center">
        {live ? (
          <canvas
            ref={canvasRef}
            onPointerDown={isCam ? undefined : onDown}
            onPointerUp={isCam ? undefined : onUp}
            onWheel={isCam ? undefined : onWheel}
            onContextMenu={
              isCam
                ? undefined
                : (e) => {
                    e.preventDefault();
                    key("back");
                  }
            }
            style={isCam && facing === "front" ? { transform: "scaleX(-1)" } : undefined}
            className={`h-full w-full object-contain ${isCam ? "" : "cursor-pointer"}`}
          />
        ) : (
          <div className="flex flex-col items-center gap-2 px-6 text-center">
            <Icon name="reload" size={16} className="spinner text-dim" />
            <p className="font-display text-[14px] font-semibold text-fg">{t("Approve on your phone")}</p>
            <p className="text-[11.5px] text-dim">{t("Accept the capture request to start streaming.")}</p>
          </div>
        )}
      </div>
    </div>
  );
}

function Btn({
  title,
  onClick,
  children,
  active,
  danger,
}: {
  title: string;
  onClick: () => void;
  children: React.ReactNode;
  active?: boolean;
  danger?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`flex h-7 w-7 items-center justify-center rounded-lg transition-colors ${
        danger
          ? "text-dim hover:bg-bad/15 hover:text-bad"
          : active
            ? "bg-(--color-accent)/15 text-(--color-accent)"
            : "text-dim hover:bg-panel3 hover:text-fg"
      }`}
    >
      {children}
    </button>
  );
}
