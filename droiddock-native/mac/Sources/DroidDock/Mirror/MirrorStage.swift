import SwiftUI

/// The picture + floating control bar + connecting / failed states. Used by the pop-out window
/// (`inWindow: false`) and by `MirrorScreen` / `CameraScreen` (`inWindow: true`).
struct MirrorStage: View {
    let inWindow: Bool
    /// The pop-out's chrome (hover reveal). `nil` in the main window, where the controls are always shown.
    var chrome: MirrorChrome? = nil
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovering = false

    private var s: MirrorSession { MirrorSession.shared }
    private var radius: CGFloat { inWindow ? DD.Radius.lg : 0 }

    var body: some View {
        if inWindow { embedded } else { framed }
    }

    /// The picture with its connecting / failed states on top (never the controls).
    private func screen(radius r: CGFloat) -> some View {
        let live = s.phase == .live
        return ZStack {
            // A plain filled shape (no clip mask over the live layer); the video layer rounds its own corners.
            RoundedRectangle(cornerRadius: r, style: .continuous).fill(.black)
            MirrorLayerView(renderer: s.renderer, input: s.input,
                            inputEnabled: live && !s.isCamera,
                            mirrored: s.isCamera && s.facing == "front", rotation: s.rotation, cropScale: s.cropScale,
                            cornerRadius: r, hidden: !live)
            overlay
            if live && s.isCamera { CameraPictureOverlay(); ZoomDial() }
        }
    }

    /// Pop-out window: the window is the phone. A thin bezel, the window buttons and the name in a slim top strip (quiet
    /// until the pointer is over the window), the controls in a floating capsule panel under the window. Nothing covers the
    /// picture, so Android's edge swipes (top and bottom) stay on the phone.
    private var framed: some View {
        let shown = chrome?.revealed ?? true
        return VStack(spacing: 0) {
            ZStack {
                if let note = s.note {
                    Text(note).font(DD.Font.label).foregroundStyle(DD.OnCard.primary)
                        .ddTransition(.ddRise)
                } else if s.controlHint {
                    Text(tr("Phone control is off")).font(DD.Font.label).foregroundStyle(DD.warn)
                        .ddTransition(.ddRise)
                } else {
                    Text(s.isCamera ? tr("Phone camera") : (model.phoneName ?? tr("Phone screen")))
                        .font(DD.Font.label)
                        .foregroundStyle(shown ? DD.OnCard.secondary : DD.OnCard.tertiary)
                        .ddTransition(.opacity)
                }
            }
            .lineLimit(1).truncationMode(.tail)
            .padding(.horizontal, 76)          // clear of the traffic lights on both sides
            .frame(maxWidth: .infinity)
            .frame(height: MirrorFrame.top)
            .allowsHitTesting(false)
            GeometryReader { g in
                screen(radius: MirrorFrame.radius(forWidth: g.size.width))
            }
            .padding(.horizontal, MirrorFrame.side)
            .padding(.bottom, MirrorFrame.bottom)
        }
        .background(MirrorFrame.background)
        .ddAnimation(Motion.content, value: shown)
        .ddAnimation(Motion.toast, value: s.note)
        .ddAnimation(Motion.toast, value: s.controlHint)
        .onContinuousHover { phase in
            switch phase {
            case .active: chrome?.pointerMovedInWindow()
            case .ended: chrome?.pointerLeftWindow()
            }
        }
        .ignoresSafeArea()
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(s.isCamera ? tr("Phone camera") : tr("Phone screen"))
        // The capsule lives in another panel and hides when idle: the same commands as VoiceOver actions on the window.
        .accessibilityAction(named: Text(tr("Stop"))) { s.stop() }
        .accessibilityAction(named: Text(tr("Save a still to Pictures/DroidDock"))) { s.screenshot() }
        .accessibilityAction(named: Text(s.audioMuted ? tr("Unmute phone audio") : tr("Mute phone audio"))) { s.toggleMute() }
        .accessibilityAction(named: Text(s.onTop ? tr("Unpin (on top)") : tr("Keep on top"))) { s.onTop.toggle() }
    }

