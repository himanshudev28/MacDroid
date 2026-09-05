import {
  audioAttach,
  onAudioStarted,
  onAudioStopped,
  onAudioError,
  type AudioStarted,
} from "./bridge";

/// Plays the phone's PCM stream (binary kind-4 frames) through WebAudio.
///
/// Chunks are scheduled onto the AudioContext clock rather than pushed into a
/// worklet: an AudioWorklet has to be loaded from a URL, which under this app's
/// CSP means a blob: source we would have to allow, and the whole point of the
/// raw-PCM wire format was to avoid adding failure modes. Scheduled
/// `AudioBufferSourceNode`s need no module loading, no CSP change, and cannot
/// fail to initialise.
///
/// The scheduler keeps a small lead over the clock so network jitter does not
/// produce gaps, and hard-resyncs whenever it falls behind or drifts too far
/// ahead — an unbounded queue would otherwise turn a burst into permanent
/// latency that never recovers.

/// How far ahead of the clock to schedule. Below ~80 ms, ordinary Wi-Fi jitter
/// starts producing audible gaps; above ~200 ms the lag behind the video is
/// noticeable on anything with lip-sync.
const LEAD = 0.12;

/// Resync rather than let the queue grow past this. Reached when the phone
/// produces faster than this clock consumes (they are different crystals), or
/// after a burst of buffered chunks arrives at once.
const MAX_LEAD = 0.4;

export class PhoneAudio {
  private ctx: AudioContext | null = null;
  private gain: GainNode | null = null;
  private nextTime = 0;
  private sampleRate = 48000;
  private channels = 2;
  private volume = 1;
  private muted = false;

  /// Set once a stream is announced; guards against samples arriving before
  /// (or after) a format is known, which would otherwise play at the wrong rate.
  private live = false;

  onError: ((msg: string) => void) | null = null;
  onLiveChange: ((live: boolean) => void) | null = null;

  start(m: AudioStarted) {
    this.sampleRate = m.sampleRate > 0 ? m.sampleRate : 48000;
    this.channels = m.channels > 0 ? m.channels : 2;
    // A format we do not understand must not be fed to the player as if it
    // were s16le — that produces loud noise, the worst possible failure here.
    if (m.format && m.format !== "pcm_s16le") {
      this.live = false;
      this.onError?.(`unsupported audio format ${m.format}`);
      return;
    }
    this.ensureContext();
    this.nextTime = 0;
    this.live = true;
    this.onLiveChange?.(true);
  }

  stop() {
    if (!this.live) return;
    this.live = false;
    this.nextTime = 0;
    this.onLiveChange?.(false);
  }

  setVolume(v: number) {
    this.volume = Math.max(0, Math.min(1, v));
    this.applyGain();
  }

  setMuted(m: boolean) {
    this.muted = m;
    this.applyGain();
  }

  private applyGain() {
    const g = this.gain;
    if (!g || !this.ctx) return;
    // A ramp, not an assignment: stepping gain discontinuously on a running
    // signal is an audible click.
    g.gain.setTargetAtTime(this.muted ? 0 : this.volume, this.ctx.currentTime, 0.01);
  }

  private ensureContext() {
    if (!this.ctx) {
      // Pinning latencyHint keeps WebKit from choosing a large output buffer,
      // which would add fixed lag on top of the scheduling lead.
      this.ctx = new AudioContext({ sampleRate: this.sampleRate, latencyHint: "interactive" });
      this.gain = this.ctx.createGain();
      this.gain.connect(this.ctx.destination);
      this.applyGain();
    }
    // Autoplay policy can leave the context suspended. Mirroring always starts
    // from a click, so there is a recent gesture and this resolves — but it is
    // async, and chunks arriving meanwhile are simply scheduled slightly late.
    if (this.ctx.state === "suspended") void this.ctx.resume();
  }

  /// One PCM chunk. `reset` marks the first chunk after a silent gap, where the
  /// schedule is guaranteed stale.
  push(reset: boolean, pcm: Uint8Array) {
    if (!this.live) return;
    const ctx = this.ctx;
    const gain = this.gain;
    if (!ctx || !gain) return;

    const bytesPerFrame = 2 * this.channels;
    const frames = Math.floor(pcm.length / bytesPerFrame);
    if (frames === 0) return;

    const buf = ctx.createBuffer(this.channels, frames, this.sampleRate);
    // DataView rather than Int16Array on purpose: these bytes are a subarray at
    // byte-offset 1 of the channel payload, so an Int16Array view over the same
    // buffer would throw on alignment. getInt16 is offset-agnostic, and s16le
    // needs the explicit little-endian flag regardless.
    const view = new DataView(pcm.buffer, pcm.byteOffset, frames * bytesPerFrame);
    for (let ch = 0; ch < this.channels; ch++) {
      const out = buf.getChannelData(ch);
      for (let i = 0; i < frames; i++) {
        out[i] = view.getInt16(i * bytesPerFrame + ch * 2, true) / 32768;
      }
    }

    const now = ctx.currentTime;
    if (reset || this.nextTime < now || this.nextTime > now + MAX_LEAD) {
      // Behind the clock means a gap already happened; too far ahead means
      // latency is accumulating. Both are fixed by dropping back to one lead.
      this.nextTime = now + LEAD;
    }

    const src = ctx.createBufferSource();
    src.buffer = buf;
    src.connect(gain);
    src.start(this.nextTime);
    this.nextTime += buf.duration;
  }

  async close() {
    this.live = false;
    const ctx = this.ctx;
    this.ctx = null;
    this.gain = null;
    if (ctx) await ctx.close().catch(() => {});
  }
}

/// Wire a player to the backend's audio stream. Returns a teardown function.
export function attachPhoneAudio(player: PhoneAudio) {
  const offStarted = onAudioStarted((m) => player.start(m));
  const offStopped = onAudioStopped(() => player.stop());
  const offError = onAudioError((e) => {
    player.stop();
    player.onError?.(e?.error || "audio failed");
  });
  // The attach both registers the PCM channel and replays an `audio-started`
  // that beat this window's mount — without it, audio that began during app
  // launch would arrive as samples with no declared format and be dropped.
  void audioAttach((reset, pcm) => player.push(reset, pcm)).then((m) => {
    if (m) player.start(m);
  });
  return () => {
    offStarted();
    offStopped();
    offError();
    void player.close();
  };
}

/// Does this WebView's `VideoDecoder` actually support HEVC? Probed once at
/// startup so the backend never negotiates a stream that cannot be decoded.
export async function probeHevc(): Promise<boolean> {
  if (typeof VideoDecoder === "undefined") return false;
  // Two spellings, because they are not interchangeable: `hvc1` expects
  // parameter sets out of band, `hev1` allows them in band, and implementations
  // differ on which they will accept for an Annex-B stream. Our stream carries
  // them in band, so either answer being yes is enough to proceed.
  for (const codec of ["hvc1.1.6.L93.B0", "hev1.1.6.L93.B0"]) {
    try {
      const r = await VideoDecoder.isConfigSupported({ codec });
      if (r?.supported) return true;
    } catch {
      /* not supported — try the other spelling */
    }
  }
  return false;
}
