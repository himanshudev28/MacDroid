import SwiftUI

/// Large now-playing view: artwork, tinted backdrop, seek with haptics, transport, phone volume.
struct NowPlayingView: View {
    let media: MediaState
    let art: NSImage?
    let tint: Color?

    @State private var asOf = Date()
    @State private var scrub: Double?
    @State private var volume: Double = 0
    @State private var lastSentVol = -1

    private var playing: Bool { media.playing ?? false }
    private var durSec: Double { (media.dur ?? 0) / 1000 }
    private var hasVol: Bool { (media.volMax ?? 0) > 0 }

    var body: some View {
        ScrollView {
            VStack(spacing: DD.Space.xl) {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .center, spacing: 36) { artwork(260); controls.frame(maxWidth: 420) }
                    VStack(spacing: DD.Space.xl) { artwork(220); controls.frame(maxWidth: 420) }
                }
                .frame(maxWidth: .infinity)
                MacMediaShareCard()
            }
            .padding(DD.Space.content)
            .frame(maxWidth: .infinity)
        }
        .onChange(of: media) { _, _ in asOf = Date() }
        .onAppear { asOf = Date(); volume = media.vol ?? 0 }
        .onChange(of: media.vol) { _, v in if scrubbingVolume == false { volume = v ?? 0 } }
        .background { transportShortcuts }
    }

    // MARK: Artwork

    private func artwork(_ side: CGFloat) -> some View {
        ZStack {
            if let art {
                Image(nsImage: art).resizable().aspectRatio(contentMode: .fill)
                    .transition(.opacity)
            } else {
                Rectangle().fill(DD.panel2)
                Image(systemName: "music.note").font(.system(size: side * 0.28))
                    .symbolRenderingMode(.hierarchical).foregroundStyle(DD.faint)
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous))
        .ddHairline(RoundedRectangle(cornerRadius: DD.Radius.lg, style: .continuous))
        .scaleEffect(playing ? 1 : 0.94)
        .ddAnimation(Motion.expand, value: playing)
        .ddAnimation(Motion.content, value: media.trackKey)
        .accessibilityHidden(true)
    }

    // MARK: Info + controls

    private var controls: some View {
        VStack(alignment: .leading, spacing: DD.Space.lg) {
            VStack(alignment: .leading, spacing: 4) {
                Text(media.title?.isEmpty == false ? media.title! : tr("Unknown track"))
                    .font(DD.Font.title).foregroundStyle(DD.fg).lineLimit(2)
                if let artist = media.artist, !artist.isEmpty {
                    Text(artist).font(DD.Font.title3).foregroundStyle(DD.dim).lineLimit(1)
                }
                HStack(spacing: 6) {
                    Image(systemName: playing ? "waveform" : "pause.fill")
                        .contentTransition(.symbolEffect(.replace))
                    Text(playing ? tr("Playing") : tr("Paused")).contentTransition(.opacity)
                    if let app = media.app, !app.isEmpty { Text("· \(app)") }
                }
                .font(DD.Font.callout).foregroundStyle(DD.dim).padding(.top, 2)
            }
            .id(media.trackKey)
            .transition(.opacity)
            .ddAnimation(Motion.content, value: media.trackKey)
            .accessibilityElement(children: .combine)

            if durSec > 0 { seek }
            transport.frame(maxWidth: .infinity)
            if hasVol { volumeRow }
        }
    }

    private var seek: some View {
        TimelineView(.periodic(from: .now, by: playing ? UIActivity.shared.period(1) : 3600)) { ctx in   // idle when paused
            let base = (media.pos ?? 0) / 1000
            let live = playing ? base + ctx.date.timeIntervalSince(asOf) : base
            let pos = min(max(scrub ?? live, 0), durSec)
            SeekBar(position: pos, duration: durSec,
                    onScrub: { scrub = $0 },
                    onCommit: { v in
                        scrub = nil; asOf = Date()
                        send("seek", v * 1000)
                    })
        }
    }

    private var transport: some View {
        HStack(spacing: 28) {
            Button { send("prev") } label: { Image(systemName: "backward.fill").font(.system(size: 20)) }
                .buttonStyle(TransportStyle()).help(tr("Previous  ⌘←")).accessibilityLabel(tr("Previous"))
            Button { send(playing ? "pause" : "play") } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.system(size: 30))
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(TransportStyle(primary: true))
            .help(playing ? tr("Pause  Space") : tr("Play  Space"))
            .accessibilityLabel(playing ? tr("Pause") : tr("Play"))
            Button { send("next") } label: { Image(systemName: "forward.fill").font(.system(size: 20)) }
                .buttonStyle(TransportStyle()).help(tr("Next  ⌘→")).accessibilityLabel(tr("Next"))
        }
    }

    @State private var scrubbingVolume = false
    private var volumeRow: some View {
        let maxV = media.volMax ?? 1
        return HStack(spacing: DD.Space.md) {
            Image(systemName: volume <= 0 ? "speaker.slash.fill" : "speaker.fill").foregroundStyle(DD.dim)
                .frame(width: 18).contentTransition(.symbolEffect(.replace))
            Slider(value: $volume, in: 0...maxV, step: 1) { editing in
                scrubbingVolume = editing
                if !editing, volume <= 0 || volume >= maxV { Haptics.levelChange() }
            }
            .controlSize(.small)
            .onChange(of: volume) { _, v in
                let step = Int(v.rounded())
                guard step != lastSentVol else { return }
                lastSentVol = step
                send("setvol", Double(step))
            }
            Image(systemName: "speaker.wave.3.fill").foregroundStyle(DD.dim)
            Text("\(Int((volume / maxV * 100).rounded()))%").font(DD.Font.caption).monospacedDigit()
                .foregroundStyle(DD.dim).frame(width: 36, alignment: .trailing)
                .contentTransition(.numericText())
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(tr("Phone volume"))
    }

    private var transportShortcuts: some View {
        Group {
            Button("") { send(playing ? "pause" : "play") }.keyboardShortcut(.space, modifiers: [])
            Button("") { send("prev") }.keyboardShortcut(.leftArrow, modifiers: .command)
            Button("") { send("next") }.keyboardShortcut(.rightArrow, modifiers: .command)
        }
        .opacity(0).frame(width: 0, height: 0).accessibilityHidden(true)
    }

    private func send(_ cmd: String, _ value: Double = 0) {
        Task { try? await Core.shared.mediaCmd(cmd, value: value) }
    }
}