    /// Width / height of the phone picture; a plain phone shape until the first size is known.
    private var pictureAspect: CGFloat {
        guard let i = s.info, i.width > 0, i.height > 0 else { return 9.0 / 19.5 }
        let d = s.displaySize(width: i.width, height: i.height)
        return CGFloat(d.width) / CGFloat(d.height)
    }

    /// Inside the main window: same arrangement as the pop-out. Nothing sits on the picture: any note or
    /// hint is above it, the controls below it, and the picture takes all the height left over.
    private var embedded: some View {
        VStack(spacing: DD.Space.sm) {
            ZStack {
                if let note = s.note {
                    Text(note).font(DD.Font.callout).foregroundStyle(DD.fg)
                        .ddTransition(.opacity)
                } else if s.controlHint {
                    ControlHintRow()
                        .ddTransition(.opacity)
                }
            }
            .lineLimit(2).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 20)
            screen(radius: radius)
                .aspectRatio(pictureAspect, contentMode: .fit)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            MirrorControlBar(inWindow: true)
        }
        .ddAnimation(Motion.toast, value: s.note)
        .ddAnimation(Motion.toast, value: s.controlHint)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(s.isCamera ? tr("Phone camera") : tr("Phone screen"))
    }

    @ViewBuilder private var overlay: some View {
        switch s.phase {
        case .live: EmptyView()
        case .failed(let msg):
            VStack(spacing: DD.Space.md) {
                Image(systemName: "exclamationmark.triangle").font(.system(size: 26)).foregroundStyle(DD.bad)
                Text(tr("Couldn't start")).font(DD.Font.title2).foregroundStyle(DD.OnCard.primary)
                Text(msg).font(DD.Font.callout).foregroundStyle(DD.OnCard.secondary)
                    .multilineTextAlignment(.center).frame(maxWidth: 280)
            }.padding(DD.Space.xl)
        default:
            VStack(spacing: DD.Space.md) {
                ProgressView().controlSize(.regular).tint(.white)
                Text(s.isCamera ? tr("Approve the camera on your phone") : tr("Approve on your phone"))
                    .font(DD.Font.title2).foregroundStyle(DD.OnCard.primary)
                Text(tr("Accept the capture request to start streaming."))
                    .font(DD.Font.callout).foregroundStyle(DD.OnCard.secondary)
                    .multilineTextAlignment(.center).frame(maxWidth: 260)
            }.padding(DD.Space.xl)
        }
    }
}

/// Floating pill. Buttons never take keyboard focus; the picture view keeps it for typing.
struct MirrorControlBar: View {
    let inWindow: Bool
    /// The pop-out's chrome: the hint popover holds it open while showing.
    var chrome: MirrorChrome? = nil
    @Environment(AppModel.self) private var model
    @Environment(\.ddReduceTransparency) private var reduceTransparency
    private var s: MirrorSession { MirrorSession.shared }

    var body: some View {
        let live = s.phase == .live
        HStack(spacing: 2) {
            if live && !s.isCamera {
                btn("chevron.backward", tr("Back")) { s.input.key("back") }
                btn("circle", tr("Home")) { s.input.key("home") }
                btn("square.on.square", tr("Recents")) { s.input.key("recents") }
                divider
            }
            if live && s.isCamera { cameraControls }
            if live && !s.isCamera && s.controlHint {
                hintButton
            }
            if live {
                btn("camera.viewfinder", tr("Save a still to Pictures/DroidDock")) { s.screenshot() }
                if model.audio != nil || (s.audioExpected && model.audioError == nil) {
                    btn(s.audioMuted ? "speaker.slash" : "speaker.wave.2",
                        s.audioMuted ? tr("Unmute phone audio") : tr("Mute phone audio"), on: s.audioMuted) { s.toggleMute() }
                }
            }
            btn(inWindow ? "arrow.up.forward.app" : "rectangle.inset.filled.on.rectangle",
                inWindow ? tr("Pop out") : tr("Show in the main window")) { s.setPlacement(inWindow: !inWindow) }
            if !inWindow {
                btn(s.onTop ? "pin.fill" : "pin", s.onTop ? tr("Unpin (on top)") : tr("Keep on top"), on: s.onTop) { s.onTop.toggle() }
            }
            divider
            btn("xmark", live || s.phase == .connecting ? tr("Stop") : tr("Close"), danger: true) { s.stop() }
        }
        .padding(.horizontal, DD.Space.xs).padding(.vertical, DD.Space.xs)
        .background { capsuleFill }
        .overlay(Capsule().strokeBorder(DD.OnCard.stroke))
        .shadow(color: .black.opacity(0.35), radius: 10, y: 4)
        .environment(\.colorScheme, .dark)
        .ddAnimation(Motion.select, value: live)
        .onChange(of: showHint) { _, open in chrome?.hold(open) }
    }

