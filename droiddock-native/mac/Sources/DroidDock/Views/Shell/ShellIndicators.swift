import SwiftUI

/// Small live status marks shared by the shells (Classic's Transfers row, Phone tabs' tab bar). Each reads its own
/// state, so a progress tick or a stream change invalidates only the mark, not the sidebar or toolbar around it.

/// Finder-style determinate ring for the active transfers: the mean of their fractions, so byte and item transfers
/// mix fairly. A mini spinner while no total is known yet. Draws nothing while nothing is moving.
struct TransferProgressRing: View {
    @Environment(AppModel.self) private var model
    var diameter: CGFloat = 13
    /// False inside a toolbar item: animating there re-runs the window's constraint pass every frame and, with progress
    /// ticking several times a second, can exhaust AppKit's pass limit and crash (see SegmentedTabBar).
    var animated: Bool = true

    private var fraction: Double? {
        var sum = 0.0, n = 0
        for t in model.transfers where t.isActive && t.total > 0 {
            sum += min(1, max(0, t.done / t.total))
            n += 1
        }
        return n > 0 ? sum / Double(n) : nil
    }

    var body: some View {
        let active = model.activeTransferCount > 0
        ZStack {
            if active {
                Group {
                    if let f = fraction {
                        let pct = Text(verbatim: "\(Int((f * 100).rounded()))%")
                        ZStack {
                            Circle().stroke(DD.line, lineWidth: 2)
                            Circle().trim(from: 0, to: max(0.02, f))
                                .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                                .ddAnimation(Motion.progress, value: animated ? f : 0)
                        }
                        .frame(width: diameter, height: diameter)
                        // No `.help` here: it would replace the host's tooltip (the tab's title + ⌘ shortcut, the row's title).
                        .accessibilityLabel(pct)
                    } else {
                        ProgressView().controlSize(.mini)
                    }
                }
                .ddTransition(animated ? .scale(scale: 0.7).combined(with: .opacity) : .identity)
            }
        }
        .ddAnimation(Motion.expand, value: animated ? active : false)
    }
}

/// Green "on air" dot while the phone's screen or camera is streaming (the colour macOS uses for camera-in-use),
/// with the word for VoiceOver. No tooltip of its own: the host segment keeps its title and ⌘ shortcut.
struct MirrorLiveDot: View {
    @State private var session = MirrorSession.shared
    var size: CGFloat = 6
    /// False inside a toolbar item (see TransferProgressRing.animated).
    var animated: Bool = true

    var body: some View {
        ZStack {
            if session.isLive {
                Circle().fill(DD.ok).frame(width: size, height: size)
                    .accessibilityLabel(tr("Live"))
                    .ddTransition(animated ? .scale(scale: 0.5).combined(with: .opacity) : .identity)
            }
        }
        .ddAnimation(Motion.select, value: animated ? session.isLive : false)
    }
}