/// Music-style transport: plain glyphs in the label colour, a quiet circle on hover, no filled accent button.
private struct TransportStyle: ButtonStyle {
    var primary = false
    @State private var hover = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(primary || hover ? DD.fg : DD.dim)
            .frame(width: primary ? 52 : 40, height: primary ? 52 : 40)
            .background(Circle().fill(configuration.isPressed ? DD.panel3 : (hover ? DD.hover : .clear)))
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .animation(Motion.press, value: configuration.isPressed)
            .onHover { hover = $0 }
            .animation(Motion.hover, value: hover)
    }
}

/// Mac -> phone media sharing (`macMediaSync`, `macMediaBrowser`).
struct MacMediaShareCard: View {
    @Environment(AppModel.self) private var model

    private func bind(_ key: String, _ get: @escaping (DroidConfig) -> Bool) -> Binding<Bool> {
        Binding(get: { model.config.map(get) ?? false }, set: { model.setSetting(key, $0) })
    }

    var body: some View {
        let sync = model.config?.macMediaSync ?? false
        SettingsSection(tr("Share from this Mac")) {
            SettingsRow(tr("Send what's playing to the phone"),
                        description: tr("Shows the current track on the phone's home screen. Works for Music, Spotify and media websites.")) {
                Toggle(tr("Send what's playing to the phone"), isOn: bind("macMediaSync") { $0.macMediaSync })
                    .toggleStyle(.switch).labelsHidden()
            }
            if sync {
                SettingsRow(tr("Include media websites"),
                            description: tr("Reads the active browser tab's title, only on known media sites. Other tabs are ignored.")) {
                    Toggle(tr("Include media websites"), isOn: bind("macMediaBrowser") { $0.macMediaBrowser })
                        .toggleStyle(.switch).labelsHidden()
                }
                .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .ddAnimation(Motion.expand, value: sync)
    }
}