    // MARK: Camera controls

    @ViewBuilder private var cameraControls: some View {
        if s.hasLenses {
            if s.backLenses.count > 1 {
                HStack(spacing: 2) {
                    ForEach(s.backLenses) { l in lensChip(l) }
                }
                .padding(2)
                .background(Capsule().fill(.black.opacity(0.28)))
            } else if let l = s.backLenses.first, s.facing != "front" { lensChip(l) }
            btn("arrow.triangle.2.circlepath.camera", tr("Switch front/back camera")) { s.flipCamera() }
                .disabled(s.switchingLens || s.frontLens == nil)
            btn("rotate.right", tr("Rotate the picture")) { s.rotateManually() }
            divider
            modeSwitch
            shutterButton
            divider
        } else {
            Button { s.flipCamera(); MirrorSurfaceView.refocus() } label: {
                Label(s.facing == "front" ? tr("Front") : tr("Back"), systemImage: "arrow.triangle.2.circlepath.camera")
                    .font(DD.Font.label).foregroundStyle(DD.OnCard.primary)
                    .padding(.horizontal, DD.Space.sm).frame(height: 28)
            }
            .buttonStyle(.plain).focusable(false)
            .help(tr("Switch front/back camera"))
            btn("rotate.right", tr("Rotate the picture")) { s.rotateManually() }
            divider
            modeSwitch
            shutterButton
            divider
        }
    }

