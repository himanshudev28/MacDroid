import { useEffect, useRef, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import Icon from "./Icon";
import {
  mirrorAttach,
  mirrorInput,
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
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const decoderRef = useRef<VideoDecoder | null>(null);
  const tsRef = useRef(0);
  const waitingKeyRef = useRef(true);
  const downRef = useRef<{ x: number; y: number; t: number } | null>(null);
  const [live, setLive] = useState(false);
  const [source, setSource] = useState<"screen" | "camera">("screen");
  const [facing, setFacing] = useState<"front" | "back">("back");
  const [onTop, setOnTop] = useState(false);
  const isCam = source === "camera";

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
      const dec = new VideoDecoder({
        output: (frame) => {
          const canvas = canvasRef.current;
          const ctx = canvas?.getContext("2d");
          if (canvas && ctx) {
            if (canvas.width !== frame.displayWidth) canvas.width = frame.displayWidth;
            if (canvas.height !== frame.displayHeight) canvas.height = frame.displayHeight;
            ctx.drawImage(frame, 0, 0);
          }
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
      <div className="drag flex h-9 shrink-0 items-center justify-between border-b border-line bg-panel px-2.5">
        <span className="flex items-center gap-1.5 text-[11px] font-medium text-fg/85">
          <span className={`h-1.5 w-1.5 rounded-full ${live ? "led bg-(--color-link)" : "bg-faint"}`} />
          {isCam ? "Camera" : "Mirroring"}
        </span>
        <div className="no-drag flex items-center gap-1">
          {!isCam && live && (
            <>
              <Btn title="Back" onClick={() => key("back")}>
                <Icon name="arrowLeft" size={13} />
              </Btn>
              <Btn title="Home" onClick={() => key("home")}>
                <Icon name="circle" size={12} />
              </Btn>
              <Btn title="Recents" onClick={() => key("recents")}>
                <Icon name="squareStack" size={12} />
              </Btn>
            </>
          )}
          {isCam && live && (
            <Btn title="Switch front/back camera" onClick={flipCamera}>
              <Icon name="switchCamera" size={13} />
            </Btn>
          )}
          <Btn title={onTop ? "Unpin (on top)" : "Keep on top"} onClick={toggleTop} active={onTop}>
            <Icon name="pin" size={12} />
          </Btn>
          <Btn title="Close" onClick={() => getCurrentWindow().close()} danger>
            <Icon name="x" size={13} />
          </Btn>
        </div>
      </div>

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
            <p className="font-display text-[14px] font-semibold text-fg">Approve on your phone</p>
            <p className="text-[11.5px] text-dim">Accept the capture request to start streaming.</p>
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