    private func lensChip(_ l: MirrorLens) -> some View {
        let on = l.id == s.lens
        let text = on ? l.label : l.label.replacingOccurrences(of: "×", with: "")
        return Button {
            if on, s.zoomRange != nil { s.zoomDialOpen.toggle() } else { s.selectLens(l.id) }
            MirrorSurfaceView.refocus()
        } label: {
            Text(text)
                .font(.system(size: 11, weight: .semibold, design: .rounded)).monospacedDigit()
                .foregroundStyle(on ? Color(nsColor: .systemYellow) : DD.OnCard.secondary)
                .frame(width: 28, height: 28)
                .background(Circle().fill(on ? DD.OnCard.fillActive : .clear))
                .contentShape(Circle())
        }
        .buttonStyle(PressableButtonStyle()).focusable(false)
        .disabled(s.switchingLens)
        .ddAnimation(Motion.select, value: on)
        .help(tr("Lens {name}", ["name": l.label]))
        .accessibilityLabel(tr("Lens {name}", ["name": l.label]))
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private var modeSwitch: some View {
        HStack(spacing: DD.Space.xs) {
            modeText(tr("PHOTO"), .photo)
            modeText(tr("VIDEO"), .video)
        }
        .padding(.horizontal, DD.Space.xs)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(tr("Capture mode"))
    }

    private func modeText(_ t: String, _ m: MirrorSession.CaptureMode) -> some View {
        let on = s.captureMode == m
        return Button { if !s.recording { s.captureMode = m }; MirrorSurfaceView.refocus() } label: {
            Text(t).font(.system(size: 10, weight: .semibold)).tracking(0.8)
                .foregroundStyle(on ? Color(nsColor: .systemYellow) : DD.OnCard.secondary)
                .frame(height: 28)
        }
        .buttonStyle(.plain).focusable(false)
        .ddAnimation(Motion.select, value: on)
        .accessibilityLabel(t.capitalized)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private var shutterButton: some View {
        let video = s.captureMode == .video
        return Button { s.shutter(); MirrorSurfaceView.refocus() } label: {
            ZStack {
                Circle().strokeBorder(.white, lineWidth: 2).frame(width: 30, height: 30)
                RoundedRectangle(cornerRadius: video && s.recording ? 3 : 11, style: .continuous)
                    .fill(video ? Color(nsColor: .systemRed) : .white)
                    .frame(width: video && s.recording ? 12 : 22, height: video && s.recording ? 12 : 22)
            }
            .frame(width: 32, height: 32)
            .contentShape(Circle())
        }
        .buttonStyle(PressableButtonStyle()).focusable(false)
        .ddAnimation(Motion.select, value: s.recording)
        .ddAnimation(Motion.select, value: video)
        .help(video ? (s.recording ? tr("Stop recording") : tr("Start recording")) : tr("Take photo"))
        .accessibilityLabel(video ? (s.recording ? tr("Stop recording") : tr("Start recording")) : tr("Take photo"))
    }

    /// HUD glass over whatever is behind the capsule; a solid dark fill under Reduce Transparency.
    @ViewBuilder private var capsuleFill: some View {
        if reduceTransparency {
            Capsule().fill(Color(white: 0.14))
        } else {
            ZStack {
                Capsule().fill(.black.opacity(0.35))
                Capsule().fill(.ultraThinMaterial).environment(\.colorScheme, .dark)
            }
        }
    }

    @State private var showHint = false
    private var hintButton: some View {
        btn("exclamationmark.circle", tr("Phone control is off"), on: showHint) { showHint.toggle() }
            .foregroundStyle(DD.warn)
            .popover(isPresented: $showHint, arrowEdge: .top) {
                VStack(alignment: .leading, spacing: DD.Space.sm) {
                    Text(tr("Turn on DroidDock's accessibility service on your phone to control it from the Mac"))
                        .font(DD.Font.callout).fixedSize(horizontal: false, vertical: true)
                    Button(tr("Got it")) { showHint = false; s.dismissControlHint() }
                        .buttonStyle(SecondaryButtonStyle())
                }
                .padding(DD.Space.md).frame(width: 260)
            }
    }

    private var divider: some View {
        Rectangle().fill(DD.OnCard.stroke).frame(width: 1, height: 16).padding(.horizontal, DD.Space.xs)
    }

    private func btn(_ symbol: String, _ label: String, on: Bool = false, danger: Bool = false,
                     action: @escaping () -> Void) -> some View {
        BarButton(symbol: symbol, label: label, on: on, danger: danger) {
            action(); MirrorSurfaceView.refocus()
        }
    }
}

private struct BarButton: View {
    let symbol: String, label: String, on: Bool, danger: Bool
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(danger && hover ? DD.bad : DD.OnCard.primary)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 28, height: 28)
                .background(Circle().fill(on ? DD.OnCard.fillActive : (hover ? DD.OnCard.fillHover : .clear)))
                .contentShape(Circle())
        }
        .buttonStyle(PressableButtonStyle())
        .focusable(false)
        .onHover { hover = $0 }
        .ddAnimation(Motion.hover, value: hover)
        .ddAnimation(Motion.select, value: on)
        .ddAnimation(Motion.select, value: symbol)
        .help(label)
        .accessibilityLabel(label)
    }
}

/// The one-time explanation above an in-window picture when the phone cannot be controlled.
private struct ControlHintRow: View {
    var body: some View {
        HStack(spacing: DD.Space.xs) {
            Image(systemName: "exclamationmark.circle").foregroundStyle(DD.warn)
            Text(tr("Turn on DroidDock's accessibility service on your phone to control it from the Mac"))
                .foregroundStyle(DD.fg)
            Button(tr("Got it")) { MirrorSession.shared.dismissControlHint() }
                .buttonStyle(.link)
        }
        .font(DD.Font.callout)
    }
}

/// On the camera picture: the shutter flash, the recording pill (top centre) and the "Switching…" state.
private struct CameraPictureOverlay: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var flash = false
    private var s: MirrorSession { MirrorSession.shared }

    var body: some View {
        ZStack(alignment: .top) {
            Rectangle().fill(.white).opacity(flash ? 0.7 : 0).allowsHitTesting(false)
            if s.zoomPillVisible {
                Text(String(format: "%.1f×", s.requestedZoom)).font(DD.Font.label).monospacedDigit()
                    .foregroundStyle(Color(nsColor: .systemYellow))
                    .padding(.horizontal, DD.Space.sm).padding(.vertical, DD.Space.xs)
                    .background(Capsule().fill(.black.opacity(0.55)))
                    .frame(maxHeight: .infinity, alignment: .bottom).padding(.bottom, DD.Space.xl)
                    .ddTransition(.opacity)
                    .accessibilityHidden(true)
            }
            if s.recording { recordingPill.padding(.top, DD.Space.sm).ddTransition(.opacity) }
            if s.switchingLens {
                HStack(spacing: DD.Space.xs) {
                    ProgressView().controlSize(.small).tint(.white)
                    Text(tr("Switching…")).font(DD.Font.label).foregroundStyle(DD.OnCard.primary)
                }
                .padding(.horizontal, DD.Space.sm).padding(.vertical, DD.Space.xs)
                .background(Capsule().fill(.black.opacity(0.55)))
                .frame(maxHeight: .infinity, alignment: .center)
                .ddTransition(.opacity)
                .accessibilityElement(children: .combine)
            }
        }
        .allowsHitTesting(false)
        .ddAnimation(Motion.content, value: s.recording)
        .ddAnimation(Motion.content, value: s.switchingLens)
        .ddAnimation(Motion.content, value: s.zoomPillVisible)
        .onChange(of: s.shutterTick) { _, _ in
            guard !reduceMotion, !Motion.systemReduceMotion else { return }
            flash = true
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(30))
                withAnimation(.easeOut(duration: 0.25)) { flash = false }
            }
        }
    }

    private var recordingPill: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            HStack(spacing: DD.Space.xs) {
                Circle().fill(Color(nsColor: .systemRed)).frame(width: 8, height: 8)
                if let start = s.recordStart {
                    let t = max(0, Int(ctx.date.timeIntervalSince(start)))
                    Text(String(format: "%02d:%02d", t / 60, t % 60)).monospacedDigit()
                } else {
                    Text(tr("Waiting for keyframe…"))
                }
            }
            .font(DD.Font.label).foregroundStyle(DD.OnCard.primary)
            .padding(.horizontal, DD.Space.sm).padding(.vertical, DD.Space.xs)
            .background(Capsule().fill(.black.opacity(0.55)))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(tr("Recording"))
        }
    }
}

/// Compact zoom dial over the picture's lower edge: log-scale slider with ticks at the lens multipliers. Shown when the
/// active lens chip was clicked and the stream reports a zoom range.
private struct ZoomDial: View {
    private var s: MirrorSession { MirrorSession.shared }
    var body: some View {
        if s.zoomDialOpen, let r = s.zoomRange {
            let lo = log(r.lowerBound), hi = log(max(r.upperBound, r.lowerBound * 1.01))
            VStack {
                Spacer()
                VStack(spacing: 2) {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            ForEach(s.backLenses.compactMap { $0.zoom }, id: \.self) { z in
                                Rectangle().fill(DD.OnCard.secondary).frame(width: 1, height: 8)
                                    .offset(x: g.size.width * CGFloat((log(z) - lo) / (hi - lo)))
                            }
                        }.frame(maxHeight: .infinity, alignment: .bottom)
                    }.frame(height: 8).padding(.horizontal, 8)
                    Slider(value: Binding(get: { log(min(max(s.requestedZoom, r.lowerBound), r.upperBound)) },
                                          set: { s.setZoom(exp($0)) }), in: lo...hi)
                        .controlSize(.small).tint(Color(nsColor: .systemYellow))
                        .accessibilityLabel(tr("Zoom"))
                        .accessibilityValue(String(format: "%.1f×", s.requestedZoom))
                        .accessibilityAdjustableAction { d in _ = s.zoomKey(d == .increment ? 1 : -1) }
                }
                .padding(.horizontal, DD.Space.sm).padding(.vertical, DD.Space.xs)
                .frame(maxWidth: 280)
                .background(Capsule().fill(.black.opacity(0.55)))
                .padding(.bottom, DD.Space.md)
                .ddTransition(.opacity)
            }
        }
    }
}
